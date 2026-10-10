import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Shell routing contract only. Android evidence comes from the emulator workflow.
test("concurrent driver isolates QA and requires race and cold-reader evidence", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-concurrent-driver-"));
  try {
    fs.mkdirSync(path.join(root, "tools"));
    for (const name of ["validate-android-catalog.sh", "catalog-rotation-fixtures.mjs"])
      fs.copyFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), path.join(root, "tools", name));
    fs.mkdirSync(path.join(root, "sdk/platform-tools"), { recursive: true });
    fs.writeFileSync(path.join(root, "sdk/platform-tools/adb"), `#!/usr/bin/env node
const fs=require("node:fs"), path=require("node:path");
const cmd=process.argv.slice(4).join(" "), f=n=>path.join(process.env.QA_STATE,n);
fs.appendFileSync(f("calls"),cmd+"\\n");
if(cmd==="shell getprop ro.kernel.qemu") console.log(process.env.QA_QEMU || "1");
else if(cmd==="shell getprop sys.boot_completed") console.log("1");
else if(cmd.startsWith("shell pm path ")) {
  if(fs.existsSync(f("installed"))) console.log("package:/data/app/qa/base.apk");
} else if(cmd.startsWith("install -r ")) fs.writeFileSync(f("installed"),"true");
else if(cmd.startsWith("shell dumpsys package ")) console.log("versionCode=41 versionName=QA");
else if(cmd==="exec-out cat /data/app/qa/base.apk") process.stdout.write("QA APK");
else if(cmd.endsWith("cat no_backup/threat-catalog.json"))
  process.stdout.write(fs.existsSync(f("raced")) ? "signed catalog 13" : "signed catalog 11");
else if(cmd.endsWith("cat shared_prefs/maguardian_prefs.xml"))
  process.stdout.write(process.env.QA_BAD_PREFS==="true" && fs.existsSync(f("raced")) ? "changed" : "Manter");
else if(cmd.endsWith("cat no_backup/concurrent-evidence.json")) console.log('{"pid":42}');
else if(cmd.startsWith("shell am instrument ")) {
  const phase=/rotationPhase (\\S+)/.exec(cmd)?.[1];
  if(phase==="concurrent-write") {
    fs.writeFileSync(f("raced"),"true");
    if(process.env.QA_NO_RESULTS!=="true") console.log("CONCURRENT_RESULTS lower=accepted higher=accepted");
  }
  if(phase?.startsWith("concurrent-") && process.env.QA_NO_STATUS!=="true")
    console.log("CONCURRENT_OK phase="+phase+" version=13");
  if(!phase) for(const first of [12,13,"null"])
    console.log("CONCURRENT_ORDER_OK first="+first+" version=13");
  console.log(phase ? "OK (1 test)" : "OK (3 tests)");
}
`, { mode: 0o700 });
    fs.writeFileSync(path.join(root, "gradlew"), `#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$QA_STATE/builds"
mkdir -p app/build/outputs/apk/androidTest/catalogValidation app/build/outputs/apk/catalogValidation
printf 'harmless fixture' > app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk
printf 'QA APK' > app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
`);
    const env = { PATH: process.env.PATH, QA_STATE: root, DISPOSABLE_ANDROID: "true",
      ANDROID_SERIAL: "emulator-5554", ANDROID_HOME: path.join(root, "sdk"),
      ANDROID_VALIDATION_OUTPUT: path.join(root, "evidence") };
    const run = overrides => execFileSync("bash", ["tools/validate-android-catalog.sh", "--concurrent-install"],
      { cwd: root, env: { ...env, ...overrides }, encoding: "utf8", stdio: "pipe" });
    for (const flags of [{ DISPOSABLE_ANDROID: "false" }, { ANDROID_SERIAL: "phone" }, { QA_QEMU: "0" }])
      assert.throws(() => run(flags));
    fs.writeFileSync(path.join(root, "calls"), "");
    assert.match(run({}), /Concurrent install passed/);
    const calls = fs.readFileSync(path.join(root, "calls"), "utf8").trim().split("\n");
    assert.ok(!calls.some(c => /pm clear|uninstall|reboot|install-create/.test(c)));
    assert.equal(calls.filter(c => c.startsWith("install -r ")).length, 1);
    const tests = calls.filter(c => c.startsWith("shell am instrument "));
    assert.deepEqual(tests.map(c => /rotationPhase (\S+)/.exec(c)?.[1] ?? "orders"),
      ["old", "old-restart", "concurrent-write", "concurrent-restart", "orders"]);
    for (const command of tests.filter(c => /rotationPhase concurrent-/.test(c)))
      assert.equal(calls[calls.indexOf(command) - 1],
        "shell am force-stop com.maguardian.security.catalogvalidation");
    assert.match(tests.at(-1), /#concurrentLowerFirstKeepsTheHigherSignedCatalog/);
    assert.match(tests.at(-1), /#concurrentHigherFirstRejectsTheOlderSignedCatalog/);
    assert.match(tests.at(-1), /#simultaneousInstallKeepsTheHigherSignedCatalog/);
    assert.ok(fs.readFileSync(path.join(root, "builds"), "utf8").split("\n")
      .filter(Boolean).every(c => c.includes("Stage=old")));
    assert.throws(() => run({}), /fresh disposable package/);
    for (const flags of [{ QA_NO_STATUS: "true" }, { QA_NO_RESULTS: "true" }, { QA_BAD_PREFS: "true" }]) {
      fs.rmSync(path.join(root, "installed"));
      fs.rmSync(path.join(root, "raced"), { force: true });
      assert.throws(() => run(flags));
    }
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
