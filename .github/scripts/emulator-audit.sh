#!/usr/bin/env bash
# Collect evidence before android-emulator-runner shuts the emulator down,
# including when instrumentation reports failures.
set +e
adb logcat -c
adb logcat -v threadtime > audit-logcat.txt &
log_pid=$!
gradle --no-daemon :app:connectedDebugAndroidTest
result=$?
mkdir -p audit-screens
adb pull /sdcard/Download/pts-native-audit/. audit-screens/
kill "$log_pid"
wait "$log_pid" 2>/dev/null
exit "$result"
