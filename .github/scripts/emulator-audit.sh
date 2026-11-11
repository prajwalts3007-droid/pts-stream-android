#!/usr/bin/env bash
# Collect evidence before android-emulator-runner shuts the emulator down,
# including when instrumentation reports failures.
set +e
adb logcat -c
adb logcat -v threadtime > audit-logcat.txt &
log_pid=$!
# Acknowledge Android's first-use immersive tutorial on this disposable emulator.
# It is a non-focusable System UI overlay, not an app Back/navigation failure.
# This does not change the APK or bypass installer/Google consent on real devices.
adb shell settings put secure immersive_mode_confirmations confirmed
gradle --no-daemon :app:connectedDebugAndroidTest
result=$?
mkdir -p audit-screens
adb pull /sdcard/Download/pts-native-audit/. audit-screens/
kill "$log_pid"
wait "$log_pid" 2>/dev/null
exit "$result"
