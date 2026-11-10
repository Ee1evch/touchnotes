# Передача работы в локальную сессию (VS Code)

Облачная сессия не может попасть на lks.siriusuniversity.ru и schedule.siriusuniversity.ru: сайты пускают
только российские IP. Работа продолжается на компьютере владельца (Ubuntu, VS Code, Claude Code),
оттуда сайты открываются.

## Где что
- Ветка: `ccr-3df92df2-v3c2ev`. Каждый push собирает APK в GitHub Actions и выкладывает релиз `build-N`
  (`releases/latest/download/nastavnik.apk`, приложение само предлагает обновиться).
- Решения и устройство всего — `DECISIONS.md` (разделы про суперприложение, «где я», навигацию, ЛКС).
- Коммиты заканчиваются строкой `Co-Authored-By: …` (см. историю). PR не открывать без просьбы.
- Локально Android SDK может не быть — проверка сборки идёт в CI; чистую логику можно гонять юнит-тестами
  (`app/src/test/...`, `./gradlew testDebugUnitTest`, если SDK есть).

## Успеваемость из ЛКС — главное незаконченное
- Код: `app/src/main/java/app/nastavnik/grades/LksClient.kt` (вход и журнал), `GradesRepo.kt`,
  `ui/GradesScreen.kt`, тест `app/src/test/java/app/nastavnik/grades/LksClientTest.kt` (мок-сервер).
- Как устроен ЛКС (по сохранённым страницам login/dashboard/journal): платформа «Тандем», Laravel + Livewire.
  - Вход: `GET /login` → скрытое поле `_token`; `POST /login` с `_token`, `login` (почта), `password`, `remember=on`.
  - Журнал: `GET /journal` → `<meta name="csrf-token">`; `POST /journal/get` с заголовком `X-CSRF-TOKEN`,
    JSON `{startPeriod, interval: "year", col, endPeriod, numPeriod: 0, search: "", lectures/seminars/practices/laboratories: false}`.
    Ответ: `lessons[]` (`title`, `avg_mark`, `rating`, `count_absent`, `items{"YYYY-MM-DD": [{marks[{mark_value_standard, color, second_mark}], absent, absent_valid, comment, hometask}]}`),
    `cacheBuilding` (повторять через 3 с).
- **Причина поломки найдена (1 ноября 2026):** в форме `/login` стоит Яндекс SmartCaptcha, и запросом войти
  нельзя. Вход перенесён во встроенное окно (человек проходит капчу, приложение забирает куки), подробности —
  в `DECISIONS.md`, раздел «ЛКС: проверка с настоящим сайтом». На компьютере владельца работает VPN
  (`happ-xray`): для запросов к ЛКС нужен `curl --interface wlp3s0`, иначе `418`.
- Ещё не проверено вживую: ответ `POST /journal/get` (нужны куки сессии из браузера владельца) и вход
  через окно на телефоне.
- Если владелец решит убрать успеваемость: удалить пакет `grades`, `GradesScreen.kt`, `LksScreen.kt`, тесты
  `grades`, пункты «Успеваемость» в `PhoneShell.kt`/`HomeScreen.kt`, `GradesSettingsSection` в настройках,
  экраны `SCREEN_GRADES`/`SCREEN_LKS` и обновление в `MainActivity.onResume`, канал уведомлений `grades`,
  зависимость `androidx.webkit`; при запуске стереть сохранённое: ключи `grades_*`, `lks_*` в настройках и
  в зашифрованных настройках, папку `files/grades`.

## Также не проверено вживую
- Расписание с schedule.siriusuniversity.ru (`schedule/SiriusClient.kt`) — проверялось только на мок-сервере.
- Синхронизация через Яндекс Диск по токену с Полигона (`sync/YandexDiskStore.kt`).
