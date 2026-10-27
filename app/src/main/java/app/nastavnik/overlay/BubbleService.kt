package app.nastavnik.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.nastavnik.BuildConfig
import app.nastavnik.NastavnikApp
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.capture.CaptureException
import app.nastavnik.capture.ImageUtils
import app.nastavnik.capture.ScreenCapturer
import app.nastavnik.ai.QuickAction
import app.nastavnik.data.AppSettings
import app.nastavnik.data.BubbleSide
import app.nastavnik.session.CleanSession
import app.nastavnik.session.CleanState
import app.nastavnik.session.MentorSession
import app.nastavnik.session.ShotResult
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.ProjectionPermissionActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сердце приложения: foreground-сервис с плавающей кнопкой, панелью наставника и захватом экрана.
 */
class BubbleService : LifecycleService(), PanelController.Actions, BubbleView.Listener, SpeechInput.Callback {

    private lateinit var napp: NastavnikApp
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var panel: PanelController
    private lateinit var menu: BubbleMenu
    private lateinit var capturer: ScreenCapturer
    private lateinit var mentor: MentorSession
    private lateinit var clean: CleanSession
    private lateinit var speech: SpeechInput

    private val main = Handler(Looper.getMainLooper())
    private val bubbleParams = OverlayWindows.params(1, 1)
    private var bubbleAdded = false
    private var settings: AppSettings? = null
    private var regionMode = false
    private var hiddenUntil = 0L
    private var projectionPending = false
    private var projectionWaiter: CompletableDeferred<Boolean>? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var snapAnimator: ValueAnimator? = null
    private var lastShownError: Any? = null
    private var lastCleanState: CleanState = CleanState.Idle

    private val unhide = Runnable {
        hiddenUntil = 0
        addBubble()
        updateNotification()
    }
    private val restoreTouch = Runnable { setBubbleTouchable(true) }

    override fun onCreate() {
        super.onCreate()
        running = true
        napp = app
        capturer = ScreenCapturer(this)
        startForegroundCompat()

        wm = getSystemService(WindowManager::class.java)
        bubble = BubbleView(ContextWrapperOverlay(this), this)
        panel = PanelController(this, wm, this)
        menu = BubbleMenu(this, wm)
        mentor = MentorSession(napp, lifecycleScope)
        clean = CleanSession(napp, lifecycleScope)
        speech = SpeechInput(this, this)
        capturer.onStopped = { startForegroundCompat() }

        if (!Settings.canDrawOverlays(this)) {
            // Без разрешения окна не добавить — отправляем в приложение, там объяснят.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf()
            return
        }
        settings = napp.settings.load()
        addBubble()

        lifecycleScope.launch { mentor.load() }
        lifecycleScope.launch {
            mentor.state.collect { s ->
                panel.renderChat(s)
                refreshBubbleBusy()
                val err = s.lastError
                if (err != null && err !== lastShownError && err.needsSettings) {
                    lastShownError = err
                    panel.showStatus(err.userMessage, "Настройки") { openSettings() }
                }
            }
        }
        lifecycleScope.launch {
            clean.state.collect { s ->
                val wasSaving = lastCleanState is CleanState.Saving
                lastCleanState = s
                panel.renderClean(s)
                refreshBubbleBusy()
                // После сохранения открывается Touchnotes — убираем панель, чтобы не заслоняла импорт.
                if (wasSaving && s is CleanState.Saved) collapse()
            }
        }
        lifecycleScope.launch {
            napp.settings.flow().collect { s ->
                val old = settings
                settings = s
                panel.setOptions(s.subject, napp.settings.subjects, regionMode, s.attachShotToTyped, s.provider.shortTitle)
                panel.setWindowPrefs(s.writingAlpha, s.autoCollapseMin, s.bubbleSide == BubbleSide.LEFT)
                if (old == null || old.bubbleSizeDp != s.bubbleSizeDp || old.bubbleAlpha != s.bubbleAlpha || old.bubbleSide != s.bubbleSide) {
                    applyBubbleLook(s)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ASK -> openPanel()
            ACTION_SHOW_BUBBLE -> {
                main.removeCallbacks(unhide)
                unhide.run()
            }
            ACTION_PROJECTION_RESULT -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                onProjectionResult(code, data)
            }
            ACTION_PROJECTION_DENIED -> projectionWaiter?.complete(false)
            ACTION_RELOAD -> if (::panel.isInitialized) {
                mentor.cancel()
                clean.cancel()
                lifecycleScope.launch { mentor.load() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        settings?.let { s -> if (bubbleAdded) placeBubble(s.bubbleSide, s.bubbleYFraction) }
        menu.dismiss()
        panel.onScreenChanged()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (::panel.isInitialized) {
            speech.destroy()
            menu.dismiss()
            removeBubble()
            panel.destroy()
            mentor.cancel()
            clean.cancel()
        }
        capturer.shutdown()
        super.onDestroy()
    }

    // ---------------- foreground ----------------

    private fun fgsTypes(): Int {
        var t = 0
        if (Build.VERSION.SDK_INT >= 34) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // До Android 14 тип mediaProjection можно заявить заранее; с 14 — только после согласия пользователя.
        if (Build.VERSION.SDK_INT < 34 || capturer.isActive || projectionPending) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= 30 && hasMic()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val n = buildNotification()
        val types = fgsTypes()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
        } catch (_: Exception) {
            // Например, микрофон нельзя заявить из фона — пробуем без него.
            val noMic = if (Build.VERSION.SDK_INT >= 30) types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv() else types
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, noMic) }
        }
    }

    private fun updateNotification() {
        if (!running) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun service(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, BubbleService::class.java).setAction(action), flags,
        )
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags,
        )
        val hidden = hiddenUntil > System.currentTimeMillis()
        val text = if (hidden) {
            "Кнопка спрятана до " + SimpleDateFormat("HH:mm", Locale("ru")).format(Date(hiddenUntil))
        } else {
            "Нажми на кнопку поверх Touchnotes, чтобы спросить"
        }
        val b = NotificationCompat.Builder(this, NastavnikApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("Наставник рядом")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Спросить", service(ACTION_ASK, 1))
        if (hidden) b.addAction(0, "Показать кнопку", service(ACTION_SHOW_BUBBLE, 3))
        b.addAction(0, "Выключить", service(ACTION_STOP, 2))
        return b.build()
    }

