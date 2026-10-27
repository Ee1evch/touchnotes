package app.nastavnik.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {
    const val MAX_LONG_SIDE = 1500
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

    /** Уменьшает так, чтобы длинная сторона была не больше [maxSide]. Увеличивать не будем. */
    fun scaleDown(src: Bitmap, maxSide: Int = MAX_LONG_SIDE): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxSide) return src
        val k = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    fun toJpeg(src: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Полный конвейер: обрезка → уменьшение → JPEG. */
    fun prepare(full: Bitmap, cropRect: Rect): ByteArray {
        val cropped = crop(full, cropRect)
        val scaled = scaleDown(cropped)
        val bytes = toJpeg(scaled)
        if (scaled !== cropped && scaled !== full) scaled.recycle()
        if (cropped !== full) cropped.recycle()
        return bytes
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
