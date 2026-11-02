package app.nastavnik.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.study.Alarms
import app.nastavnik.study.LessonQuiet
import app.nastavnik.study.LiveNotifications
import app.nastavnik.study.SleepPlan
import app.nastavnik.study.StudyLock
import app.nastavnik.study.WakeAlarm
import app.nastavnik.study.WakePrefs
import app.nastavnik.study.WakeGuard
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.nastavnik.sync.WebDavStore
import app.nastavnik.sync.YandexDiskStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Синхронизация: подключить облако один раз на каждом устройстве. Яндекс Диск — по паролю приложения
 * (id.yandex.ru → Безопасность → Пароли приложений → «Файлы»), любой другой — по адресу WebDAV.
 */
@Composable
fun SyncSection() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val status by app.sync.status.collectAsStateWithLifecycle()
    var configured by remember { mutableStateOf(app.sync.configured) }
    var yandex by remember { mutableStateOf(app.settings.syncUrl().let { it.isEmpty() || it == YandexDiskStore.MARKER || it == WebDavStore.YANDEX }) }
    var url by remember { mutableStateOf(app.settings.syncUrl().takeIf { it != WebDavStore.YANDEX && it != YandexDiskStore.MARKER }.orEmpty()) }
    var login by remember { mutableStateOf(app.settings.syncLogin()) }
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard("Синхронизация") {
        if (configured) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (app.settings.syncUrl()) {
                            YandexDiskStore.MARKER -> "Яндекс Диск"
                            WebDavStore.YANDEX -> "Яндекс Диск по WebDAV · ${app.settings.syncLogin()}"
                            else -> app.settings.syncLogin()
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val time = if (status.lastAt > 0) SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(status.lastAt)) else "ещё не было"
                    Text("${status.message.ifEmpty { "Подключено" }} · $time", style = MaterialTheme.typography.bodySmall, color = if (status.ok) Brand.InkSoft else Brand.Error)
                }
                if (status.running) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Accent, strokeWidth = 2.dp)
                else InkButton("Сейчас") { scope.launch { app.sync.now() } }
            }
            if (status.devices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FieldLabel("Другие устройства")
                status.devices.forEach { d ->
                    val (name, type, at) = d.split('·').let { Triple(it.getOrNull(0).orEmpty(), it.getOrNull(1).orEmpty(), it.getOrNull(2)?.toLongOrNull() ?: 0) }
                    Text(
                        "${if (type == "tablet") "Планшет" else "Телефон"} $name · ${SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(at))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                "Синхронизируются тетради, карточки, прогресс, чистовики, напоминания, группа и экзамены — сами, при открытии и закрытии приложения.",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
            )
            LinkText("Отключить облако") { app.sync.disconnect(); configured = false }
            return@SectionCard
        }

        FieldLabel(
            "Тетради на планшете, карточки и напоминания на телефоне — одно и то же",
            "Подключи одно облако на обоих устройствах (тот же аккаунт). Своего сервера нет: данные лежат в папке «Nastavnik» твоего диска.",
        )
        Segmented(listOf(true to "Яндекс Диск", false to "Другой WebDAV"), yandex) { yandex = it }
        AnimatedVisibility(!yandex, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            AppTextField(url, { url = it }, label = "Адрес WebDAV", placeholder = "https://cloud.example.ru/remote.php/dav/files/имя/")
        }
        if (yandex) {
            // WebDAV у Яндекса теперь только для платного Яндекс 360 — бесплатно работает REST API по токену.
            Text(
                "1. Открой Полигон Яндекс Диска (кнопка ниже) и войди в свой аккаунт.\n" +
                    "2. Нажми «Получить OAuth-токен» и разреши доступ.\n" +
                    "3. Скопируй токен (длинная строка, обычно начинается с «y0_») и вставь сюда.",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
            )
            LinkText("Открыть Полигон Яндекса") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YandexDiskStore.POLIGON)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            AppTextField(
                password, { password = it.trim() }, label = "Токен Яндекс Диска",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "Токен действует около года и даёт доступ к Диску — храню его зашифрованным. Отозвать: id.yandex.ru → «Безопасность» → «Доступы».",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
            )
        } else {
            AppTextField(login, { login = it }, label = "Логин")
            AppTextField(
                password, { password = it }, label = "Пароль",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        error?.let { StatusLine(it, Tone.ERROR) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Подключить", enabled = !checking && password.isNotBlank() && (yandex || (login.isNotBlank() && url.startsWith("http")))) {
                val address = if (yandex) YandexDiskStore.MARKER else url.trim()
                checking = true
                error = null
                scope.launch {
                    val ok = runCatching { if (yandex) YandexDiskStore(password).check() else WebDavStore(address, login.trim(), password).check() }
                    checking = false
                    ok.onSuccess {
                        app.settings.setSync(address, if (yandex) "Яндекс Диск" else login, password)
                        password = ""
                        configured = true
                        app.sync.now()
                    }.onFailure { error = it.message ?: "Не получилось подключиться" }
                }
            }
            if (checking) CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Accent, strokeWidth = 2.dp)
        }
    }
}