    // ---------------- кнопка ----------------

    private fun applyBubbleLook(s: AppSettings) {
        val size = OverlayWindows.dp(this, s.bubbleSizeDp.toFloat())
        bubbleParams.width = size
        bubbleParams.height = size
        bubbleParams.alpha = s.bubbleAlpha.coerceAtMost(MAX_PASS_THROUGH_ALPHA)
        if (bubbleAdded) placeBubble(s.bubbleSide, s.bubbleYFraction)
    }

    private fun addBubble() {
        if (bubbleAdded || hiddenUntil > System.currentTimeMillis() || panel.isShowing) return
        val s = settings ?: napp.settings.load()
        applyBubbleLook(s)
        positionBubble(s.bubbleSide, s.bubbleYFraction)
        try {
            wm.addView(bubble, bubbleParams)
            bubbleAdded = true
        } catch (_: Exception) {
        }
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        main.removeCallbacks(restoreTouch)
        if (bubbleAdded) runCatching { wm.removeView(bubble) }
        bubbleAdded = false
    }

    private fun positionBubble(side: BubbleSide, yFraction: Float) {
        val screen = OverlayWindows.screenSize(this)
        val size = bubbleParams.width
        bubbleParams.x = if (side == BubbleSide.LEFT) 0 else screen.x - size
        bubbleParams.y = (yFraction * (screen.y - size)).toInt().coerceIn(0, (screen.y - size).coerceAtLeast(0))
    }

    private fun placeBubble(side: BubbleSide, yFraction: Float) {
        positionBubble(side, yFraction)
        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
    }

    private fun refreshBubbleBusy() {
        if (!::bubble.isInitialized) return
        bubble.busy = mentor.state.value.busy || clean.state.value is CleanState.Generating || clean.state.value is CleanState.Saving
    }

