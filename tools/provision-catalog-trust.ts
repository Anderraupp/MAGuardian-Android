import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { publicSigningKey, validateTrustedKeys, makeEnvelope, payloadSchema } from "../shared/threat-catalog";

// Writes PUBLIC anchors and a signed offline bootstrap only. No private file,
// no network, no Git push, no APK upload and no automatic key generation.
const pem = process.env.THREAT_CATALOG_SIGNING_KEY;
if (!pem) throw new Error("Configure THREAT_CATALOG_SIGNING_KEY using Secrets first.");
const root = (p: string) => fileURLToPath(new URL(`../${p}`, import.meta.url));
const key = publicSigningKey(pem);
if (process.argv.includes("--public")) {
  console.log(JSON.stringify({ id: key.id, spkiBase64: key.spkiBase64 }, null, 2));
  process.exit(0);
}
const trustPath = root("threat-catalog/trusted-keys.json");
const reviewed = payloadSchema.parse(JSON.parse(readFileSync(root("threat-catalog/reviewed.json"), "utf8")));
let trusted = validateTrustedKeys(JSON.parse(readFileSync(trustPath, "utf8")));
if (process.argv.includes("--initial")) {
  if (trusted.keys.length) throw new Error("Initial provisioning cannot replace existing trust anchors.");
  if (reviewed.version <= 2) throw new Error("Increment reviewed.version above legacy version 2 before provisioning.");
  trusted = { schemaVersion: 1, keys: [{ id: key.id, spkiBase64: key.spkiBase64,
    minimumVersion: reviewed.version, maximumVersion: 2147483647, status: "active" }] };
}
validateTrustedKeys(trusted);
const bootstrap = makeEnvelope(reviewed, pem, trusted);
const ts = `// Generated PUBLIC trust anchors; review together with the APK keys.\nexport const PRODUCTION_TRUSTED_KEYS = ${JSON.stringify(trusted, null, 2)};\n`;
const entries = trusted.keys.map(k =>
  `        "${k.id}" to Key("${k.spkiBase64}", ${k.minimumVersion}, ${k.maximumVersion}, ${k.status === "revoked"})`
).join(",\n");
const kotlin = `package com.maguardian.security.data

/** Generated PUBLIC trust anchors, fixed in the APK. Never load keys from the server. */
object ThreatCatalogKeys {
    data class Key(val spkiBase64: String, val minimumVersion: Int,
                   val maximumVersion: Int, val revoked: Boolean)
    val trusted: Map<String, Key> = mapOf(
${entries}
    )
}
`;
const outputs: [string, string][] = [
  [trustPath, JSON.stringify(trusted, null, 2) + "\n"],
  [root("shared/catalog-trust.ts"), ts],
  [root("app/src/main/java/com/maguardian/security/data/ThreatCatalogKeys.kt"), kotlin],
  [root("app/src/main/assets/threat-catalog-bootstrap.json"), bootstrap + "\n"],
];
if (process.argv.includes("--check")) {
  for (const [p, text] of outputs)
    if (!existsSync(p) || readFileSync(p, "utf8") !== text) throw new Error(`Public provisioning output differs: ${p}`);
  console.log("Public trust anchors and signed bootstrap match the reviewed policy.");
} else {
  for (const [p, text] of outputs) writeFileSync(p, text);
  console.log(`Provisioned public key ${key.id}. Review the public diff before building; no APK was published.`);
}
