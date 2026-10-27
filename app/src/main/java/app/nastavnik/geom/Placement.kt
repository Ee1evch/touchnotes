package app.nastavnik.geom

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Прямоугольник в пикселях экрана (без зависимостей от Android — чтобы проверять юнит-тестами). */
data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = if (width > 0 && height > 0) width.toLong() * height else 0L

    fun offset(dx: Int, dy: Int) = IRect(left + dx, top + dy, right + dx, bottom + dy)
    fun inset(d: Int) = IRect(left + d, top + d, right - d, bottom - d)
    fun intersects(o: IRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun overlapArea(o: IRect): Long {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0L
    }

    /** Сдвигает прямоугольник внутрь [bounds], не меняя размера (если помещается). */
    fun clampInto(bounds: IRect): IRect {
        val dx = when {
            width >= bounds.width -> bounds.left - left
            left < bounds.left -> bounds.left - left
            right > bounds.right -> bounds.right - right
            else -> 0
        }
        val dy = when {
            height >= bounds.height -> bounds.top - top
            top < bounds.top -> bounds.top - top
            bottom > bounds.bottom -> bounds.bottom - bottom
            else -> 0
        }
        return offset(dx, dy)
    }

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** С какой стороны от отмеченного места стоит заметка (туда же смотрит её «хвостик»). */
enum class Side { RIGHT, LEFT, BELOW, ABOVE, NONE }

data class Placement(val rect: IRect, val side: Side)

/**
 * Выбирает, куда поставить заметку наставника: рядом с отмеченным местом, но не поверх него,
 * не поверх других заметок и по возможности на пустом месте листа.
 */
object CalloutPlacer {

    /**
     * @param area где можно ставить (экран без системных панелей)
     * @param anchor отмеченное место; null — заметка без привязки
     * @param ink доля «чернил» в прямоугольнике 0…1 (пустое место предпочтительнее)
     * @param avoid уже занятые места: другие заметки, кнопка
     */
    fun place(
        area: IRect,
        anchor: IRect?,
        width: Int,
        height: Int,
        gap: Int,
        avoid: List<IRect> = emptyList(),
        ink: (IRect) -> Float = { 0f },
    ): Placement {
        val w = min(width, area.width)
        val h = min(height, area.height)
        val cands = mutableListOf<Placement>()
        if (anchor == null) {
            // Без привязки — в углах и по краям, справа предпочтительнее (там обычно поле).
            val xs = listOf(area.right - w - gap, area.left + gap, area.centerX - w / 2)
            val ys = listOf(area.top + gap, area.centerY - h / 2, area.bottom - h - gap)
            for (x in xs) for (y in ys) cands += Placement(IRect(x, y, x + w, y + h).clampInto(area), Side.NONE)
        } else {
            val a = anchor
            val alignY = listOf(a.top, a.centerY - h / 2, a.bottom - h)
            val alignX = listOf(a.left, a.centerX - w / 2, a.right - w)
            for (y in alignY) {
                cands += Placement(IRect(a.right + gap, y, a.right + gap + w, y + h), Side.RIGHT)
                cands += Placement(IRect(a.left - gap - w, y, a.left - gap, y + h), Side.LEFT)
            }
            for (x in alignX) {
                cands += Placement(IRect(x, a.bottom + gap, x + w, a.bottom + gap + h), Side.BELOW)
                cands += Placement(IRect(x, a.top - gap - h, x + w, a.top - gap), Side.ABOVE)
            }
            // Чуть дальше от места — если рядом всё исписано.
            val far = gap * 4
            cands += Placement(IRect(a.right + far, a.top, a.right + far + w, a.top + h), Side.RIGHT)
            cands += Placement(IRect(a.left, a.bottom + far, a.left + w, a.bottom + far + h), Side.BELOW)
            for (i in cands.indices) cands[i] = cands[i].copy(rect = cands[i].rect.clampInto(area))
        }
        val diag = hypot(area.width.toDouble(), area.height.toDouble()).coerceAtLeast(1.0)
        return cands.withIndex().minBy { (i, p) -> score(p, i, anchor, avoid, ink, diag) }.value
    }

    private fun score(p: Placement, order: Int, anchor: IRect?, avoid: List<IRect>, ink: (IRect) -> Float, diag: Double): Double {
        val r = p.rect
        val area = r.area.coerceAtLeast(1).toDouble()
        var s = 0.0
        if (anchor != null) {
            // Закрыть само место — хуже всего: студент должен видеть, о чём речь.
            s += 1000.0 * r.overlapArea(anchor.inset(-4)) / min(area, anchor.area.coerceAtLeast(1).toDouble())
            val dx = max(0, max(anchor.left - r.right, r.left - anchor.right))
            val dy = max(0, max(anchor.top - r.bottom, r.top - anchor.bottom))
            s += 120.0 * hypot(dx.toDouble(), dy.toDouble()) / diag
        }
        for (o in avoid) s += 500.0 * r.overlapArea(o) / area
        s += 60.0 * ink(r)
        s += order * 0.01 // при равенстве — порядок кандидатов: справа, слева, снизу, сверху
        return s
    }
}