/** Устройство: как вести себя и за сколько предупреждать о паре. */
@Composable
fun DeviceSection(tablet: Boolean, showRole: Boolean = true) {
    val context = LocalContext.current
    val repo = context.app.settings
    var role by remember { mutableStateOf(repo.deviceRole()) }
    var alert by remember { mutableStateOf(repo.lessonAlertMin()) }
    SectionCard("Это устройство") {
        if (showRole) {
            FieldLabel(
                "Режим",
                if (tablet) "Планшет: тетради для стилуса, наставник, чистовики — и всё остальное." else "Телефон: расписание, карта, карточки и напоминания — без тетрадей для стилуса.",
            )
            Segmented(listOf("auto" to "Сам", "phone" to "Телефон", "tablet" to "Планшет"), role) {
                role = it
                repo.setDeviceRole(it)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        FieldLabel("Перед парой", "Живое уведомление: аудитория, отсчёт до начала, весь день полосой.")
        Segmented(listOf(0 to "Не надо", 5 to "за 5 мин", 10 to "за 10", 15 to "за 15"), alert) {
            alert = it
            repo.setLessonAlertMin(it)
            Alarms.rescheduleAll(context)
        }
        if (alert > 0) LiveSection()
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        var quiet by remember { mutableStateOf(repo.quietLessons()) }
        var access by remember { mutableStateOf(LessonQuiet.hasAccess(context)) }
        LifecycleResumeEffect(Unit) {
            access = LessonQuiet.hasAccess(context)
            onPauseOrDispose { }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Тихо на парах", "На время пары — «Не беспокоить», после — как было. Будильники и повторные звонки проходят.")
            }
            AppSwitch(quiet) {
                quiet = it
                repo.setQuietLessons(it)
                if (it && !access) StudyLock.openSettings(context, LessonQuiet.accessIntent())
                Alarms.rescheduleAll(context)
            }
        }
        AnimatedVisibility(quiet && !access, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Нужно разрешить «Наставнику» доступ к режиму «Не беспокоить».",
                    style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f),
                )
                LinkText("Разрешить") { StudyLock.openSettings(context, LessonQuiet.accessIntent()) }
            }
        }
    }
}

/**
 * Группа английского: у группы несколько пар иностранного в одно время — у разных преподавателей.
 * Выбранная остаётся в расписании, на «Сегодня» и в уведомлениях; «Все» — без фильтра.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LanguageSection() {
    val context = LocalContext.current
    val app = context.app
    var chosen by remember { mutableStateOf(app.settings.languageTeacher()) }
    val options by androidx.compose.runtime.produceState(emptyList<String>()) {
        value = app.schedule.languageTeachers(ScheduleText.monday(java.time.LocalDate.now()))
    }
    SectionCard("Английский") {
        FieldLabel(
            "Твоя группа",
            if (options.isEmpty()) "Варианты появятся, когда загрузится расписание с парами иностранного."
            else "Оставлю в расписании и уведомлениях пары только твоего преподавателя.",
        )
        if (options.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun pick(t: String) {
                app.settings.setLanguageTeacher(t)
                chosen = t
                app.schedule.changed()
                Alarms.rescheduleAll(context)
            }
            ChipButton("Все", selected = chosen.isBlank() || chosen == "*") { pick("*") }
            options.forEachIndexed { i, t ->
                ChipButton("Группа ${i + 1} · ${Lesson.shortName(t)}", selected = t == chosen) { pick(t) }
            }
        }
    }
}

/**
 * Будильник к первой паре: время подъёма считается от расписания, сборов и дороги; вечером — когда лечь,
 * чтобы проснуться в конце цикла сна. Здесь же — всё, что нужно, чтобы он точно сработал.
 */
