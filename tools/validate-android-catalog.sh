#!/usr/bin/env bash
set -euo pipefail

# Run only on a fresh emulator owned by this validation session.
# This script never installs, clears or uninstalls the normal user's package.
[[ "${DISPOSABLE_ANDROID:-}" == true ]] || {
  echo "Set DISPOSABLE_ANDROID=true only for a fresh disposable emulator." >&2; exit 1;
}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
[[ "$SERIAL" == emulator-* ]] || { echo "Physical devices are forbidden." >&2; exit 1; }
adb_cmd() { "$ADB" -s "$SERIAL" "$@"; }
[[ "$(adb_cmd shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo "Target is not an Android emulator." >&2; exit 1;
}
PKG=com.maguardian.security.catalogvalidation
RUNNER="com.catalogvalidation.fixture/com.maguardian.security.data.CatalogTestRunner"
OUT="${ANDROID_VALIDATION_OUTPUT:-$ROOT/../generated-artifacts/android-validation}"
mkdir -p "$OUT"
run_tests() {
  local name="$1"; shift
  adb_cmd shell am instrument -w -r -e disposable true "$@" "$RUNNER" | tee "$OUT/$name.log"
  grep -Eq 'OK \([0-9]+ tests?\)' "$OUT/$name.log"
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$OUT/$name.log"
}
wait_boot() {
  adb_cmd wait-for-device
  for _ in $(seq 1 450); do
    [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && return 0
    sleep 2
  done
  echo "Emulator did not boot within 15 minutes." >&2; return 1
}
restore_network() {
  adb_cmd shell svc wifi enable >/dev/null || true
  adb_cmd shell svc data enable >/dev/null || true
}
collect_evidence() {
  if [[ -n "${ATOMIC_WRITER_PID:-}" ]]; then
    # The only killed Android process is our fixed, guarded disposable package.
    adb_cmd shell am force-stop "$PKG" || true
    kill "$ATOMIC_WRITER_PID" 2>/dev/null || true
    wait "$ATOMIC_WRITER_PID" 2>/dev/null || true
  fi
  # Only a session created by this disposable rehearsal can be abandoned.
  if [[ -n "${INSTALL_SESSION:-}" ]]; then
    adb_cmd shell pm install-abandon "$INSTALL_SESSION" > "$OUT/session-cleanup.txt" 2>&1 || true
  fi
  if [[ -n "${STAGED_APK:-}" ]]; then
    adb_cmd shell rm -f "$STAGED_APK" || true
  fi
  adb_cmd logcat -d -s BootReceiver:I ThreatCatalogJobService:I AndroidRuntime:E > "$OUT/android-logcat.txt" || true
  adb_cmd shell dumpsys jobscheduler > "$OUT/jobs-final.txt" || true
  if adb_cmd shell run-as "$PKG" test -f cache/catalog-ui-failure.png >/dev/null 2>&1; then
    adb_cmd exec-out run-as "$PKG" cat cache/catalog-ui-failure.png > "$OUT/catalog-ui-failure.png" 2>/dev/null || rm -f "$OUT/catalog-ui-failure.png"
  else
    rm -f "$OUT/catalog-ui-failure.png"
  fi
  restore_network
  if [[ -n "${SERVER_PID:-}" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap collect_evidence EXIT
wait_boot
adb_cmd shell settings put global window_animation_scale 0
adb_cmd shell settings put global transition_animation_scale 0
adb_cmd shell settings put global animator_duration_scale 0
cd "$ROOT"
if [[ "${1:-}" == "--rotation" || "${1:-}" == "--rotation-direct" || "${1:-}" == "--rotation-interrupted" ||
      "${1:-}" == "--rotation-corrupt-truncated" || "${1:-}" == "--rotation-corrupt-tampered" ||
      "${1:-}" == "--atomic-pending" || "${1:-}" == "--atomic-committed" || "${1:-}" == "--rotation-oversized" ]]; then
  route=three-step
  stages=(old transition recovery)
  if [[ "$1" == "--rotation-direct" || "$1" == "--rotation-interrupted" ]]; then
    route=direct
    stages=(old recovery)
  elif [[ "$1" == "--rotation-oversized" ]]; then
    route=oversized
    stages=(old recovery)
  elif [[ "$1" == "--atomic-pending" ]]; then
    route=atomic-pending
    stages=(old)
    api="$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    [[ "$api" =~ ^[0-9]+$ && "$api" -ge 30 ]] || {
      echo "AtomicFile .new rehearsal requires Android API 30+." >&2; exit 1;
    }
  elif [[ "$1" == "--atomic-committed" ]]; then
    route=atomic-committed
    stages=(old)
  fi
  [[ "$1" != "--rotation-interrupted" ]] || route=interrupted
  if [[ "$1" == --rotation-corrupt-* ]]; then
    route="${1#--rotation-}"
    stages=(old recovery)
  fi
  record_package() {
    local phase="$1" installed_apk
    adb_cmd shell dumpsys package "$PKG" | grep -E 'versionCode=|versionName=' \
      > "$OUT/package-$phase.txt"
    installed_apk="$(adb_cmd shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
    [[ "$installed_apk" == /data/app/*/base.apk ]] || {
      echo "Unexpected disposable APK path." >&2; return 1;
    }
    adb_cmd exec-out cat "$installed_apk" | sha256sum | cut -d ' ' -f 1 \
      > "$OUT/installed-$phase.sha256"
  }
  # No clear/uninstall between stages: package-manager upgrade must retain data.
  [[ -z "$(adb_cmd shell pm path "$PKG" | tr -d '\r')" ]] || {
    echo "Rotation requires a fresh disposable package; existing installation refused." >&2; exit 1;
  }
  node tools/catalog-rotation-fixtures.mjs new-session
  bash gradlew --no-daemon :app:assembleDebug :app:assembleCatalogValidation \
    :app:assembleCatalogValidationAndroidTest -PcatalogRotationStage=old | tee "$OUT/build-old-fixture.log"
  FIXTURE=app/build/catalogRotationSession/fixture.apk
  cp app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk "$FIXTURE"
  sha256sum "$FIXTURE" | cut -d ' ' -f 1 > app/build/catalogRotationSession/fixture-sha256.txt
  adb_cmd install "$FIXTURE"
  # Catalogs now refer to the exact, unchanged harmless instrumentation APK.
  # All downloads are local signed fixtures: the entire upgrade rehearsal is offline.
  adb_cmd shell svc wifi disable
  adb_cmd shell svc data disable
  : > "$OUT/apk-checksums.txt"
  printf '%s\n' "$route" > "$OUT/rotation-route.txt"
  for stage in "${stages[@]}"; do
    bash gradlew --no-daemon :app:assembleCatalogValidation \
      "-PcatalogRotationStage=$stage" | tee "$OUT/build-$stage.log"
    apk=app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
    sha256sum "$apk" | sed "s|$apk|$stage QA APK|" >> "$OUT/apk-checksums.txt"
    adb_cmd shell am force-stop "$PKG"
    if [[ "$route" == oversized && "$stage" == recovery ]]; then
      run_tests oversized-prepare -e class com.maguardian.security.data.ThreatCatalogRotationTest \
        -e rotationPhase oversized-prepare -e rotationRoute "$route"
      record_package oversized-prepare
      cmp "$OUT/installed-old.sha256" "$OUT/installed-oversized-prepare.sha256"
      adb_cmd exec-out run-as "$PKG" cat no_backup/oversized-evidence.json > "$OUT/oversized-evidence.json"
      # Metadata only: never pull the large synthetic cache into evidence.
      adb_cmd shell am force-stop "$PKG"
    fi
    if [[ "$route" == corrupt-* && "$stage" == recovery ]]; then
      run_tests corrupt-prepare -e class com.maguardian.security.data.ThreatCatalogRotationTest \
        -e rotationPhase corrupt-prepare -e rotationRoute "$route"
      record_package corrupt-prepare
      adb_cmd exec-out run-as "$PKG" cat no_backup/threat-catalog.json \
        > "$OUT/cache-corrupt-prepare.json"
      sha256sum "$OUT/cache-corrupt-prepare.json" > "$OUT/cache-corrupt-prepare.sha256"
      cmp "$OUT/installed-old.sha256" "$OUT/installed-corrupt-prepare.sha256"
      adb_cmd shell am force-stop "$PKG"
    fi
    if [[ "$route" == interrupted && "$stage" == recovery ]]; then
      record_package before-session
      # Write the complete recovery APK into PackageInstaller, but NEVER commit
      # this first session. The old APK must remain runnable even while staged.
      size="$(wc -c < "$apk" | tr -d '[:space:]')"
      adb_cmd shell pm install-create -r -S "$size" | tee "$OUT/session-create.txt"
      created="$(tr -d '\r' < "$OUT/session-create.txt")"
      [[ "$created" =~ ^Success:\ created\ install\ session\ \[([0-9]+)\]$ ]] || {
        echo "PackageInstaller did not create the expected session." >&2; exit 1;
      }
      INSTALL_SESSION="${BASH_REMATCH[1]}"
      STAGED_APK="/data/local/tmp/maguardian-recovery-$INSTALL_SESSION.apk"
      adb_cmd push "$apk" "$STAGED_APK" > "$OUT/session-push.txt"
      adb_cmd shell pm install-write -S "$size" "$INSTALL_SESSION" base.apk "$STAGED_APK" \
        | tee "$OUT/session-write.txt"
      grep -q '^Success' "$OUT/session-write.txt"
      run_tests interrupted-staged -e class com.maguardian.security.data.ThreatCatalogRotationTest \
        -e rotationPhase interrupted-staged -e rotationRoute "$route"
      record_package interrupted-staged
      cmp "$OUT/installed-before-session.sha256" "$OUT/installed-interrupted-staged.sha256"
      adb_cmd shell pm install-abandon "$INSTALL_SESSION" | tee "$OUT/session-abandon.txt"
      grep -q '^Success' "$OUT/session-abandon.txt"
      INSTALL_SESSION=
      adb_cmd shell rm -f "$STAGED_APK"
      STAGED_APK=
      adb_cmd shell am force-stop "$PKG"
      run_tests interrupted-abandoned -e class com.maguardian.security.data.ThreatCatalogRotationTest \
        -e rotationPhase interrupted-abandoned -e rotationRoute "$route"
      record_package interrupted-abandoned
      cmp "$OUT/installed-before-session.sha256" "$OUT/installed-interrupted-abandoned.sha256"
      # Now perform the actual upgrade, with neither uninstall nor pm clear.
      adb_cmd shell am force-stop "$PKG"
    fi
    adb_cmd install -r "$apk"
    if [[ "$route" == oversized ]]; then
      # MY_PACKAGE_REPLACED can launch QA receivers/services before instrumentation.
      # Stop that real process; the measured cold reader must not reuse its snapshot.
      adb_cmd shell am force-stop "$PKG"
    fi
    run_tests "rotation-$stage" -e class com.maguardian.security.data.ThreatCatalogRotationTest \
      -e rotationPhase "$stage" -e rotationRoute "$route"
    adb_cmd shell am force-stop "$PKG"
    run_tests "rotation-$stage-restart" -e class com.maguardian.security.data.ThreatCatalogRotationTest \
      -e rotationPhase "$stage-restart" -e rotationRoute "$route"
    record_package "$stage"
    if [[ "$route" == interrupted || "$route" == corrupt-* || "$route" == atomic-pending || "$route" == atomic-committed || "$route" == oversized ]]; then
      sha256sum "$apk" | cut -d ' ' -f 1 > "$OUT/expected-$stage.sha256"
      cmp "$OUT/expected-$stage.sha256" "$OUT/installed-$stage.sha256"
    fi
  done
  if [[ "$route" == atomic-pending || "$route" == atomic-committed ]]; then
    write_phase=atomic-write
    marker=ATOMIC_PENDING
    evidence_file=atomic-pending-ready.json
    verify_phases=(atomic-verify atomic-restart)
    if [[ "$route" == atomic-committed ]]; then
      write_phase=committed-write
      marker=ATOMIC_COMMITTED
      evidence_file=atomic-committed-ready.json
      verify_phases=(committed-verify committed-restart)
    fi
    capture_atomic() {
      local phase="$1"
      adb_cmd exec-out run-as "$PKG" cat no_backup/threat-catalog.json > "$OUT/base-$phase.json"
      adb_cmd exec-out run-as "$PKG" cat shared_prefs/maguardian_prefs.xml > "$OUT/prefs-$phase.xml"
      sha256sum "$OUT/base-$phase.json" "$OUT/prefs-$phase.xml" > "$OUT/storage-$phase.sha256"
      adb_cmd shell run-as "$PKG" ls -l no_backup > "$OUT/files-$phase.txt"
    }
    capture_atomic before-write
    # Not run_tests: this invocation MUST be killed, not return JUnit success.
    adb_cmd shell am instrument -w -r -e disposable true \
      -e class com.maguardian.security.data.ThreatCatalogRotationTest \
      -e rotationPhase "$write_phase" -e rotationRoute "$route" "$RUNNER" \
      > "$OUT/atomic-write.log" 2>&1 &
    ATOMIC_WRITER_PID=$!
    ready=false
    for _ in $(seq 1 60); do
      kill -0 "$ATOMIC_WRITER_PID" 2>/dev/null || {
        echo "Writer exited before the $route checkpoint." >&2; exit 1;
      }
      if grep -q "${marker}_READY pid=" "$OUT/atomic-write.log" &&
        adb_cmd shell run-as "$PKG" test -f "no_backup/$evidence_file"; then
        ready=true
        break
      fi
      sleep 1
    done
    [[ "$ready" == true ]] || { echo "Timed out waiting for $route write." >&2; exit 1; }
    adb_cmd exec-out run-as "$PKG" cat "no_backup/$evidence_file" > "$OUT/$evidence_file"
    writer_android_pid="$(node -e 'const e=require(process.argv[1]); if(!Number.isInteger(e.pid)||e.pid<=0)process.exit(1); console.log(e.pid)' "$OUT/$evidence_file")"
    [[ "$(adb_cmd shell pidof "$PKG" | tr -d '\r')" == "$writer_android_pid" ]] || {
      echo "Writer PID does not belong to the QA package." >&2; exit 1;
    }
    capture_atomic writer-live
    cmp "$OUT/prefs-before-write.xml" "$OUT/prefs-writer-live.xml"
    if [[ "$route" == atomic-pending ]]; then
      adb_cmd exec-out run-as "$PKG" cat no_backup/threat-catalog.json.new > "$OUT/pending-live.new"
      cmp "$OUT/base-before-write.json" "$OUT/base-writer-live.json"
    else
      # Prove actual disk replacement, not just a marker or a successful sync.
      node -e '
        const fs=require("fs"), crypto=require("crypto");
        const [before,after,evidence]=process.argv.slice(1);
        const a=fs.readFileSync(before), b=fs.readFileSync(after), e=JSON.parse(fs.readFileSync(evidence));
        const sha=x=>crypto.createHash("sha256").update(x).digest("hex");
        if(a.equals(b)||sha(a)!==e.previousSha256||sha(b)!==e.committedSha256||
          JSON.parse(b).sha256!==e.snapshotHash)process.exit(1);
      ' "$OUT/base-before-write.json" "$OUT/base-writer-live.json" "$OUT/$evidence_file"
      for suffix in .new .bak; do
        if adb_cmd shell run-as "$PKG" test -e "no_backup/threat-catalog.json$suffix"; then
          echo "Committed writer left $suffix behind." >&2; exit 1
        fi
      done
    fi
    adb_cmd shell am force-stop "$PKG"
    for _ in $(seq 1 20); do
      [[ -z "$(adb_cmd shell pidof "$PKG" | tr -d '\r')" ]] && break
      sleep 1
    done
    [[ -z "$(adb_cmd shell pidof "$PKG" | tr -d '\r')" ]] || {
      echo "QA writer is still alive." >&2; exit 1;
    }
    wait "$ATOMIC_WRITER_PID" || true
    ATOMIC_WRITER_PID=
    if grep -Eq 'OK \([0-9]+ tests?\)' "$OUT/atomic-write.log"; then
      echo "Writer returned success instead of being terminated." >&2; exit 1
    fi
    printf 'force-stopped package=%s writerPid=%s route=%s; device not rebooted\n' \
      "$PKG" "$writer_android_pid" "$route" > "$OUT/atomic-kill.txt"
    capture_atomic killed
    cmp "$OUT/base-writer-live.json" "$OUT/base-killed.json"
    cmp "$OUT/prefs-before-write.xml" "$OUT/prefs-killed.xml"
    if [[ "$route" == atomic-pending ]]; then
      adb_cmd exec-out run-as "$PKG" cat no_backup/threat-catalog.json.new > "$OUT/pending-killed.new"
      cmp "$OUT/pending-live.new" "$OUT/pending-killed.new"
      sha256sum "$OUT/pending-live.new" "$OUT/pending-killed.new" > "$OUT/pending.sha256"
    fi
    for phase in "${verify_phases[@]}"; do
      run_tests "$phase" -e class com.maguardian.security.data.ThreatCatalogRotationTest \
        -e rotationPhase "$phase" -e rotationRoute "$route"
      grep -q "${marker}_OK phase=$phase" "$OUT/$phase.log"
      capture_atomic "$phase"
      cmp "$OUT/base-writer-live.json" "$OUT/base-$phase.json"
      cmp "$OUT/prefs-before-write.xml" "$OUT/prefs-$phase.xml"
      if adb_cmd shell run-as "$PKG" test -e no_backup/threat-catalog.json.new; then
        echo "Atomic .new remains after initialization." >&2; exit 1
      fi
      record_package "$phase"
      cmp "$OUT/installed-old.sha256" "$OUT/installed-$phase.sha256"
      adb_cmd shell am force-stop "$PKG"
    done
    if [[ "$route" == atomic-committed ]]; then
      echo "Atomic committed write passed: finishWrite replaced disk catalog 11 with signed catalog 13 while memory stayed at 11; QA writer killed; fresh readers loaded exact committed bytes/hash; detection and Manter preserved; no preference rewrite, APK upgrade or reboot."
    else
      echo "Atomic pending write passed: actual QA writer killed; incomplete .new discarded by fresh reader; base bytes/hash, local detection and Manter unchanged; no APK upgrade or reboot."
    fi
  elif [[ "$route" == oversized ]]; then
    grep -q 'OVERSIZED_OK phase=recovery ' "$OUT/rotation-recovery.log"
    grep -q 'OVERSIZED_OK phase=recovery-restart ' "$OUT/rotation-recovery-restart.log"
    echo "Oversized-cache recovery passed: direct APK 41 to 43; bounded size rejection; signed bootstrap 14, local detection and Manter preserved; metadata only, no clear or reboot."
  elif [[ "$route" == corrupt-* ]]; then
    echo "Corrupt-cache recovery passed: $route cache rejected; direct APK 41 to 43; signed bootstrap 14 and update 16; local detection and Manter preserved without clearing data."
  elif [[ "$route" == interrupted ]]; then
    echo "Interrupted recovery passed: staged/abandoned session kept APK 41 and catalog 13; real upgrade to 43 recovered via bootstrap 14 and update 16; Manter preserved."
  elif [[ "$route" == direct ]]; then
    echo "Direct recovery passed: APKs 41/43 only; revoked cache 13 rejected; bootstrap 14; update 16; Manter preserved."
  else
    echo "Rotation passed: APKs 41/42/43; catalogs 11/12/13/14/16; Manter preserved."
  fi
  exit 0
elif [[ -n "${1:-}" ]]; then
  echo "Unknown validation mode: $1" >&2; exit 1
fi
node tools/catalog-validation-server.mjs > "$OUT/catalog-server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 30); do
  [[ -f app/build/catalogValidationFixtures/server-cert.pem ]] &&
    curl --silent --fail --cacert app/build/catalogValidationFixtures/server-cert.pem \
      https://localhost:8443/threat-catalog.json >/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "QA HTTPS server failed to start." >&2; exit 1; }
  sleep 1
done
curl --silent --fail --cacert app/build/catalogValidationFixtures/server-cert.pem \
  https://localhost:8443/threat-catalog.json >/dev/null
bash gradlew --no-daemon :app:assembleDebug :app:assembleCatalogValidation \
  :app:assembleCatalogValidationAndroidTest | tee "$OUT/build.log"
adb_cmd install -r app/build/outputs/apk/catalogValidation/app-catalogValidation.apk
adb_cmd install -r app/build/outputs/apk/androidTest/catalogValidation/app-catalogValidation-androidTest.apk
# Only the disposable package is cleared; normal app data is never touched.
adb_cmd shell pm clear "$PKG"
restore_network
run_tests online -e class \
  com.maguardian.security.data.ThreatCatalogStorageTest,com.maguardian.security.data.ThreatCatalogUpdaterTest,com.maguardian.security.data.ThreatCatalogUiTest \
  -e network online
run_tests reboot-prepare -e class com.maguardian.security.data.ThreatCatalogRebootTest -e rebootPhase prepare
adb_cmd shell svc wifi disable
adb_cmd shell svc data disable
run_tests offline -e class \
  com.maguardian.security.data.ThreatCatalogUpdaterTest#unavailableNetworkPreservesOfflineCatalogPreferencesAndBundledDetection,com.maguardian.security.data.ThreatCatalogUiTest \
  -e network offline
adb_cmd reboot
wait_boot
# Keep the verification offline even if the emulator restored network after reboot.
adb_cmd shell svc wifi disable
adb_cmd shell svc data disable
run_tests reboot-verify -e class com.maguardian.security.data.ThreatCatalogRebootTest -e rebootPhase verify
adb_cmd shell dumpsys jobscheduler > "$OUT/jobs-after-reboot.txt"
echo "Android catalog validation passed. Evidence: $OUT"
