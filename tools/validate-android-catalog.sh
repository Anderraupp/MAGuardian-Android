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
  adb_cmd logcat -d -s BootReceiver:I ThreatCatalogJobService:I AndroidRuntime:E > "$OUT/android-logcat.txt" || true
  adb_cmd shell dumpsys jobscheduler > "$OUT/jobs-final.txt" || true
  if adb_cmd shell run-as "$PKG" test -f cache/catalog-ui-failure.png >/dev/null 2>&1; then
    adb_cmd exec-out run-as "$PKG" cat cache/catalog-ui-failure.png > "$OUT/catalog-ui-failure.png" 2>/dev/null || rm -f "$OUT/catalog-ui-failure.png"
  else
    rm -f "$OUT/catalog-ui-failure.png"
  fi
  restore_network
}
trap collect_evidence EXIT
wait_boot
adb_cmd shell settings put global window_animation_scale 0
adb_cmd shell settings put global transition_animation_scale 0
adb_cmd shell settings put global animator_duration_scale 0
cd "$ROOT"
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
