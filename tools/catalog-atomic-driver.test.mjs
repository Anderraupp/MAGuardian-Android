import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

// Command contract only. Real Android persistence is proved by instrumentation,
// not by this fake ADB. This catches accidental reboot/upgrade/unsafe targeting.
for (const mode of ["pending", "committed"]) test(`${mode} file driver kills only QA and checks storage across fresh readers`, () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), "catalog-atomic-driver-"));
  try {
    fs.mkdirSync(path.join(temp, "tools"));
    for (const name of ["validate-android-catalog.sh", "catalog-rotation-fixtures.mjs"]) {
      fs.copyFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), path.join(temp, "tools", name));
    }
    fs.mkdirSync(path.join(temp, "sdk/platform-tools"), { recursive: true });
    fs.writeFileSync(path.join(temp, "sdk/platform-tools/adb"), `#!/usr/bin/env node
const fs = require("node:fs"), path = require("node:path"), crypto = require("node:crypto");
const a = process.argv.slice(4), cmd = a.join(" "), root = process.env.QA_STATE;
const f = name => path.join(root, name);
const exists = name => fs.existsSync(f(name));
const write = (name, data) => fs.writeFileSync(f(name), data);
fs.appendFileSync(f("calls"), cmd + "\\n");
if (cmd === "shell getprop ro.kernel.qemu") console.log(process.env.QA_QEMU || "1");
else if (cmd === "shell getprop sys.boot_completed") console.log("1");
else if (cmd === "shell getprop ro.build.version.sdk") console.log(process.env.QA_API || "36");
else if (cmd.startsWith("shell pm path ")) {
  if (exists("installed")) console.log("package:/data/app/qa/base.apk");
} else if (cmd.startsWith("install -r ")) write("installed", "true");
else if (cmd.startsWith("exec-out cat /data/app/")) process.stdout.write("contract-only QA app");
else if (cmd.startsWith("shell dumpsys package ")) console.log("versionCode=41 versionName=QA");
else if (cmd.startsWith("shell am force-stop ")) {
  if (exists("writer")) { fs.unlinkSync(f("writer")); write("killed", "true"); }
} else if (cmd.startsWith("shell pidof ")) {
  if (exists("writer")) console.log("5001");
} else if (cmd.startsWith("shell am instrument ")) {
  const phase = a[a.indexOf("rotationPhase") + 1];
  if (phase === "old") { write("base", '{"valid":"signed fixture"}'); write("prefs", '<keep fixture="true"/>'); }
  if (phase === "atomic-write" || phase === "committed-write") {
    let marker = "ATOMIC_PENDING";
    if (phase === "committed-write") {
      const sha = x => crypto.createHash("sha256").update(x).digest("hex");
      const previousSha256 = sha(fs.readFileSync(f("base")));
      const snapshotHash = "f".repeat(64);
      const committed = JSON.stringify({sha256:snapshotHash, payload:"new QA fixture"});
      write("base", committed);
      write("ready", JSON.stringify({pid:5001, previousSha256,
        committedSha256:sha(committed), snapshotHash}));
      marker = "ATOMIC_COMMITTED";
    } else {
      write("pending", '{"format":2,"payload":"unfinished');
      write("ready", '{"pid":5001}');
    }
    write("writer", "true");
    console.log("INSTRUMENTATION_STATUS: stream=" + marker + "_READY pid=5001");
    const timer = setInterval(() => {
      if (exists("killed")) {
        clearInterval(timer);
        console.log(process.env.QA_WRITER_OK === "true" ? "OK (1 test)" : "shortMsg=Process crashed.");
        process.exit(process.env.QA_WRITER_OK === "true" ? 0 : 1);
      }
    }, 25);
  } else {
    if (phase === "atomic-verify") {
      if (!exists("pending") || !exists("killed")) process.exit(1);
      fs.unlinkSync(f("pending"));
    }
    if (phase === "atomic-restart" && exists("pending")) process.exit(1);
    if (phase.startsWith("committed-")) {
      if (!exists("killed") || exists("pending")) process.exit(1);
      if (process.env.QA_NO_STATUS !== "true") console.log("ATOMIC_COMMITTED_OK phase=" + phase);
    }
    if (phase.startsWith("atomic-")) console.log("ATOMIC_PENDING_OK phase=" + phase);
    console.log("OK (1 test)");
  }
} else if (cmd.startsWith("exec-out run-as ")) {
  const name = /atomic-(pending|committed)-ready.json$/.test(cmd) ? "ready" :
    cmd.endsWith("threat-catalog.json.new") ? "pending" :
    cmd.endsWith("threat-catalog.json") ? "base" : "prefs";
  process.stdout.write(fs.readFileSync(f(name)));
} else if (cmd.startsWith("shell run-as ") && (cmd.includes(" test -f ") || cmd.includes(" test -e "))) {
  const name = /atomic-(pending|committed)-ready.json$/.test(cmd) ? "ready" :
    cmd.endsWith("threat-catalog.json.new") ? "pending" : "absent";
  process.exit(exists(name) ? 0 : 1);
}
`, { mode: 0o700 });
    fs.writeFileSync(path.join(temp, "gradlew"), `#!/usr/bin/env bash
set -eu
mkdir -p app/build/outputs/apk/androidTest/catalogValidation app/build/outputs/apk/catalogValidation
printf 'contract-only harmless fixture' > app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk
printf 'contract-only QA app' > app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
`);
    const out = path.join(temp, "evidence");
    const env = { PATH: process.env.PATH, ANDROID_HOME: path.join(temp, "sdk"),
      ANDROID_SERIAL: "emulator-5554", DISPOSABLE_ANDROID: "true",
      ANDROID_VALIDATION_OUTPUT: out, QA_STATE: temp };
    const run = overrides => execFileSync("bash", [path.join(temp, "tools/validate-android-catalog.sh"),
      "--atomic-" + mode], { env: { ...env, ...overrides }, encoding: "utf8", stdio: "pipe", timeout: 30_000 });
    for (const overrides of [{ DISPOSABLE_ANDROID: "false" }, { ANDROID_SERIAL: "physical-device" },
      { QA_QEMU: "0" }, ...(mode === "pending" ? [{ QA_API: "29" }] : [])]) {
      assert.throws(() => run(overrides));
    }
    fs.writeFileSync(path.join(temp, "calls"), "");
    assert.match(run({}), /QA writer killed/);
    const calls = fs.readFileSync(path.join(temp, "calls"), "utf8").trim().split("\n");
    assert.ok(!calls.some(c => /reboot|pm clear|uninstall|install-create|install-abandon/.test(c)));
    assert.equal(calls.filter(c => c.startsWith("install -r ")).length, 1);
    assert.ok(calls.filter(c => c.startsWith("shell am force-stop "))
      .every(c => c === "shell am force-stop com.maguardian.security.catalogvalidation"));
    const phases = calls.filter(c => c.startsWith("shell am instrument "))
      .map(c => /rotationPhase (\S+)/.exec(c)[1]);
    const prefix = mode === "pending" ? "atomic" : "committed";
    assert.deepEqual(phases, ["old", "old-restart", `${prefix}-write`, `${prefix}-verify`, `${prefix}-restart`]);
    for (const phase of ["writer-live", "killed", `${prefix}-verify`, `${prefix}-restart`]) {
      assert.deepEqual(fs.readFileSync(path.join(out, `base-${phase}.json`)),
        fs.readFileSync(path.join(out, mode === "pending" ? "base-before-write.json" : "base-writer-live.json")));
      assert.deepEqual(fs.readFileSync(path.join(out, `prefs-${phase}.xml`)),
        fs.readFileSync(path.join(out, "prefs-before-write.xml")));
    }
    if (mode === "pending") {
      assert.deepEqual(fs.readFileSync(path.join(out, "pending-live.new")),
        fs.readFileSync(path.join(out, "pending-killed.new")));
    } else {
      assert.notDeepEqual(fs.readFileSync(path.join(out, "base-before-write.json")),
        fs.readFileSync(path.join(out, "base-writer-live.json")));
      assert.ok(!fs.existsSync(path.join(out, "pending-live.new")));
    }
    assert.ok(fs.existsSync(path.join(out, "atomic-kill.txt")));
    const beforeRefusal = fs.readFileSync(path.join(temp, "calls"), "utf8");
    assert.throws(() => run({}), /fresh disposable package/);
    const refusalCalls = fs.readFileSync(path.join(temp, "calls"), "utf8").slice(beforeRefusal.length);
    assert.doesNotMatch(refusalCalls, /am force-stop|am instrument|install -r|pm clear|reboot/);
    // A writer that returns ordinary JUnit success must never count as a kill.
    for (const name of ["installed", "ready", "killed", "pending"]) {
      fs.rmSync(path.join(temp, name), { force: true });
    }
    assert.throws(() => run({ QA_WRITER_OK: "true" }), /Writer returned success instead/);
    if (mode === "committed") {
      for (const name of ["installed", "ready", "killed"]) fs.rmSync(path.join(temp, name), { force: true });
      assert.throws(() => run({ QA_NO_STATUS: "true" }));
    }
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
