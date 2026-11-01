# Сторонние компоненты

## Lecturial 2

Загрузка расписания с сайта Университета «Сириус» (`app/src/main/java/app/nastavnik/schedule/SiriusClient.kt`
и тесты к нему) основана на `SiriusScheduleDataSource` из
[Lecturial 2](https://github.com/neckmnull/Lecturial-2), Copyright 2026 Lecturial 2 contributors,
Apache License 2.0. Код изменён: kotlinx.serialization вместо org.json, OkHttp 4, свои модели, только группы.
Текст лицензии и NOTICE — в `third_party/lecturial-2/`, в приложении — `assets/licenses/lecturial-2.txt`.

Lecturial 2 и «Наставник» — неофициальные проекты и не связаны с Университетом «Сириус».

## Карта кампуса

Данные карты (`app/src/main/assets/campus/`) получены из навигатора ГМЦ, присланного владельцем проекта,
и пересобраны `tools/campus/build_campus.py`.
