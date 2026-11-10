#!/usr/bin/env bash
# Collect evidence before android-emulator-runner shuts the emulator down,
# including when instrumentation reports failures.
set +e
gradle --no-daemon :app:connectedDebugAndroidTest
result=$?
mkdir -p audit-screens
adb pull /sdcard/Download/pts-native-audit/. audit-screens/
adb logcat -d -t 3000 > audit-logcat.txt
exit "$result"
