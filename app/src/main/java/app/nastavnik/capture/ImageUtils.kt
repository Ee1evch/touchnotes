package app.nastavnik.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import android.media.Image
import app.nastavnik.geom.InkLines
import app.nastavnik.geom.LineMap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

object ImageUtils {
    const val MAX_LONG_SIDE = 1500

    /** Не больше 1,15 Мп: тогда Claude не уменьшает снимок сам и координаты заметок совпадают с нашими. */
    const val MAX_PIXELS = 1_150_000
    const val JPEG_QUALITY = 85

    /** Кадр из ImageReader (RGBA_8888) → Bitmap. Учитывает выравнивание строк (rowStride). */
    fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val buffer = plane.buffer
        buffer.rewind()
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return exact
    }

    /** Безопасная обрезка: прямоугольник прижимается к границам картинки. */
    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val r = Rect(rect)
        if (!r.intersect(0, 0, src.width, src.height) || r.width() < 8 || r.height() < 8) return src
        if (r.left == 0 && r.top == 0 && r.width() == src.width && r.height() == src.height) return src
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    /** Уменьшает так, чтобы длинная сторона была не больше [maxSide], а площадь — не больше [maxPixels]. Увеличивать не будем. */
    fun scaleDown(src: Bitmap, maxSide: Int = MAX_LONG_SIDE, maxPixels: Int = MAX_PIXELS): Bitmap {
        val k = scaleFactor(src.width, src.height, maxSide, maxPixels)
        if (k >= 1f) return src
        // Вниз, а не до ближайшего: иначе площадь может чуть превысить предел.
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
    }

    fun scaleFactor(w: Int, h: Int, maxSide: Int = MAX_LONG_SIDE, maxPixels: Int = MAX_PIXELS): Float {
        val bySide = maxSide.toFloat() / max(w, h)
        val byArea = sqrt(maxPixels.toDouble() / (w.toDouble() * h)).toFloat()
        return minOf(1f, bySide, byArea)
    }

    fun toJpeg(src: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Готовый снимок. [width]×[height] — сама запись (без поля), [gutter] — ширина поля с номерами строк слева,
     * [lines] — найденные строки (в пикселях записи).
     */
    class Prepared(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val screenRect: Rect,
        val gutter: Int = 0,
        val lines: LineMap = LineMap(emptyList(), width, height),
        /** Яркость пикселей записи — чтобы потом найти отмеченную строку после увеличения страницы. */
        val luma: IntArray? = null,
    )

    /** Ширина поля с номерами строк слева от снимка. */
    private const val GUTTER = 44

    /**
     * Полный конвейер: обрезка → уменьшение → JPEG. Возвращает и то, какую часть экрана показывает снимок.
     * [numberLines] — найти строки записей и подписать их номерами на поле слева (для наставника; для чистовика не нужно).
     */
    fun prepare(full: Bitmap, cropRect: Rect, numberLines: Boolean = false): Prepared {
        val cropped = crop(full, cropRect)
        val used = if (cropped === full) Rect(0, 0, full.width, full.height) else Rect(cropRect).apply { intersect(0, 0, full.width, full.height) }
        var k = scaleFactor(cropped.width, cropped.height)
        // Поле с номерами тоже идёт в 1,15 Мп: иначе Claude уменьшит снимок сам и пиксели разъедутся.
        if (numberLines) while (k > 0.2f && (cropped.width * k + GUTTER) * (cropped.height * k) > MAX_PIXELS) k *= 0.99f
        val scaled = if (k >= 1f) cropped else Bitmap.createScaledBitmap(cropped, (cropped.width * k).toInt().coerceAtLeast(1), (cropped.height * k).toInt().coerceAtLeast(1), true)
        val result = if (numberLines) numbered(scaled, used) else Prepared(toJpeg(scaled), scaled.width, scaled.height, used)
        if (scaled !== cropped && scaled !== full) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        return result
    }

    private fun numbered(src: Bitmap, used: Rect): Prepared {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val lines = InkLines.detect(px, w, h)
        if (lines.lines.isEmpty()) return Prepared(toJpeg(src), w, h, used, 0, lines, px)
        val out = Bitmap.createBitmap(w + GUTTER, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#EEF1F6"))
        c.drawBitmap(src, GUTTER.toFloat(), 0f, null)
        c.drawRect(GUTTER - 2f, 0f, GUTTER.toFloat(), h.toFloat(), Paint().apply { color = Color.parseColor("#C8D1DE") })
        val size = (lines.lineHeight * 0.7f).coerceIn(12f, 20f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A56DB")
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }
        var lastY = -1000f
        for (l in lines.lines) {
            val y = l.centerY + size * 0.36f
            // Строки слишком тесно — номер не помещается, пропускаем (модель тогда даст рамку).
            if (y - lastY < size * 0.95f) continue
            c.drawText(l.number.toString(), GUTTER - 7f, y, paint)
            lastY = y
        }
        val jpeg = toJpeg(out)
        out.recycle()
        return Prepared(jpeg, w, h, used, GUTTER, lines, px)
    }

    /**
     * Свежий кадр области экрана [area] → яркость и строки (длинная сторона до 1600 px).
     * [scale] — во сколько раз уменьшено: пиксель кадра = пиксель экрана × scale.
     */
    class Page(val image: app.nastavnik.geom.PageImage, val scale: Float)

    fun page(full: Bitmap, area: Rect): Page {
        val cropped = crop(full, area)
        val k = minOf(1f, 1600f / maxOf(cropped.width, cropped.height))
        val scaled = if (k >= 1f) cropped else Bitmap.createScaledBitmap(cropped, (cropped.width * k).toInt().coerceAtLeast(1), (cropped.height * k).toInt().coerceAtLeast(1), true)
        val w = scaled.width
        val h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        if (scaled !== cropped && scaled !== full) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        return Page(app.nastavnik.geom.PageImage(px, w, h, InkLines.detect(px, w, h)), k)
    }

    /**
     * Страница конспекта из кадра экрана: область записей [area] в двух размерах — [NotebookShot.image] для
     * чистовика (до 1,15 Мп) и [NotebookShot.small] для вопросов наставнику (до 0,55 Мп, дешевле).
     * Наши окна, попавшие в кадр ([cover], в координатах экрана), закрашиваются цветом листа.
     */
    class NotebookShot(val image: ByteArray, val small: ByteArray)

    fun notebookShot(full: Bitmap, area: Rect, cover: List<Rect> = emptyList()): NotebookShot {
        val cropped = crop(full, area)
        val own = if (cover.isEmpty()) cropped else cropped.copy(Bitmap.Config.ARGB_8888, true).also { b ->
            val c = Canvas(b)
            val paint = Paint()
            val ox = if (cropped === full) 0 else area.left.coerceAtLeast(0)
            val oy = if (cropped === full) 0 else area.top.coerceAtLeast(0)
            for (r in cover) {
                val rr = Rect(r.left - ox, r.top - oy, r.right - ox, r.bottom - oy)
                if (!rr.intersect(0, 0, b.width, b.height)) continue
                // Цвет листа — чуть левее или выше окна; у самого края — белый.
                val sx = (rr.left - 4).takeIf { it >= 0 } ?: (rr.right + 4).coerceAtMost(b.width - 1)
                val sy = rr.centerY().coerceIn(0, b.height - 1)
                paint.color = b.getPixel(sx, sy)
                c.drawRect(rr, paint)
            }
        }
        fun jpegAt(maxPixels: Int, quality: Int): ByteArray {
            val scaled = scaleDown(own, MAX_LONG_SIDE, maxPixels)
            val bytes = toJpeg(scaled, quality)
            if (scaled !== own) scaled.recycle()
            return bytes
        }
        val result = NotebookShot(jpegAt(MAX_PIXELS, 82), jpegAt(SMALL_PIXELS, 78))
        if (own !== cropped) own.recycle()
        if (cropped !== full) cropped.recycle()
        return result
    }

    /** Размер страниц конспекта, которые уходят наставнику вместе с вопросом. */
    const val SMALL_PIXELS = 550_000

    /** Серая уменьшенная копия всего экрана — для слежения за прокруткой и письмом. */
    fun thumb(full: Bitmap): app.nastavnik.geom.Thumb {
        val b = app.nastavnik.geom.ThumbBuilder(full.width, full.height)
        val row = IntArray(full.width)
        for (y in 0 until full.height) {
            full.getPixels(row, 0, full.width, 0, y, full.width, 1)
            b.addRow(y, row)
        }
        return b.build()
    }
}
