import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { validateCatalog, trusted, privatePem } from "./test-signing";

test("standalone Android publisher can generate newer metadata without touching app sources", () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const script = path.join(root, "tools/publish-threat-catalog.ts");
  const fixture = JSON.parse(readFileSync(path.join(root, "threat-catalog/reviewed.json"), "utf8"));
  const dir = mkdtempSync(path.join(tmpdir(), "maguardian-publish-"));
  const input = path.join(dir, "reviewed.json");
  const output = path.join(dir, "catalog.json");
  const trustPath = path.join(dir, "trusted.json");
  writeFileSync(trustPath, JSON.stringify(trusted));
  const buildPath = path.join(root, "app/build.gradle");
  const before = readFileSync(buildPath, "utf8");
  function run() {
    return execFileSync(process.execPath, ["--import", "tsx", script, input, output, `--trusted-keys=${trustPath}`],
      { cwd: root, encoding: "utf8", stdio: "pipe",
        env: { ...process.env, THREAT_CATALOG_SIGNING_KEY: privatePem } });
  }
  try {
    writeFileSync(input, JSON.stringify(fixture));
    assert.match(run(), /Validated catalog/);
    const old = readFileSync(output, "utf8");
    assert.equal(validateCatalog(old).payload.version, fixture.version);
    assert.throws(run, /strictly newer version/);
    assert.equal(readFileSync(output, "utf8"), old);
    fixture.version++;
    writeFileSync(input, JSON.stringify(fixture));
    assert.match(run(), /Validated catalog/);
    assert.equal(validateCatalog(readFileSync(output, "utf8")).payload.version, fixture.version);
    const good = readFileSync(output, "utf8");
    fixture.version++;
    fixture.indicators[0].status = "candidate";
    writeFileSync(input, JSON.stringify(fixture));
    assert.throws(run);
    assert.equal(readFileSync(output, "utf8"), good);
    assert.equal(readFileSync(buildPath, "utf8"), before, "app version and APK build configuration unchanged");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
