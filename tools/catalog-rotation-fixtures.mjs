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
function envelope(version, signingKey, targetBytes) {
  let payload = JSON.stringify({ schemaVersion: 1, version,
    publishedAt: "2026-01-01T00:00:00Z", indicators: [{
      packageName: "com.catalogvalidation.fixture", appName: "Harmless QA fixture",
      threatType: "popup", severity: "high", description: targetBytes
        ? "Proteção sintética UTF-8: ação, café, 漢字, 🛡️" : "Synthetic rotation test, not malware",
      apkSha256: [fixtureHash], status: "confirmed",
      source: { name: "QA", url: "https://example.org/qa", license: "CC0-1.0",
        licenseUrl: "https://creativecommons.org/publicdomain/zero/1.0/",
        attribution: "Synthetic disposable QA metadata" },
      review: { reviewer: "QA", reviewedAt: "2026-01-01T00:00:00Z", evidence: "Harmless local APK" }
    }] });
  if (targetBytes !== undefined) {
    // JSON permits trailing whitespace in the payload. It is INSIDE the
    // signature, not untrusted outer padding, and does not exceed field limits.
    // RSA-3072 base64 signatures and SHA-256 hex digests have fixed lengths.
    const skeleton = JSON.stringify({ format: 2, payload, sha256: "0".repeat(64),
      keyId: signingKey.id, signature: "A".repeat(512) });
    const padding = targetBytes - Buffer.byteLength(skeleton, "utf8");
    if (padding < 0) throw new Error("Boundary envelope target is too small");
    payload += " ".repeat(padding);
  }
  const sha256 = crypto.createHash("sha256").update(payload).digest("hex");
  const signature = crypto.sign("sha256",
    Buffer.from(`maguardian/threat-catalog/v2\n${signingKey.id}\n${sha256}\n${payload}`),
    signingKey.privateKey).toString("base64");
  const raw = JSON.stringify({ format: 2, payload, sha256, keyId: signingKey.id, signature });
  if (targetBytes !== undefined && Buffer.byteLength(raw, "utf8") !== targetBytes) {
    throw new Error("Boundary envelope byte count mismatch");
  }
  return raw;
}
const catalogs = {
  "old-bootstrap": envelope(10, old), "old-cache": envelope(11, old),
  "concurrent-lower": envelope(12, old),
  // Highest version accepted by the old APK, seeded without any transition APK.
  "direct-old-cache": envelope(13, old),
  successor: envelope(12, successor), "revoked-cache": envelope(13, old),
  "recovery-bootstrap": envelope(14, successor), "revoked-update": envelope(15, old),
  "recovery-update": envelope(16, successor)
};
// Opt-in only: do not enlarge ordinary rotation/upgrade APKs. Read the real
// production limit rather than introducing a second independent QA limit.
const boundary = process.argv[3] === "--size-boundary";
if (process.argv[3] && !boundary) throw new Error("Unknown fixture option");
if (boundary) {
  const source = fs.readFileSync(path.join(root,
    "app/src/main/java/com/maguardian/security/data/ThreatCatalog.kt"), "utf8");
  const expression = source.match(/const val MAX_BYTES = ([\d *]+)\r?$/m)?.[1];
  if (!expression) throw new Error("Cannot read catalog MAX_BYTES");
  const maxBytes = expression.split("*").map(part => Number(part.trim()))
    .reduce((a, b) => a * b, 1);
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) throw new Error("Invalid MAX_BYTES");
  catalogs["boundary-exact"] = envelope(12, old, maxBytes);
  catalogs["boundary-plus-one"] = envelope(13, old, maxBytes + 1);
} else {
  // Prevent stale opt-in assets from leaking into subsequent normal QA runs.
  for (const name of ["exact", "plus-one"]) {
    fs.rmSync(path.join(assets, `rotation-boundary-${name}.json`), { force: true });
  }
}
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
