package app.nastavnik.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.home.HomeLayout
import app.nastavnik.home.HomeLayout.Item
import app.nastavnik.home.HomeLayout.Size
import app.nastavnik.schedule.Lesson
import app.nastavnik.schedule.ScheduleNow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

/** Блок главной: название, значок, какие размеры можно, только для планшета. */
private data class Widget(val id: String, val title: String, val icon: Int, val sizes: List<Size>, val tabletOnly: Boolean = false)

private val WIDGETS = listOf(
    Widget("evening", "Вечерняя учёба", R.drawable.ic_cards, listOf(Size.M, Size.L)),
    Widget("day", "Пары", R.drawable.ic_calendar, listOf(Size.S, Size.M, Size.L)),
    Widget("plan", "План на день", R.drawable.ic_sparkle, listOf(Size.M, Size.L)),
    Widget("cards", "Карточки", R.drawable.ic_cards, listOf(Size.S, Size.M, Size.L)),
    Widget("alarm", "Экзамен", R.drawable.ic_target, listOf(Size.S, Size.M, Size.L)),
    Widget("grades", "Успеваемость", R.drawable.ic_chart, listOf(Size.S, Size.M, Size.L)),
    Widget("notebooks", "Тетради", R.drawable.ic_notebook, listOf(Size.M, Size.L), tabletOnly = true),
    Widget("mentor", "Наставник", R.drawable.ic_sparkle, listOf(Size.M, Size.L), tabletOnly = true),
    Widget("sections", "Разделы", R.drawable.ic_chart, listOf(Size.M, Size.L), tabletOnly = true),
)

private fun defaults(tablet: Boolean) = if (tablet) listOf(
    Item("evening", Size.L), Item("day", Size.M), Item("plan", Size.M),
    Item("cards", Size.S), Item("alarm", Size.S),
    Item("notebooks", Size.L), Item("mentor", Size.M), Item("sections", Size.M),
) else listOf(
    Item("evening", Size.L), Item("day", Size.L), Item("plan", Size.L),
    Item("cards", Size.S), Item("alarm", Size.S),
    Item("grades", Size.L),
)

/**
 * Главный экран из блоков (конструктор): порядок, размер S/M/L, убрать и добавить — в режиме правки [editing].
 * Раскладка своя у телефона и планшета. [extra] — блоки, которые собирает сам экран (тетради, наставник).
 */
