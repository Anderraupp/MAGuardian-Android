import { readFileSync, writeFileSync, renameSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { makeEnvelope, validateCatalog, loadTrustedKeys, payloadSchema, digest } from "../shared/threat-catalog";

// Offline, reviewed-input only. No scraper, paid API, APK download or auto-promotion.
const args = process.argv.slice(2);
const positional = args.filter(s => !s.startsWith("--"));
const [input, output = fileURLToPath(new URL("../threat-catalog/catalog.json", import.meta.url))] = positional;
if (!input) throw new Error("Usage: npx tsx tools/publish-threat-catalog.ts reviewed.json [catalog.json]");
const pem = process.env.THREAT_CATALOG_SIGNING_KEY;
if (!pem) throw new Error("THREAT_CATALOG_SIGNING_KEY must be supplied through Secrets; never use Git or CLI arguments.");
const trustOption = args.find(s => s.startsWith("--trusted-keys="));
const trusted = loadTrustedKeys(trustOption ? trustOption.slice("--trusted-keys=".length)
  : new URL("../threat-catalog/trusted-keys.json", import.meta.url));
const payload = JSON.parse(readFileSync(input, "utf8"));
const raw = makeEnvelope(payload, pem, trusted);
let previous: { version: number; sha256: string } | undefined;
try {
  const oldRaw = readFileSync(output, "utf8");
  const old = JSON.parse(oldRaw);
  if (old.format === 1 && args.includes("--migrate-unsigned")) {
    // Explicit one-time operator migration only; never an Android acceptance path.
    if (Object.keys(old).sort().join() !== "format,payload,sha256" ||
      typeof old.payload !== "string" || digest(old.payload) !== old.sha256)
      throw new Error("Invalid legacy envelope");
    previous = { version: payloadSchema.parse(JSON.parse(old.payload)).version, sha256: old.sha256 };
  } else {
    const checked = validateCatalog(oldRaw, undefined, Date.now(), trusted);
    previous = { version: checked.payload.version, sha256: checked.envelope.sha256 };
  }
} catch (error: any) {
  // Invalid previous data is not silently overwritten.
  if (error.code !== "ENOENT") throw error;
}
const validated = validateCatalog(raw, previous, Date.now(), trusted);
if (previous && validated.payload.version <= previous.version)
  throw new Error("Publication requires a strictly newer version");
mkdirSync(path.dirname(output), { recursive: true });
writeFileSync(`${output}.tmp`, raw + "\n", { flag: "wx" });
renameSync(`${output}.tmp`, output);
console.log(`Validated catalog v${validated.payload.version}: ${validated.payload.indicators.length} reviewed indicators.`);
