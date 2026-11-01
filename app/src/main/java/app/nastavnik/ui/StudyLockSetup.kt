package app.nastavnik.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.nastavnik.study.Alarms
import app.nastavnik.study.LockPrefs
import app.nastavnik.study.StudyLock
import app.nastavnik.study.WakeGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class LaunchApp(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Приложения с рабочего стола (кроме «Наставника» и тех, что открыты всегда). */
private suspend fun launcherApps(context: android.content.Context): List<LaunchApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0L))
    else @Suppress("DEPRECATION") pm.queryIntentActivities(main, 0)
    val always = StudyLock.alwaysAllowed(context)
    list.map { it.activityInfo.packageName to it }
        .distinctBy { it.first }
        .filter { it.first !in always }
        .map { (pkg, ri) ->
            LaunchApp(
                pkg,
                ri.loadLabel(pm).toString(),
                runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/**
 * Настройка учебного замка — один раз и навсегда: во сколько вечером закрывать и какие приложения оставить.
 * Изменить потом нельзя ни выбор, ни время; выключить замок из приложения — тоже.
 */
@Composable
fun StudyLockSetupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var start by remember { mutableIntStateOf(20 * 60) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf(WakeGuard.enabled(context)) }
    LifecycleResumeEffect(Unit) { service = WakeGuard.enabled(context); onPauseOrDispose { } }
    val apps by produceState<List<LaunchApp>?>(null) { value = launcherApps(context) }

    ScreenScaffold(title = "Учебный замок", onBack = onBack) {
        SectionCard(null) {
            Text(
                "Каждый вечер с выбранного времени все приложения закрываются, пока не сделаешь вечернюю учёбу: " +
                    "карточки на сегодня, пара предложений по каждой сегодняшней паре и подготовка к завтрашним. " +
                    "Не сделал вечером — замок остаётся и утром, пока не доделаешь.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Всегда открыты: телефон и экстренные вызовы, SMS, клавиатура, рабочий стол и сам «Наставник». " +
                    "Раз в неделю — 15 экстренных минут.",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
            )
            StatusLine("Время и список приложений выбираются один раз — изменить или выключить замок потом нельзя.", Tone.ERROR)
        }

        SectionCard("1. Служба защиты") {
            if (service) {
                StatusLine("Включена — замок будет работать по-настоящему", Tone.OK)
            } else {
                Text("Без неё приложение не может закрывать другие. Включи «Наставник: защита и учебный замок» в спец. возможностях.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
                Text(
                    "Если пункт серый: Настройки → Приложения → Наставник → ⋮ → «Разрешить ограниченные настройки».",
                    style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
                )
                InkButton("Открыть спец. возможности") {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }

        SectionCard("2. Во сколько закрывать") {
            Segmented(listOf(18 * 60 to "18:00", 19 * 60 to "19:00", 20 * 60 to "20:00", 21 * 60 to "21:00", 22 * 60 to "22:00"), start) { start = it }
        }

        SectionCard("3. Что оставить открытым") {
            Text("Выбери самое нужное: мессенджер для связи, карты, банк. Остальное будет закрыто.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            AppTextField(value = query, onValueChange = { query = it }, label = "Поиск приложения", modifier = Modifier.fillMaxWidth())
            val list = apps
            if (list == null) {
                Text("Загружаю приложения…", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            } else {
                val q = query.trim().lowercase()
                list.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg in chosen }.forEach { a ->
                    val on = a.pkg in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) Brand.Accent.copy(alpha = 0.10f) else Brand.Ink.copy(alpha = 0f))
                            .clickable { chosen = if (on) chosen - a.pkg else chosen + a.pkg }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(36.dp)) { a.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) } }
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppSwitch(on) { chosen = if (it) chosen + a.pkg else chosen - a.pkg }
                    }
                }
            }
        }

        InkButton("Включить замок навсегда", Modifier.fillMaxWidth(), enabled = service) { confirm = true }
        if (!service) Text("Сначала включи службу защиты (шаг 1).", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    }

    if (confirm) {
        var wait by remember { mutableIntStateOf(5) }
        LaunchedEffect(Unit) { while (wait > 0) { delay(1000); wait-- } }
        val names = apps.orEmpty().filter { it.pkg in chosen }.joinToString { it.label }.ifBlank { "ничего, кроме телефона и SMS" }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Включить навсегда?") },
            text = {
                Text("Каждый день с ${"%d:%02d".format(start / 60, start % 60)} закрыто всё, кроме: $names.\n\nИзменить это или выключить замок потом будет нельзя.")
            },
            confirmButton = {
                LinkText(if (wait > 0) "Включить ($wait)" else "Включить") {
                    if (wait > 0) return@LinkText
                    LockPrefs.of(context).configureOnce(start, chosen, LocalDate.now())
                    Alarms.rescheduleAll(context)
                    confirm = false
                    onDone()
                }
            },
            dismissButton = { LinkText("Отмена") { confirm = false } },
        )
    }
}

/** Раздел в настройках: не настроен — кнопка; настроен — что выбрано (только чтение) и состояние. */
@Composable
fun StudyLockSection(go: (String) -> Unit) {
    val context = LocalContext.current
    val lp = remember { LockPrefs.of(context) }
    var service by remember { mutableStateOf(WakeGuard.enabled(context)) }
    LifecycleResumeEffect(Unit) { service = WakeGuard.enabled(context); onPauseOrDispose { } }
    val names by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            lp.allowed.map { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
        }
    }
    SectionCard("Учебный замок") {
        if (!lp.configured) {
            FieldLabel("Закрывать приложения вечером", "Пока не сделаешь вечернюю учёбу: карточки, что было сегодня, подготовка к завтра.")
            InkButton("Настроить") { go(MainActivity.SCREEN_LOCK_SETUP) }
        } else {
            FieldLabel(
                "Каждый день с ${"%d:%02d".format(lp.startMinutes / 60, lp.startMinutes % 60)}",
                "Открыты: ${names.joinToString().ifBlank { "только телефон и SMS" }}. Изменить нельзя.",
            )
            if (!service) StatusLine("Служба защиты выключена — замок не работает. Включи её в спец. возможностях.", Tone.ERROR)
            SoftButton("Вечерняя учёба") { go(MainActivity.SCREEN_EVENING) }
        }
    }
}