@Composable
fun WakeSection() {
    val context = LocalContext.current
    val app = context.app
    val st = app.settings
    val w = remember { WakePrefs.of(context) }
    val scope = rememberCoroutineScope()
    val askNotifications = rememberAskNotifications()
    var on by remember { mutableStateOf(w.on) }
    var ready by remember { mutableStateOf(w.readyMin) }
    var travel by remember { mutableStateOf(w.travelMin) }
    var math by remember { mutableStateOf(w.math) }
    var check by remember { mutableStateOf(w.check) }
    var skip by remember { mutableStateOf(w.skipDay) }
    var tick by remember { mutableStateOf(0) }
    var fullScreen by remember { mutableStateOf(true) }
    var battery by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var guardOn by remember { mutableStateOf(w.guardOn) }
    var guardService by remember { mutableStateOf(WakeGuard.enabled(context)) }
    LifecycleResumeEffect(Unit) {
        guardService = WakeGuard.enabled(context)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        fullScreen = android.os.Build.VERSION.SDK_INT < 34 || runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
        battery = context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        notifications = Permissions.notifications(context)
        tick++
        onPauseOrDispose { }
    }
    val plan by androidx.compose.runtime.produceState<WakeAlarm.Plan?>(null, on, ready, travel, skip, tick) { value = WakeAlarm.plan(context) }
    fun changed() {
        tick++
        Alarms.rescheduleAll(context)
    }
    fun open(action: String) = StudyLock.openSettings(context, Intent(action, Uri.parse("package:${context.packageName}")))

    SectionCard("Будильник к первой паре") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Будить к первой паре", "Сам ставит будильник по расписанию: только в дни с парами, с учётом сборов, дороги и циклов сна.")
            }
            AppSwitch(on) {
                on = it
                w.on = it
                if (it) askNotifications()
                changed()
            }
        }
        AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Ближайший будильник и когда лечь.
                val p = plan
                val today = java.time.LocalDate.now()
                val dayName = p?.let {
                    when (it.date) {
                        today -> "Сегодня"
                        today.plusDays(1) -> "Завтра"
                        else -> it.date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ru"))).replaceFirstChar { c -> c.titlecase(Locale.forLanguageTag("ru")) }
                    }
                }
                if (p == null) {
                    StatusLine(
                        if (st.scheduleGroup().isBlank()) "Сначала выбери группу в «Расписании»" else "На неделе пар нет — будить не нужно",
                        Tone.INFO,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$dayName · подъём в ${WakeAlarm.hm(p.wake)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Пара в ${WakeAlarm.hm(p.lessonAt)}" + (p.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                        )
                        Text(
                            "Лечь, чтобы проснуться в конце цикла: " +
                                SleepPlan.bedtimes(p.latest).joinToString(" · ") { (n, t) -> "${WakeAlarm.hm(t)} ($n)" } +
                                ". Лучше — ${WakeAlarm.hm(p.bedtime)}: 5 циклов, 7 ч 30 мин.",
                            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                        )
                    }
                }
                if (skip.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${java.time.LocalDate.parse(skip).format(java.time.format.DateTimeFormatter.ofPattern("d.MM"))} не разбужу", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f))
                        LinkText("вернуть") { w.skipDay = ""; skip = ""; changed() }
                    }
                } else if (p != null) {
                    LinkText("Не будить ${dayName?.lowercase()}") { w.skipDay = p.date.toString(); skip = p.date.toString(); changed() }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                FieldLabel("Собираться", "Душ, завтрак, одеться.")
                Segmented(listOf(20 to "20 мин", 30 to "30", 45 to "45", 60 to "60"), ready) { ready = it; w.readyMin = it; changed() }
                FieldLabel("Дорога до пары", "Для онлайн-пар не учитывается.")
                Segmented(listOf(5 to "5 мин", 15 to "15", 25 to "25", 40 to "40"), travel) { travel = it; w.travelMin = it; changed() }

                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { FieldLabel("Выключать примером", "Спящий нажмёт «стоп», а сложить числа — уже нет.") }
                    AppSwitch(math) { math = it; w.math = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { FieldLabel("Проверить, что встал", "Через 3 минуты: «Ты встал?» Не ответил — звонит снова.") }
                    AppSwitch(check) { check = it; w.check = it }
                }

                // Защита: пока звонит — нельзя выключить телефон и удалить приложение.
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel(
                            "Не дать отключить",
                            "Пока звонит: закрываю меню выключения телефона, удаление приложения и настройки. " +
                                "Если телефон всё-таки перезагрузить кнопкой — после включения зазвоню снова.",
                        )
                    }
                    AppSwitch(guardOn) { guardOn = it; w.guardOn = it }
                }
                if (guardOn && !guardService) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Включи службу «Наставник: защита и учебный замок» в спец. возможностях", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        LinkText("Открыть") {
                            StudyLock.openSettings(context, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    Text(
                        "Если пункт серый: Настройки → Приложения → Наставник → ⋮ (сверху справа) → «Разрешить ограниченные настройки», потом снова сюда.",
                        style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                    )
                }

                // Что может помешать будильнику — с кнопками исправить.
                if (!notifications) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Уведомления выключены — экран будильника не покажется", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    LinkText("Включить") { askNotifications() }
                }
                if (!fullScreen) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Разреши показ будильника на заблокированном экране", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    LinkText("Разрешить") { open("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT") }
                }
                if (!battery) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сними ограничения батареи — тогда телефон точно не «усыпит» будильник", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f))
                    LinkText("Снять") { open(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
                }
                SoftButton("Проверить звонок") { WakeAlarm.startRinging(context, test = true) }
            }
        }
    }
}

/** Живое уведомление: способ обновления и пример кнопкой «Показать сейчас». */
@Composable
private fun LiveSection() {
    val context = LocalContext.current
    val repo = context.app.settings
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(LiveNotifications.mode(context)) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    FieldLabel("Живое уведомление — обновление", mode.hint)
    Segmented(LiveNotifications.Mode.entries.map { it to it.label }, mode) { mode = it; repo.setLiveMode(it.id); Alarms.rescheduleAll(context) }
    SoftButton("Показать сейчас") { scope.launch { LiveNotifications.preview(context) } }
}

