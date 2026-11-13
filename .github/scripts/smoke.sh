#!/usr/bin/env bash
# Дымовые тесты на эмуляторе: запускает androidTest и печатает нужную часть logcat в лог CI.
set -u
adb logcat -c || true
# Скриншоты печатаются в журнал — буфер побольше, чтобы ранние тесты не вытеснялись.
adb logcat -G 16M || true
# Системные окна «… isn't responding» на медленном эмуляторе перекрывают экран и ломают тесты.
adb shell settings put global hide_error_dialogs 1 || true
# Журнал пишем в файл по ходу тестов: снимков много, и кольцевой буфер logcat вытеснял ранние.
adb logcat -v brief SmokeTest:I MentorFlowTest:I Nastavnik:I System.out:I TestRunner:I AndroidRuntime:E '*:S' > smoke-logcat.txt &
logpid=$!

export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2048m"

./gradlew --no-daemon :app:connectedFullReleaseAndroidTest :parent:connectedReleaseAndroidTest
code=$?

# Очистка памяти между прогонами для предотвращения OOM на runner-е
sync; sleep 2

# «Наставник · Студент» — только свой тест (общие тесты полной версии в лёгкой не к месту).
if [ $code -eq 0 ]; then
  ./gradlew --no-daemon :app:connectedStudentReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.nastavnik.StudentFlowTest || code=1
fi

sleep 2
kill $logpid 2>/dev/null || true
echo "===== logcat (SmokeTest / System.out / ошибки) ====="
cat smoke-logcat.txt
exit $code
