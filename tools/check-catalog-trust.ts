import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { loadTrustedKeys, validateCatalog, payloadSchema, digest } from "../shared/threat-catalog";

// Entirely PUBLIC release check; no signing secret is needed by builds/CI.
const root = (p: string) => fileURLToPath(new URL(`../${p}`, import.meta.url));
const trusted = loadTrustedKeys(root("threat-catalog/trusted-keys.json"));
if (!trusted.keys.some(k => k.status === "active")) throw new Error("Production signing key not provisioned.");
if (JSON.stringify(trusted) !== JSON.stringify(loadTrustedKeys()))
  throw new Error("Generated server trust policy differs from reviewed public policy.");
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
if (readFileSync(root("app/src/main/java/com/maguardian/security/data/ThreatCatalogKeys.kt"), "utf8") !== kotlin)
  throw new Error("Generated Android public pins differ from reviewed policy.");
const bootstrap = validateCatalog(readFileSync(root("app/src/main/assets/threat-catalog-bootstrap.json"), "utf8"),
  undefined, Date.now(), trusted);
const reviewed = payloadSchema.parse(JSON.parse(readFileSync(root("threat-catalog/reviewed.json"), "utf8")));
if (bootstrap.envelope.sha256 !== digest(JSON.stringify(reviewed)))
  throw new Error("Offline bootstrap does not match reviewed baseline.");
console.log("Public Android/server pins and signed offline bootstrap verified. No private key accessed.");
