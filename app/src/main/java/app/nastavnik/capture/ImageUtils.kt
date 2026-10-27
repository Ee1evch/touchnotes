package app.nastavnik.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
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
        return Bitmap.createScaledBitmap(src, (src.width * k).roundToInt().coerceAtLeast(1), (src.height * k).roundToInt().coerceAtLeast(1), true)
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

    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int, val screenRect: Rect)

    /** Полный конвейер: обрезка → уменьшение → JPEG. Возвращает и то, какую часть экрана показывает снимок. */
    fun prepare(full: Bitmap, cropRect: Rect): Prepared {
        val cropped = crop(full, cropRect)
        val used = if (cropped === full) Rect(0, 0, full.width, full.height) else Rect(cropRect).apply { intersect(0, 0, full.width, full.height) }
        val scaled = scaleDown(cropped)
        val bytes = toJpeg(scaled)
        val result = Prepared(bytes, scaled.width, scaled.height, used)
        if (scaled !== cropped && scaled !== full) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        return result
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
