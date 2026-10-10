import { readFileSync, writeFileSync, renameSync, mkdirSync } from "node:fs";
import path from "node:path";
import { makeEnvelope, validateCatalog } from "../shared/threat-catalog";

// Offline, reviewed-input only. No scraper, paid API, APK download or auto-promotion.
const [input, output = "threat-catalog/catalog.json"] = process.argv.slice(2);
if (!input) throw new Error("Usage: npx tsx script/publish-threat-catalog.ts reviewed.json [catalog.json]");
const payload = JSON.parse(readFileSync(input, "utf8"));
const raw = makeEnvelope(payload);
let previous: ReturnType<typeof validateCatalog> | undefined;
try {
  previous = validateCatalog(readFileSync(output, "utf8"));
} catch (error: any) {
  // Invalid previous data is not silently overwritten.
  if (error.code !== "ENOENT") throw error;
}
const validated = validateCatalog(raw, previous && {
  version: previous.payload.version, sha256: previous.envelope.sha256,
});
if (previous && validated.payload.version <= previous.payload.version)
  throw new Error("Publication requires a strictly newer version");
mkdirSync(path.dirname(output), { recursive: true });
writeFileSync(`${output}.tmp`, raw + "\n", { flag: "wx" });
renameSync(`${output}.tmp`, output);
console.log(`Validated catalog v${validated.payload.version}: ${validated.payload.indicators.length} reviewed indicators.`);
