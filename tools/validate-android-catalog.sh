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
      "${1:-}" == "--rotation-corrupt-truncated" || "${1:-}" == "--rotation-corrupt-tampered" ]]; then
  route=three-step
  stages=(old transition recovery)
  if [[ "$1" == "--rotation-direct" || "$1" == "--rotation-interrupted" ]]; then
    route=direct
    stages=(old recovery)
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
    run_tests "rotation-$stage" -e class com.maguardian.security.data.ThreatCatalogRotationTest \
      -e rotationPhase "$stage" -e rotationRoute "$route"
    adb_cmd shell am force-stop "$PKG"
    run_tests "rotation-$stage-restart" -e class com.maguardian.security.data.ThreatCatalogRotationTest \
      -e rotationPhase "$stage-restart" -e rotationRoute "$route"
    record_package "$stage"
    if [[ "$route" == interrupted || "$route" == corrupt-* ]]; then
      sha256sum "$apk" | cut -d ' ' -f 1 > "$OUT/expected-$stage.sha256"
      cmp "$OUT/expected-$stage.sha256" "$OUT/installed-$stage.sha256"
    fi
  done
  if [[ "$route" == corrupt-* ]]; then
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
