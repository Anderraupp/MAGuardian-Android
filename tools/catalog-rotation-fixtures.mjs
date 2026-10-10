import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

// Never reads environment secrets. Private QA keys stay in ignored build output.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const build = path.join(root, "app/build");
const session = path.join(build, "catalogRotationSession");
const assets = path.join(build, "generated/catalogRotationAssets");
const stage = process.argv[2];
if (!["old", "transition", "recovery", "new-session"].includes(stage)) {
  throw new Error("Expected old, transition, recovery or new-session.");
}
if (stage === "new-session") {
  fs.rmSync(session, { recursive: true, force: true });
  fs.rmSync(assets, { recursive: true, force: true });
}
fs.mkdirSync(session, { recursive: true, mode: 0o700 });
function key(name) {
  const file = path.join(session, `${name}.pem`);
  if (!fs.existsSync(file)) {
    const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 3072 });
    fs.writeFileSync(file, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  }
  const privateKey = crypto.createPrivateKey(fs.readFileSync(file));
  const spki = crypto.createPublicKey(privateKey).export({ type: "spki", format: "der" });
  return { privateKey, spki: spki.toString("base64"),
    id: crypto.createHash("sha256").update(spki).digest("hex") };
}
const old = key("old");
const successor = key("successor");
if (stage === "new-session") process.exit(0);
const fixtureHashPath = path.join(session, "fixture-sha256.txt");
const fixtureHash = fs.existsSync(fixtureHashPath) ? fs.readFileSync(fixtureHashPath, "utf8").trim() : "0".repeat(64);
if (!/^[0-9a-f]{64}$/.test(fixtureHash)) throw new Error("Invalid harmless fixture APK hash");
fs.mkdirSync(assets, { recursive: true });
function envelope(version, signingKey) {
  const payload = JSON.stringify({ schemaVersion: 1, version,
    publishedAt: "2026-01-01T00:00:00Z", indicators: [{
      packageName: "com.catalogvalidation.fixture", appName: "Harmless QA fixture",
      threatType: "popup", severity: "high", description: "Synthetic rotation test, not malware",
      apkSha256: [fixtureHash], status: "confirmed",
      source: { name: "QA", url: "https://example.org/qa", license: "CC0-1.0",
        licenseUrl: "https://creativecommons.org/publicdomain/zero/1.0/",
        attribution: "Synthetic disposable QA metadata" },
      review: { reviewer: "QA", reviewedAt: "2026-01-01T00:00:00Z", evidence: "Harmless local APK" }
    }] });
  const sha256 = crypto.createHash("sha256").update(payload).digest("hex");
  const signature = crypto.sign("sha256",
    Buffer.from(`maguardian/threat-catalog/v2\n${signingKey.id}\n${sha256}\n${payload}`),
    signingKey.privateKey).toString("base64");
  return JSON.stringify({ format: 2, payload, sha256, keyId: signingKey.id, signature });
}
const catalogs = {
  "old-bootstrap": envelope(10, old), "old-cache": envelope(11, old),
  "concurrent-lower": envelope(12, old),
  // Highest version accepted by the old APK, seeded without any transition APK.
  "direct-old-cache": envelope(13, old),
  successor: envelope(12, successor), "revoked-cache": envelope(13, old),
  "recovery-bootstrap": envelope(14, successor), "revoked-update": envelope(15, old),
  "recovery-update": envelope(16, successor),
  // Accepted during transition and still trusted after recovery; newer than bootstrap 14.
  "successor-newer-cache": envelope(17, successor)
};
// Negative persisted-cache fixtures, never publishable catalogs. Recomputing
// the checksum cannot authorize removal of indicators without a new signature.
const tampered = JSON.parse(catalogs["direct-old-cache"]);
const tamperedPayload = JSON.parse(tampered.payload);
tamperedPayload.indicators = [];
tampered.payload = JSON.stringify(tamperedPayload);
tampered.sha256 = crypto.createHash("sha256").update(tampered.payload).digest("hex");
catalogs["corrupt-tampered-cache"] = JSON.stringify(tampered);
catalogs["corrupt-truncated-cache"] = catalogs["direct-old-cache"].slice(
  0, Math.floor(catalogs["direct-old-cache"].length / 2));
for (const [name, raw] of Object.entries(catalogs)) {
  fs.writeFileSync(path.join(assets, `rotation-${name}.json`), raw);
}
fs.writeFileSync(path.join(assets, "threat-catalog-bootstrap.json"),
  catalogs[stage === "recovery" ? "recovery-bootstrap" : "old-bootstrap"]);
fs.writeFileSync(path.join(assets, "rotation-policy.json"), JSON.stringify({
  stage, old: old.id, successor: successor.id, fixtureHash,
  // Recipe only: never bundle/upload the large persisted-cache content.
  oversizedCacheBytes: 8 * 1024 * 1024
}));
const entries = stage === "old" ? [[old, 10, 13, false]] :
  [[old, 10, 13, stage === "recovery"], [successor, 12, 2147483647, false]];
const source = `package com.maguardian.security.data

// Immutable QA pins: changed only by replacing the disposable APK.
object ThreatCatalogKeys {
    data class Key(val spkiBase64: String, val minimumVersion: Int,
                   val maximumVersion: Int, val revoked: Boolean)
    val trusted: Map<String, Key> = mapOf(
${entries.map(([k, min, max, revoked]) =>
  `        "${k.id}" to Key("${k.spki}", ${min}, ${max}, ${revoked})`).join(",\n")}
    )
}
`;
const sourceDir = path.join(build, "generated/catalogValidationKeys/com/maguardian/security/data");
fs.mkdirSync(sourceDir, { recursive: true });
fs.writeFileSync(path.join(sourceDir, "ValidationCatalogKeys.kt"), source);
console.log(`Prepared immutable ${stage} QA policy and signed bootstrap (public artifacts only).`);
