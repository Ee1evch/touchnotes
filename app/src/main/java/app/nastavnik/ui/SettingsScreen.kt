package app.nastavnik.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.ai.ModelPresets
import app.nastavnik.ai.Strictness
import app.nastavnik.app
import app.nastavnik.data.BubbleSide
import app.nastavnik.data.SettingsRepo
import app.nastavnik.notes.NoteApps
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onAppearance: () -> Unit = {}, onOnboarding: () -> Unit = {}, onLks: () -> Unit = {}, go: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = context.app.settings
    val s by remember { repo.flow() }.collectAsStateWithLifecycle(initialValue = repo.load())
    val tablet = remember(s.deviceRole) { context.app.isTablet() }

    ScreenScaffold(title = "Настройки", onBack = onBack) {
        AppearanceEntry(onAppearance)

        if (tablet) {
            SectionCard("Нейросеть") {
                ProviderEditor(onChanged = {})
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                FieldLabel("Модель наставника")
                ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.model) {
                    repo.setModel(it, s.provider)
                    if (s.cleanModel == s.model) repo.setCleanModel(it, s.provider)
                }
            }

            SectionCard("Наставник") {
                FieldLabel(
                    "Скорость ответа",
                    if (s.fastAnswers) "Быстро — модель меньше размышляет перед ответом."
                    else "Вдумчиво — больше времени на сложные задачи.",
                )
                Segmented(listOf(true to "Быстро", false to "Вдумчиво"), s.fastAnswers) { repo.setFastAnswers(it) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                FieldLabel("Строгость", s.strictness.label)
                Segmented(
                    listOf(Strictness.QUESTIONS_ONLY to "Только вопросы", Strictness.HINTS to "Подсказки", Strictness.DETAILED to "Подробно"),
                    s.strictness,
                ) { repo.setStrictness(it) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                FieldLabel("Следить за решением", "Проверять запись самому, когда перестанешь писать.")
                Segmented(listOf(0 to "Выкл.", 5 to "5 с", 10 to "10 с", 20 to "20 с"), s.watchPauseSec) {
                    repo.setWatchPauseSec(it)
                }
            }

            NotesAppSection()
            UsageSection(s.model)
        }

        DeviceSection(tablet, showRole = false)
        GradesSettingsSection(onLks)
        SyncSection()

        MoreSettings(tablet) {
            if (tablet) {
                SectionCard("Плавающая кнопка и заметки") {
                    var alpha by remember(s.bubbleAlpha) { mutableFloatStateOf(s.bubbleAlpha) }
                    FieldLabel("Непрозрачность кнопки: ${(alpha * 100).roundToInt()}%")
                    AppSlider(
                        value = alpha,
                        onValueChange = { alpha = it },
                        onValueChangeFinished = { repo.setBubbleAlpha(alpha) },
                        valueRange = SettingsRepo.MIN_ALPHA..SettingsRepo.MAX_ALPHA,
                    )
                    var size by remember(s.bubbleSizeDp) { mutableFloatStateOf(s.bubbleSizeDp.toFloat()) }
                    FieldLabel("Размер кнопки: ${size.roundToInt()} dp")
                    AppSlider(
                        value = size,
                        onValueChange = { size = it },
                        onValueChangeFinished = { repo.setBubbleSize(size.roundToInt()) },
                        valueRange = 40f..72f,
                        steps = 7,
                    )
                    FieldLabel("Сторона экрана")
                    Segmented(listOf(BubbleSide.LEFT to "Слева", BubbleSide.RIGHT to "Справа"), s.bubbleSide) { repo.setBubbleSide(it) }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    var fa by remember(s.noteFadeAlpha) { mutableFloatStateOf(s.noteFadeAlpha) }
                    FieldLabel("Прозрачность заметки у стилуса: ${(fa * 100).roundToInt()}%")
                    AppSlider(
                        value = fa,
                        onValueChange = { fa = it },
                        onValueChangeFinished = { repo.setNoteFadeAlpha(fa) },
                        valueRange = 0.15f..0.8f,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    var speak by remember { mutableStateOf(repo.speakHints()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Читать подсказки вслух", "Озвучивать короткую подсказку сразу после ответа.")
                        }
                        AppSwitch(speak) { speak = it; repo.setSpeakHints(it) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FieldLabel("Что видит наставник", "«Весь конспект» отправляет с вопросом и пролистанные страницы тетради.")
                    Segmented(listOf(true to "Весь конспект", false to "Только экран"), s.seeNotebook) { repo.setSeeNotebook(it) }
                }

                SectionCard("Чистовик") {
                    FieldLabel("Модель для чистовика")
                    ModelPicker(s.provider, ModelPresets.mentor(s.provider), s.cleanModel) { repo.setCleanModel(it, s.provider) }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    FieldLabel("Куда отправлять PDF")
                    val targets = remember { NoteApps.pdfTargets(context) }
                    val notes = remember(s) { NoteApps.chosen(context) }
                    RadioRow(
                        s.sendTarget == SettingsRepo.TARGET_AUTO,
                        "В приложение для записей",
                        notes?.let { "Сейчас: ${it.label}" } ?: "Системный выбор",
                    ) { repo.setSendTarget(SettingsRepo.TARGET_AUTO) }
                    RadioRow(s.sendTarget == SettingsRepo.TARGET_CHOOSER, "Каждый раз спрашивать") {
                        repo.setSendTarget(SettingsRepo.TARGET_CHOOSER)
                    }
                    targets.forEach { t ->
                        RadioRow(s.sendTarget == t.encode(), t.label) { repo.setSendTarget(t.encode()) }
                    }
                }
            }

            DeviceRoleSection(tablet)
            ScheduleExtraSection()
            BackupSection()
            UpdateSection()
            AboutSection(tablet, onOnboarding)
        }
    }
}

@Composable
private fun NotesAppSection() {
    val context = LocalContext.current
    val repo = context.app.settings
    val known = remember { NoteApps.installedKnown(context) }
    val all = remember { NoteApps.launchable(context) }
    var pick by remember { mutableStateOf(repo.notesApp()) }
    var picker by remember { mutableStateOf(false) }
    fun choose(pkg: String) {
        pick = pkg
        repo.setNotesApp(pkg)
    }
    SectionCard("Приложение для записей") {
        FieldLabel("Где ты пишешь конспекты", "Поверх него работает кнопка наставника, туда же уходит чистовик.")
        RadioRow(
            pick.isEmpty(),
            "Определять самому",
            known.firstOrNull()?.let { "Сейчас: ${it.label}" } ?: "Приложений для записей не нашлось",
        ) { choose("") }
        known.forEach { a -> RadioRow(pick == a.packageName, a.label) { choose(a.packageName) } }
        all.firstOrNull { it.packageName == pick && known.none { k -> k.packageName == pick } }?.let { a ->
            RadioRow(true, a.label, "выбрано вручную") {}
        }
        SoftButton("Другое приложение…", icon = R.drawable.ic_search) { picker = true }
        if (picker) AppPickerDialog(all, onDismiss = { picker = false }) { a ->
            choose(a.packageName)
            picker = false
        }
    }
}

/** Режим устройства (вид приложения) — в «Дополнительно». */
@Composable
private fun DeviceRoleSection(tablet: Boolean) {
    val repo = LocalContext.current.app.settings
    var role by remember { mutableStateOf(repo.deviceRole()) }
    SectionCard("Режим устройства") {
        FieldLabel(
            "Вид приложения",
            if (tablet) "Планшет: тетради со стилусом, наставник и чистовики." else "Телефон: расписание, оценки и карточки.",
        )
        Segmented(listOf("auto" to "Авто", "phone" to "Телефон", "tablet" to "Планшет"), role) {
            role = it
            repo.setDeviceRole(it)
        }
    }
}

/** Группа английского и фото преподавателей — в «Дополнительно». */
@Composable
private fun ScheduleExtraSection() {
    LanguageSection()
    TeacherPhotosSection()
}

/** «Дополнительно»: раскрывается плавно по нажатию. */
@Composable
private fun MoreSettings(tablet: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 0.75f, stiffness = 500f), label = "moreArrow")
    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .clickable { open = !open }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
            Text(
                if (tablet) "Кнопка, чистовик, расписание, резервная копия, обновления"
                else "Режим устройства, расписание, резервная копия, обновления",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Brand.InkSoft,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = 400f)) + fadeIn(tween(220, delayMillis = 40)),
        exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) + fadeOut(tween(160)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Выбор любого установленного приложения — с поиском по названию. */
@Composable
private fun AppPickerDialog(apps: List<NoteApps.App>, onDismiss: () -> Unit, onPick: (NoteApps.App) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = apps.filter { filter.isBlank() || it.label.contains(filter.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LinkText("Закрыть", onClick = onDismiss) },
        title = { Text("Где ты пишешь конспекты") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(filter, { filter = it }, "Поиск: notes, onenote, squid…")
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.packageName }) { a ->
                        Text(
                            a.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(a) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun AboutSection(tablet: Boolean, onOnboarding: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    SectionCard("О приложении") {
        if (tablet) LinkText("Пройти первую настройку заново", onClick = onOnboarding)
        Text(
            "Приложение неофициальное и не связано с Университетом «Сириус».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinkText(if (open) "Скрыть лицензию" else "Лицензии компонентов") { open = !open }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 400f)) + fadeIn(tween(180)),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) + fadeOut(tween(140)),
        ) {
            val text = remember { runCatching { context.assets.open("licenses/lecturial-2.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
