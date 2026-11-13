package app.nastavnik.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.nastavnik.ai.Drawing
import app.nastavnik.ai.DrawingKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Геометрия рисунка в координатах текущей поверхности. */
object MentorDrawing {
    fun draw(canvas: Canvas, rect: RectF, drawing: Drawing, paint: Paint, unit: Float) {
        if (drawing.kind == DrawingKind.ELLIPSE) {
            canvas.drawOval(rect, paint)
            return
        }
        val x1 = if (drawing.reverseX) rect.right else rect.left
        val x2 = if (drawing.reverseX) rect.left else rect.right
        val y1 = if (drawing.reverseY) rect.bottom else rect.top
        val y2 = if (drawing.reverseY) rect.top else rect.bottom
        canvas.drawLine(x1, y1, x2, y2, paint)
        if (drawing.kind == DrawingKind.ARROW) {
            val angle = atan2(y2 - y1, x2 - x1)
            val length = 12f * unit
            for (side in listOf(-0.55f, 0.55f)) {
                canvas.drawLine(x2, y2, x2 - length * cos(angle + side), y2 - length * sin(angle + side), paint)
            }
        }
    }
}
