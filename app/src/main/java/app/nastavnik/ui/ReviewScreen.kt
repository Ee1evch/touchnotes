package app.nastavnik.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.data.CardEntity
import app.nastavnik.study.Leitner
import app.nastavnik.study.ruPlural
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Повторение карточек из чистовиков. Вопрос → «Показать ответ» → «Помню» / «Не помню».
 * Забытая карточка вернётся в конце этой же тренировки.
 */
@Composable
fun ReviewScreen(onBack: () -> Unit, onExample: () -> Unit) {
    val context = LocalContext.current
    val study = context.app.study
    val cards = context.app.db.cards()
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<CardEntity>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var remembered by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var nextDue by remember { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val allCount by remember { cards.observeCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val learned by remember { cards.observeLearned(Leitner.LEARNED_BOX) }.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(reload) {
        val due = cards.due(System.currentTimeMillis(), SESSION_SIZE)
        queue = due
        total = due.size
        remembered = 0
        revealed = false
        nextDue = cards.nextDue(System.currentTimeMillis())
    }

    ScreenScaffold(title = "Повторение", onBack = onBack) {
        val q = queue ?: return@ScreenScaffold
        val card = q.firstOrNull()
        if (card == null) {
            if (allCount == 0) {
                NoCards(onSample = {
                    scope.launch {
                        study.addSample()
                        reload++
                    }
                }, onExample = onExample)
            } else {
                AllDone(total, remembered, learned, allCount, nextDue)
            }
            return@ScreenScaffold
        }

        // Сколько осталось в этой тренировке.
        val done = (total - q.size).coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (total == 0) 0f else done.toFloat() / total)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Brand.Accent),
                )
            }
            Text("$done из $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .softCard(RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    card.topic,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                BoxDots(card.box)
            }
            RichText(card.front, sizePx = 24, center = true, modifier = Modifier.padding(top = 12.dp))
            AnimatedVisibility(revealed, enter = fadeIn() + expandVertically()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(Modifier.widthIn(max = 240.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    RichText(card.back, sizePx = 19, center = true)
                }
            }
        }

        fun answer(ok: Boolean) {
            val updated = study.answered(card, ok)
            revealed = false
            // Забытая карточка — в конец очереди: ещё раз в этой же тренировке.
            val rest = q.drop(1)
            queue = if (ok) rest else rest + updated
            if (ok) remembered++
            scope.launch {
                study.save(updated, ok)
                if (queue.isNullOrEmpty()) nextDue = cards.nextDue(System.currentTimeMillis())
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            if (!revealed) {
                InkButton("Показать ответ", modifier = Modifier.widthIn(min = 240.dp)) { revealed = true }
            } else {
                SoftButton("Не помню", modifier = Modifier.widthIn(min = 180.dp), icon = R.drawable.ic_refresh) { answer(false) }
                InkButton("Помню", modifier = Modifier.widthIn(min = 180.dp), icon = R.drawable.ic_check) { answer(true) }
            }
        }
        Text(
            if (!revealed) "Сначала ответь про себя или на листе, потом открой ответ." else intervalHint(card.box),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Подсказка, когда карточка вернётся, если ответить «Помню». */
private fun intervalHint(box: Int): String {
    val next = (box + 1).coerceIn(1, Leitner.MAX_BOX)
    val d = Leitner.INTERVAL_DAYS[next]
    val whenText = if (d == 1) "завтра" else "через $d ${ruPlural(d, "день", "дня", "дней")}"
    return "«Помню» — карточка вернётся $whenText. «Не помню» — ещё раз в этой тренировке."
}

/** Пять точек — в какой коробке карточка: чем больше закрашено, тем лучше выучена. */
@Composable
private fun BoxDots(box: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..Leitner.MAX_BOX).forEach { i ->
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (i <= box) Brand.Accent else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

@Composable
private fun AllDone(total: Int, remembered: Int, learned: Int, all: Int, nextDue: Long?) {
    Column(
        Modifier
            .fillMaxWidth()
            .softCard(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(R.drawable.ic_check, Brand.Ok, 56)
        Text(if (total > 0) "Тренировка закончена" else "На сегодня всё повторено", style = MaterialTheme.typography.titleLarge)
        if (total > 0) {
            Text(
                "Вспомнил $remembered из $total с первого раза.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Выучено $learned из $all ${ruPlural(all, "карточки", "карточек", "карточек")}." +
                (nextDue?.let { " Следующие — " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(it)) + "." } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoCards(onSample: () -> Unit, onExample: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .softCard(color = MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(R.drawable.ic_cards, Brand.Accent, 52)
        Text("Карточки для повторения", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Каждый чистовик сам превращается в карточки: определения, теоремы и формулы из твоих записей. " +
                "Повторяй их по паре минут в день — интервалы растут: завтра, через 3 дня, через неделю, через месяц. " +
                "Так материал остаётся в голове к экзамену.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Попробовать на примере", icon = R.drawable.ic_cards, onClick = onSample)
            SoftButton("Пример чистовика", onClick = onExample)
        }
    }
}

private const val SESSION_SIZE = 30
