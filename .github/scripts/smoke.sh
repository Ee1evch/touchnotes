#!/usr/bin/env bash
# Дымовые тесты на эмуляторе: запускает androidTest и печатает нужную часть logcat в лог CI.
set -u
adb logcat -c || true
./gradlew --no-daemon connectedDebugAndroidTest
code=$?
echo "===== logcat (SmokeTest / System.out / ошибки) ====="
adb logcat -d -v brief SmokeTest:I System.out:I TestRunner:I AndroidRuntime:E '*:S' || true
exit $code
