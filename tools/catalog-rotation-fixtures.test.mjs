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
      const validCache = JSON.parse(fs.readFileSync(path.join(assets, "rotation-successor-newer-cache.json")));
      const recoveryBootstrap = JSON.parse(fs.readFileSync(path.join(assets, "rotation-recovery-bootstrap.json")));
      assert.equal(validCache.keyId, policy.successor);
      assert.equal(JSON.parse(validCache.payload).version, 17);
      assert.equal(JSON.parse(recoveryBootstrap.payload).version, 14);
      assert.ok(JSON.parse(validCache.payload).version > JSON.parse(recoveryBootstrap.payload).version);
      assert.notEqual(validCache.sha256, recoveryBootstrap.sha256);
      assert.ok(source.includes("12, 2147483647, false") || stage === "old");
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

test("upgrade driver selects the valid-cache route without clearing data and restarts each stage", () => {
  // Command-contract check only: fake ADB/builds do not prove Android behavior.
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-upgrade-driver-"));
  try {
    fs.mkdirSync(path.join(temp, "tools"));
    for (const name of ["validate-android-catalog.sh", "catalog-rotation-fixtures.mjs"]) {
      fs.copyFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), path.join(temp, "tools", name));
    }
    fs.mkdirSync(path.join(temp, "sdk/platform-tools"), { recursive: true });
    const calls = path.join(temp, "adb-calls.txt");
    fs.writeFileSync(path.join(temp, "sdk/platform-tools/adb"), `#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$QA_CALLS"
shift 2
case "$*" in
  "shell getprop ro.kernel.qemu"|"shell getprop sys.boot_completed") echo 1 ;;
  "shell am instrument "*) echo 'OK (1 test)' ;;
  "shell dumpsys package "*) echo 'versionCode=43 versionName=QA' ;;
  "install -r "*) touch "$QA_INSTALLED" ;;
  "shell pm path "*) if [[ -f "$QA_INSTALLED" ]]; then echo 'package:/data/app/qa/base.apk'; fi ;;
  "exec-out cat /data/app/qa/base.apk") printf 'harmless command-contract app' ;;
esac
`, { mode: 0o700 });
    fs.writeFileSync(path.join(temp, "gradlew"), `#!/usr/bin/env bash
set -eu
mkdir -p app/build/outputs/apk/androidTest/catalogValidation app/build/outputs/apk/catalogValidation
printf 'harmless command-contract fixture' > app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk
printf 'harmless command-contract app' > app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
`);
    const env = {
      PATH: process.env.PATH, ANDROID_HOME: path.join(temp, "sdk"),
      ANDROID_SERIAL: "emulator-5554", DISPOSABLE_ANDROID: "true",
      ANDROID_VALIDATION_OUTPUT: path.join(temp, "evidence"), QA_CALLS: calls,
      QA_INSTALLED: path.join(temp, "installed")
    };
    const output = execFileSync("bash", [path.join(temp, "tools/validate-android-catalog.sh"),
      "--rotation-valid-cache"], { env, encoding: "utf8" });
    const commands = fs.readFileSync(calls, "utf8").trim().split("\n");
    assert.equal(fs.readFileSync(path.join(temp, "evidence/rotation-route.txt"), "utf8"), "valid-cache\n");
    const tests = commands.filter(c => c.includes("shell am instrument"));
    assert.equal(tests.length, 6);
    for (const [index, phase] of ["old", "old-restart", "transition", "transition-restart",
      "recovery", "recovery-restart"].entries()) {
      assert.ok(tests[index].includes(`-e rotationPhase ${phase} -e rotationRoute valid-cache`));
      const commandIndex = commands.indexOf(tests[index]);
      if (phase.endsWith("-restart")) {
        assert.ok(commands[commandIndex - 1].includes("shell am force-stop com.maguardian.security.catalogvalidation"));
      }
      assert.ok(fs.existsSync(path.join(temp, `evidence/rotation-${phase}.log`)));
    }
    assert.equal(commands.filter(c => c.includes(" install -r ")).length, 3);
    assert.equal(commands.filter(c => c.includes(" install ") && !c.includes(" install -r ")).length, 1);
    assert.ok(!commands.some(c => /pm clear|uninstall/.test(c)));
    assert.match(output, /successor cache 17 retained over bootstrap 14/);
    assert.throws(() => execFileSync("bash", [path.join(temp, "tools/validate-android-catalog.sh"),
      "--rotation-valid-cache"], { env: { ...env, ANDROID_SERIAL: "physical-device" }, stdio: "pipe" }));
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
