package app.nastavnik.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nastavnik.R
import app.nastavnik.campus.Maneuver
import app.nastavnik.campus.Navigation
import app.nastavnik.campus.Route
import kotlin.math.roundToInt

/** Стрелка манёвра, как в навигаторе: прямо, повороты, разворот, лестница, лифт, финиш. */
@Composable
fun ManeuverIcon(kind: Maneuver.Kind, color: Color, modifier: Modifier = Modifier.size(44.dp)) {
    Canvas(modifier.semantics { contentDescription = kind.name }) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            Maneuver.Kind.START, Maneuver.Kind.STRAIGHT -> arrow(Offset(s / 2, s * 0.88f), Offset(s / 2, s * 0.14f), color, stroke)
            Maneuver.Kind.LEFT, Maneuver.Kind.RIGHT, Maneuver.Kind.SLIGHT_LEFT, Maneuver.Kind.SLIGHT_RIGHT,
            Maneuver.Kind.SHARP_LEFT, Maneuver.Kind.SHARP_RIGHT -> {
                val right = kind == Maneuver.Kind.RIGHT || kind == Maneuver.Kind.SLIGHT_RIGHT || kind == Maneuver.Kind.SHARP_RIGHT
                val angle = when (kind) {
                    Maneuver.Kind.SLIGHT_LEFT, Maneuver.Kind.SLIGHT_RIGHT -> 45f
                    Maneuver.Kind.SHARP_LEFT, Maneuver.Kind.SHARP_RIGHT -> 135f
                    else -> 90f
                }
                scale(if (right) 1f else -1f, 1f) {
                    val bend = Offset(s * 0.38f, s * 0.5f)
                    val p = Path().apply { moveTo(s * 0.38f, s * 0.9f); lineTo(bend.x, bend.y) }
                    drawPath(p, color, style = stroke)
                    val len = s * 0.38f
                    val rad = Math.toRadians((90 - angle).toDouble())
                    val end = Offset(bend.x + (len * kotlin.math.cos(rad)).toFloat(), bend.y - (len * kotlin.math.sin(rad)).toFloat())
                    arrow(bend, end, color, stroke)
                }
            }
            Maneuver.Kind.UTURN -> {
                val p = Path().apply {
                    moveTo(s * 0.62f, s * 0.9f); lineTo(s * 0.62f, s * 0.38f)
                    cubicTo(s * 0.62f, s * 0.12f, s * 0.3f, s * 0.12f, s * 0.3f, s * 0.38f)
                }
                drawPath(p, color, style = stroke)
                arrow(Offset(s * 0.3f, s * 0.38f), Offset(s * 0.3f, s * 0.62f), color, stroke)
            }
            Maneuver.Kind.UP, Maneuver.Kind.DOWN -> {
                // Ступеньки и стрелка вверх/вниз.
                val p = Path().apply {
                    moveTo(s * 0.12f, s * 0.86f); lineTo(s * 0.36f, s * 0.86f); lineTo(s * 0.36f, s * 0.64f)
                    lineTo(s * 0.58f, s * 0.64f); lineTo(s * 0.58f, s * 0.42f); lineTo(s * 0.8f, s * 0.42f)
                }
                drawPath(p, color, style = stroke)
                if (kind == Maneuver.Kind.UP) arrow(Offset(s * 0.3f, s * 0.42f), Offset(s * 0.62f, s * 0.12f), color, stroke)
                else arrow(Offset(s * 0.62f, s * 0.12f), Offset(s * 0.3f, s * 0.42f), color, stroke)
            }
            Maneuver.Kind.LIFT -> {
                drawRoundRect(color, Offset(s * 0.2f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.76f), androidx.compose.ui.geometry.CornerRadius(s * 0.1f), style = stroke)
                arrow(Offset(s / 2, s * 0.68f), Offset(s / 2, s * 0.32f), color, Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Maneuver.Kind.ARRIVE -> {
                val p = Path().apply {
                    moveTo(s / 2, s * 0.92f)
                    cubicTo(s * 0.3f, s * 0.62f, s * 0.2f, s * 0.48f, s * 0.2f, s * 0.36f)
                    cubicTo(s * 0.2f, s * 0.18f, s * 0.34f, s * 0.06f, s / 2, s * 0.06f)
                    cubicTo(s * 0.66f, s * 0.06f, s * 0.8f, s * 0.18f, s * 0.8f, s * 0.36f)
                    cubicTo(s * 0.8f, s * 0.48f, s * 0.7f, s * 0.62f, s / 2, s * 0.92f)
                    close()
                }
                drawPath(p, color)
                drawCircle(Color.White.copy(alpha = 0.9f), s * 0.11f, Offset(s / 2, s * 0.36f))
            }
        }
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, stroke: Stroke) {
    drawLine(color, from, to, stroke.width, StrokeCap.Round)
    val ang = Math.toDegrees(kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
    val head = size.minDimension * 0.2f
    rotate(ang, to) {
        val p = Path().apply { moveTo(to.x - head, to.y - head * 0.75f); lineTo(to.x, to.y); lineTo(to.x - head, to.y + head * 0.75f) }
        drawPath(p, color, style = stroke)
    }
}

/** Плашка навигации сверху: стрелка, «через 15 м», что сделать, где, и следующий шаг поменьше. */
@Composable
fun NavigationBanner(steps: List<Maneuver>, step: Int, toNext: Double?, onPrev: () -> Unit, onNext: () -> Unit) {
    val next = steps.getOrNull(step + 1)
    val cur = steps.getOrNull(step) ?: return
    // Что показывать крупно: следующий манёвр (куда сейчас идём), а на финише — «ты на месте».
    val shown = next ?: cur
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn(tween(220, easing = Motion.Decelerate)) + slideInVertically(tween(320, easing = Motion.Decelerate)) { it / 4 }) togetherWith fadeOut(tween(120)) },
            label = "navStep",
        ) { _ ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brand.Accent).padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ManeuverIcon(shown.kind, Brand.OnAccent, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    val dist = if (next != null) (toNext ?: cur.distance) else null
                    if (dist != null) Text("через ${Navigation.meters(dist)}", style = MaterialTheme.typography.titleMedium, color = Brand.OnAccent.copy(alpha = 0.85f))
                    Text(shown.title, style = MaterialTheme.typography.headlineSmall, color = Brand.OnAccent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (shown.detail.isNotBlank()) Text(shown.detail, style = MaterialTheme.typography.bodyMedium, color = Brand.OnAccent.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = step > 0, onClick = onPrev), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_chevron_left), "Предыдущий шаг", tint = Brand.OnAccent.copy(alpha = if (step > 0) 1f else 0.35f), modifier = Modifier.size(22.dp).graphicsRotate(90f))
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = step < steps.lastIndex, onClick = onNext), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_chevron_left), "Следующий шаг", tint = Brand.OnAccent.copy(alpha = if (step < steps.lastIndex) 1f else 0.35f), modifier = Modifier.size(22.dp).graphicsRotate(-90f))
                    }
                }
            }
        }
        // «Затем»: шаг после следующего — небольшая плашка под основной.
        steps.getOrNull(step + 2)?.let { then ->
            Row(
                Modifier.padding(start = 14.dp, top = 6.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Accent.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Затем", style = MaterialTheme.typography.labelMedium, color = Brand.OnAccent.copy(alpha = 0.85f))
                ManeuverIcon(then.kind, Brand.OnAccent, Modifier.size(20.dp))
            }
        }
    }
}

