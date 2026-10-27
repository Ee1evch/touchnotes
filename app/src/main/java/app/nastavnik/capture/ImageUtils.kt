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
import kotlin.math.roundToInt
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
        if (lines.lines.isEmpty()) return Prepared(toJpeg(src), w, h, used, 0, lines)
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
        return Prepared(jpeg, w, h, used, GUTTER, lines)
    }

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

    /** Маленькая превьюшка для панели чистовика. */
    fun thumbnail(jpeg: ByteArray, maxSide: Int): Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decode)
    }
}
