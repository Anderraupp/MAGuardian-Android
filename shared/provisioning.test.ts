import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdtempSync, rmSync, cpSync, mkdirSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { privatePem } from "./test-signing";
import { validateCatalog, loadTrustedKeys, digest } from "./threat-catalog";

test("operator provisioning writes only public pins and a verified bootstrap; release checks need no secret", () => {
  const source = fileURLToPath(new URL("../", import.meta.url));
  const temp = mkdtempSync(path.join(tmpdir(), "maguardian-provision-"));
  for (const dir of ["shared", "tools", "threat-catalog"]) cpSync(path.join(source, dir), path.join(temp, dir), { recursive: true });
  writeFileSync(path.join(temp, "package.json"), '{"type":"module"}');
  mkdirSync(path.join(temp, "app/src/main/assets"), { recursive: true });
  mkdirSync(path.join(temp, "app/src/main/java/com/maguardian/security/data"), { recursive: true });
  const modules = path.dirname(path.dirname(createRequire(import.meta.url).resolve("tsx/package.json")));
  symlinkSync(modules, path.join(temp, "node_modules"), "dir");
  const reviewedPath = path.join(temp, "threat-catalog/reviewed.json");
  const reviewed = JSON.parse(readFileSync(reviewedPath, "utf8"));
  reviewed.version = 3;
  writeFileSync(reviewedPath, JSON.stringify(reviewed));
  // The operator's real pins/catalog may already be provisioned. Only this
  // disposable directory models a fresh installation and the legacy migration.
  writeFileSync(path.join(temp, "threat-catalog/trusted-keys.json"),
    JSON.stringify({ schemaVersion: 1, keys: [] }));
  writeFileSync(path.join(temp, "shared/catalog-trust.ts"),
    "export const PRODUCTION_TRUSTED_KEYS = { schemaVersion: 1, keys: [] };\n");
  const legacyPayload = JSON.stringify({ ...reviewed, version: 2 });
  writeFileSync(path.join(temp, "threat-catalog/catalog.json"),
    JSON.stringify({ format: 1, payload: legacyPayload, sha256: digest(legacyPayload) }));
  const publicEnv = { ...process.env }; delete publicEnv.THREAT_CATALOG_SIGNING_KEY;
  const run = (script: string, args: string[] = [], secret = false) =>
    execFileSync(process.execPath, ["--import", "tsx", path.join(temp, "tools", script), ...args],
      { cwd: temp, encoding: "utf8", stdio: "pipe",
        env: secret ? { ...publicEnv, THREAT_CATALOG_SIGNING_KEY: privatePem } : publicEnv });
  try {
    assert.throws(() => run("provision-catalog-trust.ts", ["--initial"]), /Secrets/);
    assert.match(run("provision-catalog-trust.ts", ["--initial"], true), /Provisioned public key/);
    assert.throws(() => run("provision-catalog-trust.ts", ["--initial"], true), /cannot replace/);
    assert.match(run("check-catalog-trust.ts"), /No private key accessed/);
    const trust = loadTrustedKeys(path.join(temp, "threat-catalog/trusted-keys.json"));
    const bootstrap = readFileSync(path.join(temp, "app/src/main/assets/threat-catalog-bootstrap.json"), "utf8");
    assert.equal(validateCatalog(bootstrap, undefined, Date.now(), trust).payload.version, 3);
    assert.equal(bootstrap.includes("PRIVATE KEY"), false);
    assert.throws(() => run("publish-threat-catalog.ts", [reviewedPath]), /Secrets/);
    assert.throws(() => run("publish-threat-catalog.ts", [reviewedPath], true)); // legacy cannot silently migrate
    assert.match(run("publish-threat-catalog.ts", [reviewedPath, "--migrate-unsigned"], true), /Validated catalog/);
    const pub = JSON.parse(run("provision-catalog-trust.ts", ["--public"], true));
    assert.equal(pub.id, trust.keys[0].id);
    const kotlin = path.join(temp, "app/src/main/java/com/maguardian/security/data/ThreatCatalogKeys.kt");
    writeFileSync(kotlin, readFileSync(kotlin, "utf8").replace("false)", "true)"));
    assert.throws(() => run("check-catalog-trust.ts"), /Android public pins differ/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
});