private fun Modifier.graphicsRotate(deg: Float) = this.graphicsLayer { rotationZ = deg }

/** Нижняя панель навигации: сколько осталось, «Все шаги» и «Завершить». */
@Composable
fun NavigationBottom(route: Route, steps: List<Maneuver>, step: Int, left: Double?, onStep: (Int) -> Unit, onFinish: () -> Unit) {
    var list by remember { mutableStateOf(false) }
    val meters = left ?: route.meters
    val minutes = kotlin.math.max(1, (meters / 1.2 / 60).roundToInt())
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().glass(RoundedCornerShape(28.dp), 14.dp, strong = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$minutes мин", style = MaterialTheme.typography.headlineSmall, color = Brand.Ok)
                Text("${Navigation.meters(meters)} · шаг ${step + 1} из ${steps.size}", style = MaterialTheme.typography.bodyMedium, color = Brand.InkSoft)
            }
            SoftButton(if (list) "Скрыть" else "Все шаги") { list = !list }
            Spacer8()
            InkButton("Завершить", danger = true, onClick = onFinish)
        }
        androidx.compose.animation.AnimatedVisibility(list) {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                itemsIndexed(steps) { i, m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onStep(i) }
                            .background(if (i == step) Brand.Accent.copy(alpha = 0.1f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ManeuverIcon(m.kind, if (i == step) Brand.Accent else MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.detail.isNotBlank()) Text(m.detail, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (i < steps.lastIndex) HorizontalDivider(Modifier.padding(start = 46.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
