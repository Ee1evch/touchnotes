package app.nastavnik.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.ReminderEntity
import app.nastavnik.study.Alarms
import app.nastavnik.study.Repeats
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ruLocale = Locale.forLanguageTag("ru")

/** «Сегодня, 18:00», «Завтра, 9:00», «пт, 7 нояб., 12:30». */
fun reminderTime(at: Long, now: LocalDate = LocalDate.now()): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
    val time = t.format(DateTimeFormatter.ofPattern("H:mm"))
    return when (t.toLocalDate()) {
        now -> "Сегодня, $time"
        now.plusDays(1) -> "Завтра, $time"
        now.minusDays(1) -> "Вчера, $time"
        else -> t.format(DateTimeFormatter.ofPattern("EE, d MMM", ruLocale)) + ", $time"
    }
}

/** Изменили напоминания — переставить будильники и отправить на другие устройства. */
fun remindersChanged(context: android.content.Context) {
    Alarms.rescheduleAll(context)
    context.app.sync.soon()
}

@Composable
fun RemindersScreen(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val db = context.app.db
    val scope = rememberCoroutineScope()
    val all by remember { db.reminders().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    var editing by remember { mutableStateOf<ReminderEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    fun save(r: ReminderEntity) = scope.launch { db.reminders().put(r); remindersChanged(context) }
    fun remove(r: ReminderEntity) = scope.launch { db.reminders().delete(r.uid); remindersChanged(context) }

    ScreenScaffold(title = "Напоминания", onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Создай здесь или на планшете — сработает на всех устройствах.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft, modifier = Modifier.weight(1f),
            )
            InkButton("Новое", icon = R.drawable.ic_plus) { creating = true }
        }
        val list = all
        if (list == null) return@ScreenScaffold
        val open = list.filter { !it.done }
        val done = list.filter { it.done }
        if (open.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), 8.dp).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(30.dp))
                Text("Ничего не горит", style = MaterialTheme.typography.titleMedium)
                Text("Добавь дедлайн, лабу или «взять зачётку»", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            }
        }
        open.forEach { r ->
            ReminderRow(r, onToggle = { save(r.copy(done = true)) }, onClick = { editing = r })
        }
        if (done.isNotEmpty()) {
            LinkText(if (showDone) "Скрыть выполненные" else "Выполненные · ${done.size}", onClick = { showDone = !showDone })
            AnimatedVisibility(showDone, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done.forEach { r -> ReminderRow(r, onToggle = { save(r.copy(done = false)) }, onClick = { editing = r }) }
                }
            }
        }
    }

    if (creating || editing != null) {
        ReminderDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { save(it); creating = false; editing = null },
            onDelete = editing?.let { e -> { remove(e); editing = null } },
        )
    }
}

/** Строка напоминания: кружок-галочка (с пружинкой), название, когда и повтор. */
@Composable
fun ReminderRow(r: ReminderEntity, onToggle: () -> Unit, onClick: () -> Unit) {
    var checked by remember(r.uid, r.done) { mutableStateOf(r.done) }
    val k by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "check")
    val overdue = !r.done && r.repeat == Repeats.NONE && r.at < System.currentTimeMillis()
    val ring by animateColorAsState(if (checked) Brand.Accent else if (overdue) Brand.Error else Brand.InkSoft, tween(200), label = "ring")
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), 6.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).border(2.dp, ring, CircleShape)
                .clickable {
                    checked = !checked
                    scope.launch { kotlinx.coroutines.delay(260); onToggle() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(28.dp).graphicsLayer { scaleX = k; scaleY = k; alpha = k }.clip(CircleShape).background(Brand.Accent), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = if (r.done) "Вернуть" else "Готово", tint = Brand.OnAccent, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                color = if (checked) Brand.InkSoft else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(reminderTime(r.at), Repeats.labels[r.repeat]?.takeIf { r.repeat != Repeats.NONE }, r.note.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (overdue) Brand.Error else Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderDialog(initial: ReminderEntity?, onDismiss: () -> Unit, onSave: (ReminderEntity) -> Unit, onDelete: (() -> Unit)?) {
    val zone = ZoneId.systemDefault()
    val start = initial?.at?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var at by remember { mutableStateOf(start) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: Repeats.NONE) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (initial == null) "Новое напоминание" else "Напоминание") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(title, { title = it }, label = "О чём напомнить", placeholder = "Сдать лабу по физике")
                AppTextField(note, { note = it }, label = "Заметка (необязательно)")
                // Быстрый выбор — без календаря.
                val today = LocalDate.now()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val quick = listOf(
                        "Через час" to LocalDateTime.now().plusHours(1).withSecond(0).withNano(0),
                        "Сегодня в 20:00" to today.atTime(20, 0),
                        "Завтра в 9:00" to today.plusDays(1).atTime(9, 0),
                    ).filter { it.second.isAfter(LocalDateTime.now()) }
                    quick.forEach { (label, t) -> ChipButton(label, selected = at == t) { at = t } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(at.format(DateTimeFormatter.ofPattern("d MMMM", ruLocale)), icon = R.drawable.ic_calendar) { pickDate = true }
                    SoftButton(at.format(DateTimeFormatter.ofPattern("H:mm")), icon = R.drawable.ic_timer) { pickTime = true }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Repeats.labels.forEach { (k, label) -> ChipButton(label, selected = repeat == k) { repeat = k } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val ms = at.atZone(zone).toInstant().toEpochMilli()
                onSave((initial ?: ReminderEntity(title = title.trim(), at = ms)).copy(title = title.trim(), note = note.trim(), at = ms, repeat = repeat, done = false))
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить", color = Brand.Error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = at.toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> at = LocalDateTime.of(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate(), at.toLocalTime()) }
                    pickDate = false
                }) { Text("Готово") }
            },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = MaterialTheme.colorScheme.surface,
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { at = LocalDateTime.of(at.toLocalDate(), LocalTime.of(state.hour, state.minute)); pickTime = false }) { Text("Готово") } },
        )
    }
}

