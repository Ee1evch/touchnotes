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
import app.nastavnik.study.Alarms
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
fun DeviceSection(tablet: Boolean) {
    val context = LocalContext.current
    val repo = context.app.settings
    var role by remember { mutableStateOf(repo.deviceRole()) }
    var alert by remember { mutableStateOf(repo.lessonAlertMin()) }
    SectionCard("Это устройство") {
        FieldLabel(
            "Режим",
            if (tablet) "Планшет: тетради для стилуса, наставник, чистовики — и всё остальное." else "Телефон: расписание, карта, карточки и напоминания — без тетрадей для стилуса.",
        )
        Segmented(listOf("auto" to "Сам", "phone" to "Телефон", "tablet" to "Планшет"), role) {
            role = it
            repo.setDeviceRole(it)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        FieldLabel("Перед парой", "Уведомление с аудиторией и маршрутом по карте.")
        Segmented(listOf(0 to "Не надо", 5 to "за 5 мин", 10 to "за 10", 15 to "за 15"), alert) {
            alert = it
            repo.setLessonAlertMin(it)
            Alarms.rescheduleAll(context)
        }
    }
}
