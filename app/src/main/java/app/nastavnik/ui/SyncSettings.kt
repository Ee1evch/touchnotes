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
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleText
import app.nastavnik.study.Alarms
import app.nastavnik.study.LessonQuiet
import app.nastavnik.study.LiveNotifications
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
    var passphrase by remember { mutableStateOf(app.settings.syncPassphrase()) }

    var expanded by remember { mutableStateOf(false) }

    SectionCard("Синхронизация") {
        if (configured) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (app.settings.syncUrl()) {
                            YandexDiskStore.MARKER -> "Яндекс Диск (папка приложения)"
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FieldLabel("Сквозное шифрование (E2E)", "Данные и тетради шифруются AES-256-GCM перед загрузкой в облако.")
            AppTextField(
                passphrase, {
                    passphrase = it
                    app.settings.setSyncPassphrase(it)
                },
                label = "Парольная фраза (необязательно)",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            LinkText("Отключить облако") { app.sync.disconnect(); configured = false; expanded = false }
            return@SectionCard
        }

        FieldLabel(
            "Общие тетради, карточки и расписание",
            "Яндекс Диск или WebDAV — данные хранятся на твоём диске в папке «Nastavnik».",
        )
        if (!expanded) {
            SoftButton("Подключить облако", icon = R.drawable.ic_cloud) { expanded = true }
            return@SectionCard
        }
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
        AppTextField(
            passphrase, { passphrase = it }, label = "Парольная фраза E2E (необязательно)",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
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
                        app.settings.setSyncPassphrase(passphrase)
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

/** Уведомления и тихий режим на парах. */
@Composable
fun DeviceSection(tablet: Boolean = false, showRole: Boolean = false) {
    val context = LocalContext.current
    val repo = context.app.settings
    var alert by remember { mutableStateOf(repo.lessonAlertMin()) }
    SectionCard("Уведомления и пары") {
        FieldLabel("Перед парой", "Живое уведомление: аудитория, отсчёт до конца пары или перемены.")
        Segmented(listOf(0 to "Выкл.", 5 to "за 5 мин", 10 to "за 10", 15 to "за 15"), alert) {
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
                FieldLabel("Тихо на парах", "На время пары — «Не беспокоить», после — как было. Повторные звонки проходят.")
            }
            AppSwitch(quiet) {
                quiet = it
                repo.setQuietLessons(it)
                if (it && !access) context.startActivity(LessonQuiet.accessIntent())
                Alarms.rescheduleAll(context)
            }
        }
        AnimatedVisibility(quiet && !access, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Нужно разрешить «Наставнику» доступ к режиму «Не беспокоить».",
                    style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, modifier = Modifier.weight(1f),
                )
                LinkText("Разрешить") { context.startActivity(LessonQuiet.accessIntent()) }
            }
        }
    }
}

/**
 * Фото преподавателей в расписании: по умолчанию выключено — без фото не появляются пустые кружки. Включил —
 * список преподавателей из расписания (эта и следующая неделя), к каждому можно выбрать фото из галереи.
 */
@Composable
fun TeacherPhotosSection() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(app.settings.teacherPhotos()) }
    val teachers by androidx.compose.runtime.produceState(emptyList<String>(), on) {
        val monday = ScheduleText.monday(java.time.LocalDate.now())
        value = listOf(monday, monday.plusWeeks(1)).flatMap { m -> app.schedule.cached(m)?.lessons.orEmpty() }
            .flatMap { it.teachers }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var picking by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val t = picking
        if (uri != null && t != null) scope.launch { CustomImages.save(context, uri, CustomImages.teacherFile(context, t), 512) }
        picking = null
    }
    SectionCard("Преподаватели") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Фото в расписании", "Маленькое фото рядом с фамилией — у тех, кому ты его поставил.")
            }
            AppSwitch(on) { on = it; app.settings.setTeacherPhotos(it) }
        }
        if (on) {
            if (teachers.isEmpty()) Text("Преподаватели появятся, когда загрузится расписание.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            val version = CustomImages.version
            teachers.forEach { t ->
                val has = remember(t, version) { CustomImages.teacherFile(context, t).exists() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TeacherAvatar(listOf(t), 36.dp)
                    Text(Lesson.shortName(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    LinkText(if (has) "Сменить" else "Фото") {
                        picking = t
                        runCatching {
                            pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    if (has) LinkText("Убрать") { CustomImages.remove(CustomImages.teacherFile(context, t)) }
                }
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

