package app.nastavnik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.ai.Pricing
import app.nastavnik.ai.UsageLog
import app.nastavnik.app
import app.nastavnik.data.Backup
import app.nastavnik.data.UpdateInfo
import app.nastavnik.data.Updater
import app.nastavnik.study.Streaks
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Тема: как в системе, светлая или тёмная. */
@Composable
fun ThemeSection() {
    val context = LocalContext.current
    val repo = context.app.settings
    var mode by remember { mutableStateOf(runCatching { ThemeMode.valueOf(repo.themeMode()) }.getOrDefault(ThemeMode.LIGHT)) }
    SectionCard("Оформление") {
        FieldLabel("Тема", "Тёмная — графитовое стекло. Лист тетради и чистовик остаются светлыми, как бумага.")
        Segmented(ThemeMode.entries.map { it to it.label }, mode) {
            mode = it
            repo.setThemeMode(it.name)
        }
    }
}

/** Расходы на нейросеть: сегодня, неделя, месяц и дневной лимит. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageSection(model: String) {
    val context = LocalContext.current
    val repo = context.app.settings
    val usage = context.app.usage
    var limit by remember { mutableStateOf(repo.dailyLimitUsd()) }
    val log = remember { usage.log() }
    val day = Streaks.day(System.currentTimeMillis())
    val today = UsageLog.today(log, day)
    val price = Pricing.of(model)
    SectionCard("Расходы") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Сегодня", UsageLog.money(today.usd), "${today.requests} ${ruPlural(today.requests, "запрос", "запроса", "запросов")}", Modifier.weight(1f))
            Stat("7 дней", UsageLog.money(UsageLog.total(log, day, 7)), null, Modifier.weight(1f))
            Stat("30 дней", UsageLog.money(UsageLog.total(log, day, 30)), null, Modifier.weight(1f))
        }
        if (limit > 0) {
            val share = (today.usd / limit).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Brand.Line)) {
                Box(Modifier.fillMaxWidth(share).height(6.dp).clip(CircleShape).background(if (share >= 1f) Brand.Error else Brand.Ink))
            }
        }
        FieldLabel(
            "Дневной лимит",
            "Когда за день потрачено больше, наставник вежливо остановится до завтра. Подсказка обычно стоит меньше цента.",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0f to "Без лимита", 0.1f to "$0.10", 0.25f to "$0.25", 0.5f to "$0.50", 1f to "$1", 3f to "$3").forEach { (v, label) ->
                ChipButton(label, selected = limit == v) {
                    limit = v
                    repo.setDailyLimitUsd(v)
                }
            }
        }
        Text(
            "Цена модели $model: вход \$${fmt(price.input)}, ответ \$${fmt(price.output)} за миллион токенов. " +
                "Для Claude токены точные — их сообщает сервер, для остальных сервисов это оценка.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt(v: Double) = if (v < 1) String.format(Locale.US, "%.2f", v) else String.format(Locale.US, "%.0f", v)

@Composable
private fun Stat(title: String, value: String, hint: String?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.Line.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Резервная копия в файл (Диск, «Загрузки», флешка) и восстановление из него. */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var confirmUri by remember { mutableStateOf<Uri?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "Сохраняю…" to Tone.INFO
        scope.launch {
            status = runCatching {
                val n = context.contentResolver.openOutputStream(uri)!!.use { Backup.write(context.app, it) }
                "Готово: в копии $n ${ruPlural(n, "файл", "файла", "файлов")}. API-ключи в неё не попадают." to Tone.OK
            }.getOrElse { "Не получилось сохранить: ${it.message}" to Tone.ERROR }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmUri = uri }
    confirmUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmUri = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Восстановить из копии?") },
            text = { Text("Тетради, чистовики, карточки, прогресс и настройки заменятся тем, что в копии. Потом приложение перезапустится. API-ключ останется прежним.") },
            confirmButton = {
                InkButton("Восстановить", danger = true) {
                    confirmUri = null
                    status = "Восстанавливаю…" to Tone.INFO
                    scope.launch {
                        val ok = runCatching { Backup.restore(context.app) { context.contentResolver.openInputStream(uri) } }.getOrDefault(false)
                        if (ok) Backup.restart(context) else status = "Это не резервная копия «Наставника» или файл повреждён." to Tone.ERROR
                    }
                }
            },
            dismissButton = { SoftButton("Отмена") { confirmUri = null } },
        )
    }
    SectionCard("Резервная копия") {
        FieldLabel(
            "Все записи в одном файле",
            "Тетради, чистовики, задачи с наставником, карточки и прогресс. Сохрани на Диск — и на новом планшете всё вернётся.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Сохранить копию", icon = R.drawable.ic_share) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                save.launch("Наставник-копия-$date.zip")
            }
            SoftButton("Восстановить") { open.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        status?.let { (text, tone) -> StatusLine(text, tone) }
    }
}

/** Обновления: свежая сборка из GitHub ставится поверх, записи сохраняются. */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(context.applicationContext) }
    var status by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    SectionCard("Обновления") {
        FieldLabel("Версия 1.${updater.currentBuild}", "Новые сборки выходят на GitHub. Обновление ставится поверх — тетради и настройки остаются.")
        val f = found
        when {
            progress >= 0f -> {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Brand.Line)) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(CircleShape).background(Brand.Ink))
                }
                Text("Скачиваю… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            f != null -> InkButton("Скачать и установить сборку ${f.build}" + (if (f.sizeBytes > 0) " (${f.sizeBytes / 1_000_000} МБ)" else ""), icon = R.drawable.ic_refresh) {
                if (!updater.canInstall()) {
                    status = "Разреши «Наставнику» устанавливать приложения и нажми ещё раз." to Tone.INFO
                    updater.openInstallPermission()
                    return@InkButton
                }
                progress = 0f
                scope.launch {
                    runCatching { updater.download(f) { progress = it } }
                        .onSuccess { updater.install(it); status = null }
                        .onFailure { status = "Не удалось скачать: ${it.message}" to Tone.ERROR }
                    progress = -1f
                }
            }
            else -> SoftButton(if (checking) "Проверяю…" else "Проверить обновления", icon = R.drawable.ic_refresh) {
                if (checking) return@SoftButton
                checking = true
                scope.launch {
                    runCatching { updater.check() }
                        .onSuccess { info ->
                            found = info
                            status = if (info == null) "Стоит последняя версия." to Tone.OK else null
                        }
                        .onFailure { status = "Не удалось проверить: ${it.message}" to Tone.ERROR }
                    checking = false
                }
            }
        }
        status?.let { (text, tone) -> StatusLine(text, tone) }
    }
}
