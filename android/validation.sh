#!/bin/sh
# Runs against caller-provided disposable sessions; never starts/stops a server.
set -eu
: "${ANDROID_HOME:?Set Android SDK path}"
: "${HERDR_TEST_DEVICE:?Set an explicit adb device serial}"
: "${HERDR_TEST_HOST:?Set SSH host reachable from Android}"
: "${HERDR_TEST_PORT:?Set SSH port}"
: "${HERDR_TEST_USER:?Set SSH user}"
: "${HERDR_TEST_FINGERPRINT:?Set trusted SSH SHA256 fingerprint}"
: "${HERDR_TEST_SESSION_A:?Set disposable Herdr session A}"
: "${HERDR_TEST_SESSION_B:?Set disposable Herdr session B}"
: "${HERDR_TEST_EXECUTABLE:?Set remote Herdr executable path}"
: "${HERDR_TEST_KEY:?Set path to disposable SSH private key}"
case "$HERDR_TEST_SESSION_A:$HERDR_TEST_SESSION_B" in
  *validation*:*validation*|*test*:*test*) ;;
  *) echo 'Both session names must contain test or validation.' >&2; exit 2 ;;
esac
cd "$(dirname "$0")"
validation_adb="$ANDROID_HOME/platform-tools/adb"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
"$validation_adb" -s "$HERDR_TEST_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
"$validation_adb" -s "$HERDR_TEST_DEVICE" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$validation_adb" -s "$HERDR_TEST_DEVICE" shell run-as dev.herdr.android mkdir -p files
trap '"$validation_adb" -s "$HERDR_TEST_DEVICE" shell run-as dev.herdr.android rm -f files/validation-key >/dev/null 2>&1 || true' EXIT HUP INT TERM
"$validation_adb" -s "$HERDR_TEST_DEVICE" shell run-as dev.herdr.android sh -c '"cat > files/validation-key"' < "$HERDR_TEST_KEY"
mkdir -p app/build/reports/instrumentation
"$validation_adb" -s "$HERDR_TEST_DEVICE" shell am instrument -w -r \
  -e herdrHost "$HERDR_TEST_HOST" -e herdrPort "$HERDR_TEST_PORT" \
  -e herdrUser "$HERDR_TEST_USER" -e herdrFingerprint "$HERDR_TEST_FINGERPRINT" \
  -e herdrSessionA "$HERDR_TEST_SESSION_A" -e herdrSessionB "$HERDR_TEST_SESSION_B" \
  -e herdrExecutable "$HERDR_TEST_EXECUTABLE" \
  dev.herdr.android.test/androidx.test.runner.AndroidJUnitRunner \
  > app/build/reports/instrumentation/results.txt
cat app/build/reports/instrumentation/results.txt
grep -q '^OK (' app/build/reports/instrumentation/results.txt