@Composable
fun HomeBoard(
    tablet: Boolean,
    editing: Boolean,
    onDoneEditing: () -> Unit,
    resumeTick: Int,
    go: (String) -> Unit,
    onSchedule: () -> Unit,
    extra: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    val context = LocalContext.current
    val app = context.app
    val catalog = remember(tablet) { WIDGETS.filter { tablet || !it.tabletOnly } }
    val known = remember(catalog) { catalog.map { it.id }.toSet() }
    var items by remember(tablet) { mutableStateOf(HomeLayout.decode(app.settings.homeLayout(tablet), known, defaults(tablet))) }
    fun save(next: List<Item>) {
        items = next
        app.settings.setHomeLayout(tablet, if (next.isEmpty()) "-" else HomeLayout.encode(next))
    }

    // Данные дня — общие для блоков.
    val version by app.schedule.version.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now(), resumeTick) { while (true) { value = LocalDateTime.now(); delay(30_000) } }
    val lessons by produceState<List<Lesson>?>(null, version, resumeTick, now.toLocalDate()) { value = app.schedule.day(LocalDate.now()) }
    val tomorrow by produceState<List<Lesson>>(emptyList(), version, resumeTick) { value = app.schedule.day(LocalDate.now().plusDays(1)) }
    val nowMs = remember(resumeTick) { System.currentTimeMillis() }
    val due by remember(nowMs) { app.db.cards().observeDueCount(nowMs) }.collectAsStateWithLifecycle(initialValue = 0)
    val hasGroup = app.settings.scheduleGroup().isNotBlank()
    val exam = remember(resumeTick) { app.study.nextExam() }

    @Composable
    fun content(item: Item, m: Modifier) {
        val small = item.size == Size.S
        when (item.id) {
            "evening" -> Box(m) { EveningCard(resumeTick, now, go) }
            "day" -> if (small) {
                val st = remember(lessons, now) { ScheduleNow.at(lessons.orEmpty(), now) }
                val l = st.current ?: st.next
                HomeTile(m, R.drawable.ic_calendar, l?.start ?: "—", l?.let { listOfNotNull(it.discipline, it.classroom).joinToString(" · ") } ?: "пар больше нет", st.current != null, onSchedule)
            } else Box(m) { DayCard(lessons, tomorrow, now, hasGroup, onSchedule = onSchedule) }
            "plan" -> Box(m) { PlanCard(resumeTick) }
            "cards" -> CardsTile(m, due, go)
            "alarm" -> ExamTile(m, exam, go)
            "grades" -> if (small) {
                HomeTile(m, R.drawable.ic_chart, "Оценки", "успеваемость", false, { go(MainActivity.SCREEN_GRADES) })
            } else Box(m) { GradesCard(onOpen = { go(MainActivity.SCREEN_GRADES) }) }
            "sections" -> Box(m) { SectionsGrid(go, columns = if (item.size == Size.L && tablet) 5 else 3, items = TABLET_SECTIONS) }
            else -> Box(m) { extra[item.id]?.invoke() }
        }
    }

    val columns = HomeLayout.columns(tablet)
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Правка — панель сверху, чтобы сразу было видно, что делать.
        if (editing) AddPanel(
            hidden = catalog.filter { w -> items.none { it.id == w.id } },
            onAdd = { w -> save(HomeLayout.add(items, w.id, w.sizes.last())) },
            onReset = { save(defaults(tablet)) },
            onDone = onDoneEditing,
        )
        HomeLayout.rows(items, columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var used = 0
                row.forEach { item ->
                    val span = HomeLayout.span(item.size, columns)
                    used += span
                    val cell = Modifier.weight(span.toFloat())
                    if (!editing) {
                        content(item, cell)
                    } else {
                        val i = items.indexOf(item)
                        val w = catalog.first { it.id == item.id }
                        Box(cell) {
                            Box(Modifier.alpha(0.55f)) { content(item, Modifier.fillMaxWidth()) }
                            // Пока правишь — блок не нажимается, только кнопки правки.
                            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) {})
                            EditControls(
                                Modifier.align(Alignment.TopEnd).padding(6.dp),
                                size = item.size,
                                onSize = {
                                    val next = w.sizes[(w.sizes.indexOf(item.size) + 1) % w.sizes.size]
                                    save(HomeLayout.resize(items, i, next))
                                },
                                onEarlier = { save(HomeLayout.move(items, i, -1)) },
                                onLater = { save(HomeLayout.move(items, i, 1)) },
                                onRemove = { save(HomeLayout.remove(items, i)) },
                            )
                        }
                    }
                }
                if (used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
            }
        }
    }
}

/** Кнопки правки на блоке: размер (S/M/L по кругу), раньше, позже, убрать. */
@Composable
private fun EditControls(modifier: Modifier, size: Size, onSize: () -> Unit, onEarlier: () -> Unit, onLater: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.Paper.copy(alpha = 0.92f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Brand.Accent).clickable(onClick = onSize).semantics { contentDescription = "Размер ${size.name}" },
            contentAlignment = Alignment.Center,
        ) { Text(size.name, style = MaterialTheme.typography.labelLarge, color = Brand.OnAccent) }
        SmallIcon(R.drawable.ic_chevron_left, "Раньше", onEarlier)
        SmallIcon(R.drawable.ic_chevron, "Позже", onLater)
        SmallIcon(R.drawable.ic_close, "Убрать", onRemove)
    }
}

@Composable
private fun SmallIcon(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(16.dp)) }
}

/** Сверху в режиме правки: убранные блоки — добавить обратно; «Как было» и «Готово». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPanel(hidden: List<Widget>, onAdd: (Widget) -> Unit, onReset: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), 10.dp, strong = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Добавить блок", style = MaterialTheme.typography.titleMedium)
        if (hidden.isEmpty()) Text("Все блоки уже на экране.", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hidden.forEach { w -> ChipButton("+ ${w.title}") { onAdd(w) } }
        }
        Text(
            "S — плитка, M — половина, L — во всю ширину. Нажми на букву, чтобы сменить размер; ‹ › — порядок; × — убрать.",
            style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Как было", onClick = onReset)
            InkButton("Готово", onClick = onDone)
        }
    }
}
