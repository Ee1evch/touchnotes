package app.nastavnik.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class CaptureException(message: String) : Exception(message)

/**
 * Снимки экрана через MediaProjection. Виртуальный дисплей создаётся один раз на сессию
 * (Android 14+ не позволяет создавать его повторно), а поверхность ImageReader подключается
 * только на время снимка — так экран не копируется постоянно и не тратит батарею.
 */
class ScreenCapturer(private val context: Context) {
    private val mpm = context.getSystemService(MediaProjectionManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val thread = HandlerThread("nastavnik-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    /** Вызывается в главном потоке, если система или пользователь остановили запись экрана. */
    var onStopped: (() -> Unit)? = null

    val isActive: Boolean get() = projection != null

    /** Запускает сессию по результату системного диалога. Возвращает false, если токен не подошёл. */
    fun start(resultCode: Int, data: Intent): Boolean {
        release()
        val p = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (_: Exception) {
            null
        } ?: return false
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        if (projection === p) {
                            release()
                            onStopped?.invoke()
                        }
                    }
                }
            },
            handler,
        )
        projection = p
        val m = realMetrics()
        width = m.widthPixels
        height = m.heightPixels
        dpi = m.densityDpi
        reader = newReader(width, height)
        return try {
            virtualDisplay = p.createVirtualDisplay(
                "nastavnik-capture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                handler,
            )
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    /** Полный снимок экрана в реальном разрешении (вместе со строкой состояния). */
    suspend fun capture(): Bitmap {
        val vd = virtualDisplay ?: throw CaptureException("Нет разрешения на снимок экрана")
        val m = realMetrics()
        if (m.widthPixels != width || m.heightPixels != height || m.densityDpi != dpi) {
            // Повернули планшет — подстраиваем размер виртуального дисплея.
            width = m.widthPixels
            height = m.heightPixels
            dpi = m.densityDpi
            vd.surface = null
            reader?.close()
            reader = newReader(width, height)
            vd.resize(width, height, dpi)
        }
        val r = reader ?: throw CaptureException("Захват экрана не готов")
        drain(r)
        try {
            val image = withTimeoutOrNull(FRAME_TIMEOUT_MS) { awaitFrame(r, vd) }
                ?: run {
                    // Иногда первый кадр не приходит, пока экран статичен: переподключаем поверхность.
                    vd.surface = null
                    withTimeoutOrNull(FRAME_TIMEOUT_MS) { awaitFrame(r, vd) }
                }
                ?: throw CaptureException("Экран не прислал кадр. Попробуй ещё раз.")
            return image.use { ImageUtils.imageToBitmap(it) }
        } finally {
            vd.surface = null
            r.setOnImageAvailableListener(null, null)
        }
    }

    private suspend fun awaitFrame(r: ImageReader, vd: VirtualDisplay): Image =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (done.compareAndSet(false, true) && cont.isActive) {
                    rr.setOnImageAvailableListener(null, null)
                    cont.resume(img)
                } else {
                    img.close()
                }
            }, handler)
            cont.invokeOnCancellation { r.setOnImageAvailableListener(null, null) }
            mainHandler.post { if (!done.get()) vd.surface = r.surface }
        }

    private fun drain(r: ImageReader) {
        while (true) {
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: break
            img.close()
        }
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    /** Останавливает сессию (например, при выключении наставника). */
    fun stop() {
        val p = projection
        release()
        runCatching { p?.stop() }
    }

    private fun release() {
        projection = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    companion object {
        private const val FRAME_TIMEOUT_MS = 1200L
    }
}
