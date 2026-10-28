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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CaptureException(message: String) : Exception(message)

/**
 * Снимки экрана через MediaProjection. Виртуальный дисплей создаётся один раз на сессию
 * (Android 14+ не позволяет создавать его повторно), и экран идёт в него всё время, пока наставник включён:
 * последний кадр всегда под рукой.
 *
 * Раньше поверхность подключалась только на время снимка, чтобы не тратить батарею. Но на части планшетов
 * система на неподвижном экране после подключения кадр не присылает вовсе — и снимок не получался никогда
 * («экран не прислал кадр»). Теперь кадры приходят сами при каждом изменении экрана (на неподвижном — ни
 * одного, батарея не тратится), а снимок берёт свежий кадр после того, как спрятались наши окна.
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

    /** Последний кадр экрана (держим открытым, пока не придёт следующий) и когда он пришёл. Под [frameLock]. */
    private val frameLock = Any()
    private var latest: Image? = null
    private var latestAt = 0L

    /** Вызывается в главном потоке, если система или пользователь остановили запись экрана. */
    var onStopped: (() -> Unit)? = null

    /**
     * Нужен свежий кадр, а экран неподвижен — этот вызов чуть меняет невидимую точку в углу экрана,
     * и система рисует новый кадр. Вызывается в главном потоке.
     */
    var nudge: (() -> Unit)? = null

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
        val r = newReader(width, height)
        reader = r
        return try {
            virtualDisplay = p.createVirtualDisplay(
                "nastavnik-capture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                handler,
            )
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    private val lock = Mutex()

    /**
     * Снимок экрана в реальном разрешении (вместе со строкой состояния) — кадр, пришедший не раньше [sinceNanos]
     * (по [System.nanoTime]). Так в снимок не попадают наши окна, которые только что спрятали. Экран неподвижен
     * и свежего кадра нет даже после «будилки» — берём последний: он и есть то, что сейчас на экране.
     */
    suspend fun capture(sinceNanos: Long = System.nanoTime()): Bitmap = lock.withLock {
        ensureSize()
        val start = System.nanoTime()
        var nudges = 0
        while (true) {
            val fresh = frameAfter(sinceNanos)
            if (fresh != null) return@withLock fresh
            val waited = (System.nanoTime() - start) / 1_000_000
            if (waited >= WAIT_MS) break
            if (nudges < WAKE_AT_MS.size && waited >= WAKE_AT_MS[nudges]) {
                nudges++
                nudge?.invoke()
            }
            delay(POLL_MS)
        }
        frameAfter(0L) ?: throw CaptureException(NO_FRAME)
    }

    /** Последний кадр без ожидания — для слежения между вопросами. null — кадров ещё не было. */
    suspend fun captureLatest(): Bitmap? = lock.withLock {
        ensureSize()
        frameAfter(0L)
    }

    /** Копия последнего кадра, если он пришёл не раньше [sinceNanos]. */
    private fun frameAfter(sinceNanos: Long): Bitmap? = synchronized(frameLock) {
        val img = latest
        if (img == null || latestAt < sinceNanos) null else runCatching { ImageUtils.imageToBitmap(img) }.getOrNull()
    }

    /** Повернули планшет — подстраиваем размер виртуального дисплея и приёмника кадров. */
    private fun ensureSize() {
        val vd = virtualDisplay ?: throw CaptureException("Нет разрешения на снимок экрана")
        val m = realMetrics()
        if (m.widthPixels == width && m.heightPixels == height && m.densityDpi == dpi) return
        width = m.widthPixels
        height = m.heightPixels
        dpi = m.densityDpi
        vd.surface = null
        dropLatest()
        runCatching { reader?.close() }
        val r = newReader(width, height)
        reader = r
        vd.resize(width, height, dpi)
        vd.surface = r.surface
    }

    /** Приёмник кадров: держит последний кадр открытым, прошлый сразу отпускает. */
    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                synchronized(frameLock) {
                    latest?.close()
                    latest = img
                    latestAt = System.nanoTime()
                }
            }, handler)
        }

    private fun dropLatest() {
        synchronized(frameLock) {
            latest?.close()
            latest = null
            latestAt = 0L
        }
    }

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
        dropLatest()
        runCatching { reader?.close() }
        reader = null
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    companion object {
        /** Сколько ждать свежего кадра для снимка. */
        private const val WAIT_MS = 1500L
        private const val POLL_MS = 25L

        /** Когда «будить» неподвижный экран, если свежий кадр не пришёл. */
        private val WAKE_AT_MS = longArrayOf(120L, 400L, 900L)
        const val NO_FRAME = "Не получилось снять экран. Нажми ещё раз."
    }
}
