import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

test("QA fixtures isolate immutable policies, valid signatures and deliberate cache corruption", () => {
  // Run a copied tool, so this smoke check cannot disturb a real emulator session.
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-rotation-"));
  try {
    fs.mkdirSync(path.join(temp, "tools"));
    const tool = path.join(temp, "tools/catalog-rotation-fixtures.mjs");
    fs.copyFileSync(fileURLToPath(new URL("./catalog-rotation-fixtures.mjs", import.meta.url)), tool);
    const run = stage => execFileSync(process.execPath, [tool, stage], {
      env: { PATH: process.env.PATH }, stdio: "pipe"
    });
    run("new-session");
    const session = path.join(temp, "app/build/catalogRotationSession");
    const privateFiles = fs.readdirSync(session);
    assert.deepEqual(privateFiles.sort(), ["old.pem", "successor.pem"]);
    for (const file of privateFiles) assert.equal(fs.statSync(path.join(session, file)).mode & 0o777, 0o600);
    fs.writeFileSync(path.join(session, "fixture-sha256.txt"), "a".repeat(64));
    let originalIds;
    for (const stage of ["old", "transition", "recovery"]) {
      run(stage);
      const assets = path.join(temp, "app/build/generated/catalogRotationAssets");
      const policy = JSON.parse(fs.readFileSync(path.join(assets, "rotation-policy.json")));
      const ids = [policy.old, policy.successor];
      if (originalIds) assert.deepEqual(ids, originalIds);
      originalIds = ids;
      assert.notEqual(...ids);
      const source = fs.readFileSync(path.join(temp,
        "app/build/generated/catalogValidationKeys/com/maguardian/security/data/ValidationCatalogKeys.kt"), "utf8");
      assert.match(source, /val trusted: Map<String, Key> = mapOf\(/);
      assert.ok(source.includes(policy.old));
      assert.equal(source.includes(policy.successor), stage !== "old");
      assert.equal(source.includes("10, 13, true"), stage === "recovery");
      const bootstrap = JSON.parse(fs.readFileSync(path.join(assets, "threat-catalog-bootstrap.json")));
      assert.equal(bootstrap.keyId, stage === "recovery" ? policy.successor : policy.old);
      const directCache = JSON.parse(fs.readFileSync(path.join(assets, "rotation-direct-old-cache.json")));
      assert.equal(directCache.keyId, policy.old);
      assert.equal(JSON.parse(directCache.payload).version, 13);
      const truncated = fs.readFileSync(path.join(assets, "rotation-corrupt-truncated-cache.json"), "utf8");
      assert.throws(() => JSON.parse(truncated), SyntaxError);
      const tampered = JSON.parse(fs.readFileSync(path.join(assets, "rotation-corrupt-tampered-cache.json")));
      assert.equal(tampered.keyId, policy.old);
      assert.equal(tampered.signature, directCache.signature);
      assert.deepEqual(JSON.parse(tampered.payload).indicators, []);
      assert.equal(crypto.createHash("sha256").update(tampered.payload).digest("hex"), tampered.sha256);
      const oldPublicKey = crypto.createPublicKey(fs.readFileSync(path.join(session, "old.pem")));
      assert.equal(crypto.verify("sha256",
        Buffer.from(`maguardian/threat-catalog/v2\n${tampered.keyId}\n${tampered.sha256}\n${tampered.payload}`),
        oldPublicKey, Buffer.from(tampered.signature, "base64")), false);
      for (const file of fs.readdirSync(assets).filter(f => f.endsWith(".json") &&
          f !== "rotation-policy.json" && !f.startsWith("rotation-corrupt-"))) {
        const envelope = JSON.parse(fs.readFileSync(path.join(assets, file)));
        const name = envelope.keyId === policy.old ? "old" : "successor";
        const privateKey = crypto.createPrivateKey(fs.readFileSync(path.join(session, `${name}.pem`)));
        const publicKey = crypto.createPublicKey(privateKey);
        assert.equal(publicKey.asymmetricKeyDetails.modulusLength, 3072);
        const spki = publicKey.export({ type: "spki", format: "der" });
        assert.equal(crypto.createHash("sha256").update(spki).digest("hex"), envelope.keyId);
        assert.equal(crypto.createHash("sha256").update(envelope.payload).digest("hex"), envelope.sha256);
        assert.ok(crypto.verify("sha256",
          Buffer.from(`maguardian/threat-catalog/v2\n${envelope.keyId}\n${envelope.sha256}\n${envelope.payload}`),
          publicKey, Buffer.from(envelope.signature, "base64")));
        assert.equal(JSON.parse(envelope.payload).indicators[0].apkSha256[0], "a".repeat(64));
      }
      assert.ok(fs.readdirSync(assets).every(name => !/\.(pem|key)$/.test(name)));
    }
    assert.throws(() => run("production"));
    run("new-session");
    run("old");
    const replacement = JSON.parse(fs.readFileSync(path.join(temp,
      "app/build/generated/catalogRotationAssets/rotation-policy.json")));
    assert.notEqual(replacement.old, originalIds[0]);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
