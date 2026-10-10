import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Command contract only, not evidence of actual Android recovery.
test("oversized rehearsal upgrades only QA 41 to 43 and records metadata, not large content", () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-oversized-driver-"));
  try {
    fs.mkdirSync(path.join(temp, "tools"));
    for (const name of ["validate-android-catalog.sh", "catalog-rotation-fixtures.mjs"]) {
      fs.copyFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), path.join(temp, "tools", name));
    }
    fs.mkdirSync(path.join(temp, "sdk/platform-tools"), { recursive: true });
    fs.writeFileSync(path.join(temp, "sdk/platform-tools/adb"), `#!/usr/bin/env node
const fs = require("node:fs"), path = require("node:path");
const cmd = process.argv.slice(4).join(" "), root = process.env.QA_STATE;
const f = name => path.join(root, name);
fs.appendFileSync(f("calls"), cmd + "\\n");
if (cmd === "shell getprop ro.kernel.qemu") console.log(process.env.QA_QEMU || "1");
else if (cmd === "shell getprop sys.boot_completed") console.log("1");
else if (cmd.startsWith("shell pm path ")) {
  if (fs.existsSync(f("installed"))) console.log("package:/data/app/qa/base.apk");
} else if (cmd.startsWith("install -r ")) fs.writeFileSync(f("installed"), "true");
else if (cmd.startsWith("shell dumpsys package ")) console.log("versionCode=QA versionName=QA");
else if (cmd === "exec-out cat /data/app/qa/base.apk") process.stdout.write("contract-only QA app");
else if (cmd.endsWith("cat no_backup/oversized-evidence.json")) console.log('{"bytes":8388608}');
else if (cmd.startsWith("shell am instrument ")) {
  const phase = /rotationPhase (\\S+)/.exec(cmd)[1];
  if (phase.startsWith("recovery") && process.env.QA_NO_STATUS !== "true")
    console.log("OVERSIZED_OK phase=" + phase + " bytes=8388608");
  console.log("OK (1 test)");
}
`, { mode: 0o700 });
    fs.writeFileSync(path.join(temp, "gradlew"), `#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$QA_STATE/builds"
mkdir -p app/build/outputs/apk/androidTest/catalogValidation app/build/outputs/apk/catalogValidation
printf 'contract-only harmless fixture' > app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk
printf 'contract-only QA app' > app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
`);
    const out = path.join(temp, "evidence");
    const env = { PATH: process.env.PATH, ANDROID_HOME: path.join(temp, "sdk"),
      ANDROID_SERIAL: "emulator-5554", DISPOSABLE_ANDROID: "true",
      ANDROID_VALIDATION_OUTPUT: out, QA_STATE: temp };
    const run = overrides => execFileSync("bash", [path.join(temp, "tools/validate-android-catalog.sh"),
      "--rotation-oversized"], { env: { ...env, ...overrides }, encoding: "utf8", stdio: "pipe" });
    for (const overrides of [{ DISPOSABLE_ANDROID: "false" }, { ANDROID_SERIAL: "physical-device" },
      { QA_QEMU: "0" }]) assert.throws(() => run(overrides));
    fs.writeFileSync(path.join(temp, "calls"), "");
    assert.match(run({}), /bounded size rejection/);
    const calls = fs.readFileSync(path.join(temp, "calls"), "utf8").trim().split("\n");
    assert.ok(!calls.some(c => /pm clear|uninstall|reboot|install-create|install-abandon/.test(c)));
    assert.ok(!calls.some(c => /cat no_backup\/threat-catalog.json/.test(c)));
    assert.equal(calls.filter(c => c.startsWith("install -r ")).length, 2);
    const tests = calls.filter(c => c.startsWith("shell am instrument "));
    assert.deepEqual(tests.map(c => /rotationPhase (\S+)/.exec(c)[1]),
      ["old", "old-restart", "oversized-prepare", "recovery", "recovery-restart"]);
    assert.ok(tests.every(c => c.includes("-e rotationRoute oversized")));
    for (const command of tests.filter(c => /rotationPhase recovery/.test(c))) {
      // Fresh processes between verification stages.
      const index = calls.indexOf(command);
      assert.ok(calls.slice(0, index).some(c => c === "shell am force-stop com.maguardian.security.catalogvalidation"));
    }
    assert.doesNotMatch(fs.readFileSync(path.join(temp, "builds"), "utf8"), /Stage=transition/);
    assert.equal(fs.readFileSync(path.join(out, "rotation-route.txt"), "utf8"), "oversized\n");
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(out, "oversized-evidence.json"))), { bytes: 8388608 });
    assert.ok(fs.readdirSync(out).every(name => !/cache.*\.json|\.apk$|\.pem$|\.key$/.test(name)));
    const before = fs.readFileSync(path.join(temp, "calls"), "utf8");
    assert.throws(() => run({}), /fresh disposable package/);
    assert.doesNotMatch(fs.readFileSync(path.join(temp, "calls"), "utf8").slice(before.length),
      /am instrument|am force-stop|install -r/);
    fs.rmSync(path.join(temp, "installed"));
    // Ordinary JUnit success without measured recovery status is insufficient.
    assert.throws(() => run({ QA_NO_STATUS: "true" }));
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