    /** Стилус над кнопкой: на 1,5 с окно перестаёт принимать касания, и штрих уходит в Touchnotes. */
    private fun setBubbleTouchable(touchable: Boolean) {
        if (!bubbleAdded) return
        val f = bubbleParams.flags
        val want = if (touchable) f and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (want != f) {
            bubbleParams.flags = want
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
    }

    override fun onStylus() {
        setBubbleTouchable(false)
        main.removeCallbacks(restoreTouch)
        main.postDelayed(restoreTouch, STYLUS_PASS_MS)
    }

    override fun onTap() {
        menu.dismiss()
        openPanel()
    }

    override fun onLongPress() {
        val screen = OverlayWindows.screenSize(this)
        val onLeft = bubbleParams.x < screen.x / 2
        menu.show(
            bubbleParams.x, bubbleParams.y, bubbleParams.width, onLeft,
            listOf(
                "Спрятать на 10 минут" to { hideFor(10 * 60 * 1000L) },
                "Настройки" to { openSettings() },
                "Выключить" to { stopSelf() },
            ),
        )
    }

    override fun onDragStart() {
        snapAnimator?.cancel()
        menu.dismiss()
        dragStartX = bubbleParams.x
        dragStartY = bubbleParams.y
    }

    override fun onDrag(dx: Int, dy: Int) {
        val screen = OverlayWindows.screenSize(this)
        val size = bubbleParams.width
        bubbleParams.x = (dragStartX + dx).coerceIn(0, screen.x - size)
        bubbleParams.y = (dragStartY + dy).coerceIn(0, screen.y - size)
        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
    }

    override fun onDragEnd() {
        val screen = OverlayWindows.screenSize(this)
        val size = bubbleParams.width
        val side = if (bubbleParams.x + size / 2 < screen.x / 2) BubbleSide.LEFT else BubbleSide.RIGHT
        val targetX = if (side == BubbleSide.LEFT) 0 else screen.x - size
        val yFraction = if (screen.y > size) bubbleParams.y.toFloat() / (screen.y - size) else 0f
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 180
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                if (bubbleAdded) runCatching { wm.updateViewLayout(bubble, bubbleParams) }
            }
            start()
        }
        napp.settings.setBubblePosition(side, yFraction)
    }

    private fun hideFor(ms: Long) {
        panel.hide()
        removeBubble()
        hiddenUntil = System.currentTimeMillis() + ms
        main.removeCallbacks(unhide)
        main.postDelayed(unhide, ms)
        updateNotification()
    }

    // ---------------- панель ----------------

    private fun openPanel() {
        if (hiddenUntil > System.currentTimeMillis()) {
            main.removeCallbacks(unhide)
            hiddenUntil = 0
            updateNotification()
        }
        menu.dismiss()
        removeBubble()
        panel.show()
    }

    override fun collapse() {
        if (speech.isListening) speech.stop()
        panel.hide()
        addBubble()
    }

    // ---------------- снимок экрана ----------------

    private suspend fun ensureProjection(): Boolean {
        if (capturer.isActive) return true
        val waiter = CompletableDeferred<Boolean>()
        projectionWaiter = waiter
        // Убираем свои окна, чтобы они не заслоняли экран разрешения и системный диалог.
        val panelWasShown = panel.isShowing
        panel.hide()
        removeBubble()
        try {
            val started = runCatching {
                startActivity(
                    Intent(this, ProjectionPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }.isSuccess
            if (!started) return false
            val ok = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { waiter.await() } ?: false
            if (ok) delay(AFTER_PERMISSION_DELAY_MS) // ждём, пока закроется системный диалог
            return ok && capturer.isActive
        } finally {
            projectionWaiter = null
            if (panelWasShown) panel.show() else addBubble()
        }
    }

    private fun onProjectionResult(code: Int, data: Intent?) {
        if (data == null) {
            projectionWaiter?.complete(false)
            return
        }
        // Android 14+: сначала foreground с типом mediaProjection, потом getMediaProjection.
        projectionPending = true
        startForegroundCompat()
        val ok = capturer.start(code, data)
        projectionPending = false
        if (!ok) startForegroundCompat()
        projectionWaiter?.complete(ok)
    }

    private fun setOverlaysHidden(hidden: Boolean) {
        panel.setCaptureHidden(hidden)
        menu.dismiss()
        if (bubbleAdded) {
            bubbleParams.alpha = if (hidden) 0f else (settings?.bubbleAlpha ?: 0.7f).coerceAtMost(MAX_PASS_THROUGH_ALPHA)
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
    }

    /** Снимок без кнопки и панели, без системных панелей, ≤1500 px, JPEG 85%. */
    private suspend fun takeShot(): ShotResult {
        if (!ensureProjection()) {
            return ShotResult.Failed(
                "Нет разрешения на снимок экрана — без него наставник не видит записи. Нажми кнопку ещё раз и выбери «Весь экран».",
            )
        }
        setOverlaysHidden(true)
        try {
            delay(HIDE_DELAY_MS)
            val full = try {
                capturer.capture()
            } catch (e: CaptureException) {
                return ShotResult.Failed(e.message ?: "Не удалось сделать снимок")
            }
            try {
                val rect = if (regionMode) {
                    when (val choice = RegionSelector(this, wm).select(full)) {
                        is RegionChoice.Region -> choice.rect
                        RegionChoice.Full -> OverlayWindows.contentRect(this)
                        RegionChoice.Cancel -> return ShotResult.Cancelled
                    }
                } else {
                    OverlayWindows.contentRect(this)
                }
                val jpeg = withContext(Dispatchers.Default) { ImageUtils.prepare(full, rect) }
                // В отладочной сборке последний снимок лежит в кэше — удобно проверить, что ушло в нейросеть.
                if (BuildConfig.DEBUG) runCatching { File(cacheDir, "last_shot.jpg").writeBytes(jpeg) }
                return ShotResult.Ok(jpeg)
            } finally {
                full.recycle()
            }
        } finally {
            setOverlaysHidden(false)
        }
    }

    // ---------------- действия панели ----------------

    private fun requireKey(): Boolean {
        val reason = napp.settings.notReadyReason() ?: return true
        // Текст для Gemini/Claude: «Сначала вставь API-ключ (Google Gemini) в настройках.»
        val text = if (reason.startsWith("Не задан API-ключ")) {
            "Сначала вставь API-ключ (${napp.settings.provider().title}) в настройках."
        } else {
            "$reason. Открой настройки → «Нейросеть»."
        }
        panel.showStatus(text, "Настройки") { openSettings() }
        return false
    }

    private fun ask(text: String, withShot: Boolean) {
        if (!requireKey()) return
        if (clean.isActive && lastCleanState !is CleanState.Saving) clean.cancel()
        panel.hideStatus()
        mentor.ask(text, withShot) { takeShot() }
    }

    override fun quick(action: QuickAction) = ask(action.prompt, true)

    override fun askTyped(text: String, withShot: Boolean) = ask(text, withShot)

    override fun startClean() {
        if (!requireKey()) return
        mentor.dismissSolved()
        panel.hideStatus()
        clean.start { takeShot() }
    }

    override fun cleanAddShot() = clean.addShot { takeShot() }
    override fun cleanRemoveShot() = clean.removeLastShot()
    override fun cleanBuild() = clean.build()
    override fun cleanSave() = clean.save(panel.exportHost, mentor.state.value.conversationId)
    override fun cleanResend() {
        clean.resend()
        collapse()
    }
    override fun cleanSendPng() {
        clean.sendPngs()
        collapse()
    }
    override fun cleanBackToPreview() = clean.backToPreview()
    override fun cleanCancel() = clean.cancel()

    override fun newTask() {
        mentor.newTask()
        clean.cancel()
        panel.hideStatus()
    }

    override fun toggleRegion() {
        regionMode = !regionMode
        val s = settings ?: return
        panel.setOptions(s.subject, napp.settings.subjects, regionMode, s.attachShotToTyped, s.provider.shortTitle)
        panel.showStatus(
            if (regionMode) "Режим области: после нажатия кнопки обведи задачу пальцем." else "Снова отправляю весь экран.",
        )
        main.postDelayed({ panel.hideStatus() }, 2500)
    }

    override fun toggleAttachShot() {
        val s = settings ?: return
        napp.settings.setAttachShot(!s.attachShotToTyped)
    }

    override fun pickSubject(subject: String) = napp.settings.setSubject(subject)

    override fun openSettings() = openScreen(MainActivity.SCREEN_SETTINGS)

    override fun openHistory() = openScreen(MainActivity.SCREEN_HISTORY)

    private fun openScreen(screen: String) {
        collapse()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun stopService() = stopSelf()

    override fun mic() = speech.toggle()

    override fun dismissSolved() = mentor.dismissSolved()

    // ---------------- голос ----------------

    override fun onListening(active: Boolean) = panel.setListening(active)
    override fun onPartial(text: String) = panel.setInputText(text)
    override fun onResult(text: String) = panel.setInputText(text)
    override fun onError(message: String, needsPermission: Boolean) {
        if (needsPermission) {
            panel.showStatus(message, "Разрешить") { openScreen(MainActivity.SCREEN_ONBOARDING) }
        } else {
            panel.showStatus(message)
        }
    }

    /** Контекст с темой для View кнопки. */
    private class ContextWrapperOverlay(base: Context) : android.view.ContextThemeWrapper(base, R.style.Theme_Nastavnik_Overlay)

    companion object {
        const val ACTION_STOP = "app.nastavnik.STOP"
        const val ACTION_ASK = "app.nastavnik.ASK"
        const val ACTION_SHOW_BUBBLE = "app.nastavnik.SHOW_BUBBLE"
        const val ACTION_PROJECTION_RESULT = "app.nastavnik.PROJECTION_RESULT"
        const val ACTION_PROJECTION_DENIED = "app.nastavnik.PROJECTION_DENIED"
        const val ACTION_RELOAD = "app.nastavnik.RELOAD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 42
        private const val STYLUS_PASS_MS = 1500L
        private const val HIDE_DELAY_MS = 180L
        private const val AFTER_PERMISSION_DELAY_MS = 600L
        private const val PERMISSION_TIMEOUT_MS = 120_000L

        /** Android 12+ пропускает касания сквозь чужое окно, только если его прозрачность ≤ 0,8. */
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_STOP))
        }
    }
}
