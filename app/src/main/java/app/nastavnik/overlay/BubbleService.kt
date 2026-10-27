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
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import app.nastavnik.ai.Prompts
import app.nastavnik.ai.QuickAction
import app.nastavnik.app
import app.nastavnik.capture.CaptureException
import app.nastavnik.capture.ImageUtils
import app.nastavnik.capture.ScreenCapturer
import app.nastavnik.data.AppSettings
import app.nastavnik.data.BubbleSide
import app.nastavnik.geom.IRect
import app.nastavnik.geom.LineMatch
import app.nastavnik.geom.Motion
import app.nastavnik.geom.ScreenMotion
import app.nastavnik.geom.Thumb
import app.nastavnik.session.AskKind
import app.nastavnik.session.CleanSession
import app.nastavnik.session.CleanState
import app.nastavnik.session.MentorSession
import app.nastavnik.session.MentorUi
import app.nastavnik.session.ShotGeometry
import app.nastavnik.session.ShotResult
import app.nastavnik.ui.MainActivity
import app.nastavnik.ui.ProjectionPermissionActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сердце приложения: foreground-сервис, который тихо работает в фоне поверх Touchnotes.
 * Отдельного окна нет: кнопка, короткое меню у кнопки и ответы прямо на записях —
 * рамка там, где ошибка или где писать дальше, и заметка рядом.
 */
class BubbleService : LifecycleService(), BubbleView.Listener, SpeechInput.Callback, AnswerOverlay.Listener, AskBar.Callbacks {

    private lateinit var napp: NastavnikApp
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var menu: BubbleMenu
    private lateinit var bar: BubbleBar
    private lateinit var askBar: AskBar
    private lateinit var overlay: AnswerOverlay
    private lateinit var capturer: ScreenCapturer
    private lateinit var mentor: MentorSession
    private lateinit var clean: CleanSession
    private lateinit var speech: SpeechInput
    private var ready = false

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
    private var lastCleanState: CleanState = CleanState.Idle
    private var capturing = false

    /** Что сейчас показывает всплывашка у кнопки — чтобы не закрыть чужое сообщение. */
    private enum class BarOwner { NONE, ACTIONS, STATUS, ERROR, CLEAN, NOTICE }
    private var barOwner = BarOwner.NONE
    private var barText: String? = null
    private var shownErrorSeq = -1L
    private var lastSilentOk = 0L
    private var shownSilentError: Any? = null

    // Слежение за экраном между вопросами.
    private var trackRef: Thumb? = null
    private var trackSeq = -1L
    private var prevThumb: Thumb? = null
    private var prevMask: BooleanArray? = null
    private var dirty = false
    private var lastChangeAt = 0L
    private var lastWatchAt = 0L

    // Перенос выделений после увеличения/уменьшения страницы.
    private var relocateWanted = false
    private var prevTrackThumb: Thumb? = null
    private var lastRelocThumb: Thumb? = null

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
        menu = BubbleMenu(this, wm)
        bar = BubbleBar(this, wm).apply { onDismiss = { barOwner = BarOwner.NONE; barText = null } }
        askBar = AskBar(this, wm, this)
        overlay = AnswerOverlay(this, wm, this).apply { bubbleRect = { this@BubbleService.bubbleRect() } }
        mentor = MentorSession(napp, lifecycleScope)
        clean = napp.clean
        speech = SpeechInput(this, this)
        capturer.onStopped = { startForegroundCompat() }
        ready = true

        if (!Settings.canDrawOverlays(this)) {
            // Без разрешения окна не добавить — отправляем в приложение, там объяснят.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf()
            return
        }
        settings = napp.settings.load()
        addBubble()

        lifecycleScope.launch { mentor.load() }
        lifecycleScope.launch { mentor.state.collect { renderMentor(it) } }
        lifecycleScope.launch {
            clean.state.collect { s ->
                val prev = lastCleanState
                lastCleanState = s
                renderClean(prev, s)
                refreshBubbleBusy()
            }
        }
        lifecycleScope.launch {
            napp.settings.flow().collect { s ->
                val old = settings
                settings = s
                overlay.fadeAlpha = s.noteFadeAlpha
                bubble.watching = s.watchPauseSec > 0
                if (old == null || old.bubbleSizeDp != s.bubbleSizeDp || old.bubbleAlpha != s.bubbleAlpha || old.bubbleSide != s.bubbleSide) {
                    applyBubbleLook(s)
                }
                // Поменяли ключ или провайдера — снова пробуем фоновую проверку.
                if (old != null && (old.provider != s.provider || old.model != s.model)) shownSilentError = null
            }
        }
        lifecycleScope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ASK -> {
                showBubbleNow()
                main.postDelayed({ showActions() }, 150)
            }
            ACTION_SHOW_BUBBLE -> showBubbleNow()
            ACTION_PROJECTION_RESULT -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                onProjectionResult(code, data)
            }
            ACTION_PROJECTION_DENIED -> projectionWaiter?.complete(false)
            ACTION_RELOAD -> if (ready) {
                mentor.dismiss()
                lifecycleScope.launch { mentor.load(resume = true) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        settings?.let { s -> if (bubbleAdded) placeBubble(s.bubbleSide, s.bubbleYFraction) }
        menu.dismiss()
        if (barOwner == BarOwner.ACTIONS) bar.dismiss()
        // Повернули планшет — записи легли иначе: отметки прячем, заметки остаются (в пределах экрана).
        // Повернули планшет — выделения прячем; как только картинка успокоится, найдём строки заново.
        if (overlay.isShowing) {
            overlay.setSuspended(true)
            overlay.onScreenChanged()
            relocateWanted = true
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (ready) {
            speech.destroy()
            menu.dismiss()
            bar.dismiss()
            askBar.dismiss()
            overlay.clear()
            removeBubble()
            mentor.cancel()
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
            "Нажми на кнопку поверх Touchnotes — ответ появится прямо на записи"
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
        bubbleParams.alpha = s.bubbleAlpha.coerceAtMost(OverlayWindows.MAX_PASS_THROUGH_ALPHA)
        if (bubbleAdded) placeBubble(s.bubbleSide, s.bubbleYFraction)
    }

    private fun addBubble() {
        if (bubbleAdded || hiddenUntil > System.currentTimeMillis()) return
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

    private fun showBubbleNow() {
        main.removeCallbacks(unhide)
        unhide.run()
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

    /** Где кнопка на экране на самом деле (окно кнопки может быть сдвинуто строкой состояния). */
    private fun bubbleRect(): IRect? {
        if (!bubbleAdded || !bubble.isAttachedToWindow) return null
        val loc = IntArray(2)
        bubble.getLocationOnScreen(loc)
        return IRect(loc[0], loc[1], loc[0] + bubble.width, loc[1] + bubble.height)
    }

    private fun anchor(): BubbleAnchor {
        val screen = OverlayWindows.screenSize(this)
        val r = bubbleRect() ?: run {
            val s = bubbleParams.width.coerceAtLeast(1)
            IRect(bubbleParams.x, bubbleParams.y, bubbleParams.x + s, bubbleParams.y + s)
        }
        return BubbleAnchor(r, r.centerX < screen.x / 2)
    }

    private fun refreshBubbleBusy() {
        if (!ready) return
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
        if (barOwner == BarOwner.ACTIONS) bar.dismiss() else showActions()
    }

    /** Короткое меню у кнопки: что спросить. Никакой панели — ответ появится на записи. */
    private fun showActions() {
        askBar.dismiss()
        val items = mutableListOf<BarItem>()
        if (mentor.state.value.busy) {
            items += BarItem("Остановить", R.drawable.ic_close, 0xFFC9C8D3.toInt()) { mentor.dismiss() }
        }
        items += BarItem(QuickAction.NEXT_STEP.short, R.drawable.ic_bulb, color(R.color.menu_hint)) { quick(QuickAction.NEXT_STEP) }
        items += BarItem(QuickAction.FIND_ERROR.short, R.drawable.ic_search, color(R.color.menu_error)) { quick(QuickAction.FIND_ERROR) }
        items += BarItem(QuickAction.THEORY.short, R.drawable.ic_book, color(R.color.menu_theory)) { quick(QuickAction.THEORY) }
        items += BarItem("Спросить…", R.drawable.ic_mic, 0xFFF4F4F6.toInt()) { askBar.show(anchor()) }
        items += BarItem("Чистовик", R.drawable.ic_note, color(R.color.menu_ok)) { startClean() }
        if (overlay.isShowing) {
            items += BarItem("Убрать заметки", R.drawable.ic_close, 0xFFC9C8D3.toInt()) { mentor.dismiss() }
        }
        bar.showActions(anchor(), items)
        barOwner = BarOwner.ACTIONS
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onLongPress() {
        bar.dismiss()
        val s = settings ?: napp.settings.load()
        val watching = s.watchPauseSec > 0
        menu.show(
            bubbleParams.x, bubbleParams.y, bubbleParams.width, anchor().onLeft,
            listOf(
                (if (watching) "Не следить за решением" else "Следить за решением") to {
                    napp.settings.setWatchPauseSec(if (watching) 0 else DEFAULT_WATCH_PAUSE_SEC)
                    notice(
                        if (watching) {
                            "Фоновая проверка выключена."
                        } else {
                            "Слежу: когда перестанешь писать на ${DEFAULT_WATCH_PAUSE_SEC} с, проверю и отмечу ошибку, если она есть."
                        },
                    )
                },
                (if (regionMode) "Отправлять весь экран" else "Выделять область перед вопросом") to { toggleRegion() },
                "Новая задача" to {
                    mentor.newTask()
                    notice("Начинаю новую задачу.")
                },
                "История" to { openScreen(MainActivity.SCREEN_HISTORY) },
                "Настройки" to { openSettings() },
                "Спрятать на 10 минут" to { hideFor(10 * 60 * 1000L) },
                "Выключить" to { stopSelf() },
            ),
        )
    }

    override fun onDragStart() {
        snapAnimator?.cancel()
        menu.dismiss()
        bar.dismiss()
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
        bar.dismiss()
        askBar.dismiss()
        removeBubble()
        hiddenUntil = System.currentTimeMillis() + ms
        main.removeCallbacks(unhide)
        main.postDelayed(unhide, ms)
        updateNotification()
    }

    // ---------------- сообщения у кнопки ----------------

    private fun message(owner: BarOwner, text: String, items: List<BarItem> = emptyList(), busy: Boolean = false, autoHideMs: Long = 0) {
        if (barOwner == owner && barText == text && items.isEmpty()) return
        bar.showMessage(anchor(), text, items, busy, autoHideMs, outside = owner == BarOwner.NOTICE)
        barOwner = owner
        barText = text
    }

    private fun notice(text: String) = message(BarOwner.NOTICE, text, autoHideMs = 4500)

    private fun dismissBar(owner: BarOwner) {
        if (barOwner == owner) bar.dismiss()
    }

    // ---------------- ответ наставника ----------------

    private fun renderMentor(s: MentorUi) {
        val a = s.answer
        overlay.render(a)
        refreshBubbleBusy()
        // Опорный кадр для слежения за прокруткой — тот, по которому отвечал наставник.
        // Кадр появляется не в первом состоянии ответа («Смотрю на экран…»), а после снимка.
        if (a == null || a.seq != trackSeq || (trackRef == null && a.thumb != null)) {
            if (a?.seq != trackSeq) {
                relocateWanted = false
                prevTrackThumb = null
                lastRelocThumb = null
            }
            trackSeq = a?.seq ?: -1L
            trackRef = a?.thumb
        }
        when {
            a == null -> {
                dismissBar(BarOwner.STATUS)
            }
            a.errorText != null -> {
                dismissBar(BarOwner.STATUS)
                if (shownErrorSeq != a.seq) {
                    shownErrorSeq = a.seq
                    val items = if (a.error?.needsSettings == true) listOf(BarItem("Настройки", primary = true) { bar.dismiss(); openSettings() }) else emptyList()
                    message(BarOwner.ERROR, a.errorText, items)
                }
            }
            a.streaming && a.marks.isEmpty() && a.kind != AskKind.WATCH -> {
                message(BarOwner.STATUS, a.status ?: "Думаю…", listOf(BarItem("Остановить") { mentor.dismiss() }), busy = true)
            }
            else -> dismissBar(BarOwner.STATUS)
        }
        if (s.silentOkAt > lastSilentOk) {
            lastSilentOk = s.silentOkAt
            bubble.flash(Color.parseColor("#34A853"))
        }
        val se = s.silentError
        if (se != null && se !== shownSilentError) {
            shownSilentError = se
            if (se.needsSettings) {
                message(BarOwner.ERROR, "Фоновая проверка на паузе: ${se.userMessage}", listOf(BarItem("Настройки", primary = true) { bar.dismiss(); openSettings() }))
            }
        }
    }

    private fun requireKey(): Boolean {
        val reason = napp.settings.notReadyReason() ?: return true
        // Текст для Gemini/Claude: «Сначала вставь API-ключ (Google Gemini) в настройках.»
        val text = if (reason.startsWith("Не задан API-ключ")) {
            "Сначала вставь API-ключ (${napp.settings.provider().title}) в настройках."
        } else {
            "$reason. Открой настройки → «Нейросеть»."
        }
        message(BarOwner.ERROR, text, listOf(BarItem("Настройки", primary = true) { bar.dismiss(); openSettings() }))
        return false
    }

    private fun ask(text: String, kind: AskKind) {
        if (!requireKey()) return
        if (barOwner == BarOwner.ERROR || barOwner == BarOwner.NOTICE) bar.dismiss()
        mentor.ask(text, kind) { takeShot() }
    }

    private fun quick(action: QuickAction) = ask(action.prompt, AskKind.QUICK)

    override fun onSend(text: String) = ask(text, AskKind.USER)

    override fun onMic() = speech.toggle()

    override fun onMakeClean() = startClean()

    override fun onAllClosed() = mentor.dismiss()

    override fun onMore() = quick(QuickAction.NEXT_STEP)

    private fun toggleRegion() {
        regionMode = !regionMode
        notice(if (regionMode) "Перед вопросом обведи пальцем задачу — уйдёт только она." else "Снова отправляю весь экран.")
    }

    // ---------------- чистовик ----------------

    private fun startClean() {
        if (!requireKey()) return
        // Заметки к задаче больше не нужны и не должны попасть на снимок для чистовика.
        mentor.dismiss()
        clean.start { takeShot(numberLines = false) }
    }

    private fun renderClean(prev: CleanState, s: CleanState) {
        when (s) {
            is CleanState.Collecting -> {
                val n = s.shots.size
                val text = buildString {
                    append("Чистовик · снимков: $n из ${CleanState.MAX_SHOTS}")
                    when {
                        s.capturing -> append("\nДелаю снимок экрана…")
                        s.message != null -> append("\n").append(s.message)
                        else -> append("\nКонспект длиннее экрана? Прокрути Touchnotes и добавь ещё экран.")
                    }
                }
                val items = mutableListOf<BarItem>()
                if (!s.capturing && n < CleanState.MAX_SHOTS) items += BarItem("+ Экран") { clean.addShot { takeShot(numberLines = false) } }
                if (!s.capturing && n > 0) items += BarItem("Собрать", primary = true) { clean.build() }
                items += BarItem("Отмена") { clean.cancel() }
                message(BarOwner.CLEAN, text, items, busy = s.capturing)
            }
            is CleanState.Generating -> message(
                BarOwner.CLEAN,
                "Собираю чистовик… обычно до минуты." + if (s.retry) "\nПервый ответ не разобрался — пробую ещё раз." else "",
                listOf(BarItem("Отмена") { clean.cancel() }),
                busy = true,
            )
            is CleanState.Failed -> if (s.note == null) {
                val items = mutableListOf<BarItem>()
                if (s.shots.isNotEmpty()) items += BarItem("Пересобрать", primary = true) { clean.build() }
                if (s.needsSettings) items += BarItem("Настройки") { bar.dismiss(); openSettings() }
                items += BarItem("Отмена") { clean.cancel() }
                message(BarOwner.CLEAN, "Не получилось: ${s.message}", items)
            } else {
                dismissBar(BarOwner.CLEAN)
            }
            is CleanState.Preview -> {
                dismissBar(BarOwner.CLEAN)
                // Конспект готов — открываем предпросмотр (это уже документ, а не окно наставника).
                if (prev !is CleanState.Preview && prev !is CleanState.Saving) openScreen(MainActivity.SCREEN_CLEAN)
            }
            else -> dismissBar(BarOwner.CLEAN)
        }
    }

    // ---------------- снимок экрана ----------------

    private suspend fun ensureProjection(): Boolean {
        if (capturer.isActive) return true
        val waiter = CompletableDeferred<Boolean>()
        projectionWaiter = waiter
        // Убираем свои окна, чтобы они не заслоняли экран разрешения и системный диалог.
        bar.dismiss()
        askBar.dismiss()
        overlay.setCaptureHidden(true)
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
            overlay.setCaptureHidden(false)
            addBubble()
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
        menu.dismiss()
        overlay.setCaptureHidden(hidden)
        bar.setCaptureHidden(hidden)
        askBar.setCaptureHidden(hidden)
        if (bubbleAdded) {
            bubbleParams.alpha = if (hidden) 0f else (settings?.bubbleAlpha ?: 0.7f).coerceAtMost(OverlayWindows.MAX_PASS_THROUGH_ALPHA)
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
    }

    /** Снимок без наших окон, без системных панелей, не больше 1500 px и 1,15 Мп, JPEG 85%. */
    private suspend fun takeShot(numberLines: Boolean = true): ShotResult {
        if (!ensureProjection()) {
            return ShotResult.Failed(
                "Нет разрешения на снимок экрана — без него наставник не видит записи. Нажми кнопку ещё раз и выбери «Весь экран».",
            )
        }
        if (barOwner == BarOwner.ACTIONS || barOwner == BarOwner.NOTICE) bar.dismiss()
        capturing = true
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
                val (prepared, thumb) = withContext(Dispatchers.Default) { ImageUtils.prepare(full, rect, numberLines) to ImageUtils.thumb(full) }
                // В отладочной сборке последний снимок лежит в кэше — удобно проверить, что ушло в нейросеть.
                if (BuildConfig.DEBUG) runCatching { File(cacheDir, "last_shot.jpg").writeBytes(prepared.jpeg) }
                val r = prepared.screenRect
                val geometry = ShotGeometry(IRect(r.left, r.top, r.right, r.bottom), prepared.width, prepared.height, prepared.gutter, prepared.lines, prepared.luma)
                if (numberLines) ShotGeometry.last = geometry
                return ShotResult.Ok(prepared.jpeg, geometry, thumb)
            } finally {
                full.recycle()
            }
        } finally {
            setOverlaysHidden(false)
            capturing = false
        }
    }

    // ---------------- работа в фоне ----------------

    /** Все наши окна на экране — при сравнении кадров их не учитываем. */
    private fun ourRects(): List<IRect> =
        overlay.occupiedRects() + listOfNotNull(bubbleRect(), bar.rect(), askBar.rect())

    /**
     * Раз в пару секунд смотрит на экран (уменьшенный серый кадр, без отправки куда-либо):
     * — прокрутили страницу — рамки и заметки едут вместе с записями;
     * — страница сменилась — прячет их;
     * — если включена фоновая проверка: студент писал и остановился — тихо проверяет решение.
     */
    private suspend fun watchLoop() {
        val power = getSystemService(PowerManager::class.java)
        while (lifecycleScope.isActive) {
            delay(TICK_MS)
            val s = settings ?: continue
            val tracking = overlay.isShowing && trackRef != null
            val watching = s.watchPauseSec > 0 && shownSilentError == null
            if ((!tracking && !watching) || !capturer.isActive || capturing || askBar.isShowing || !power.isInteractive) {
                prevThumb = null
                continue
            }
            val thumb = grabThumb() ?: continue
            val rects = ourRects()
            // Строка состояния и панель навигации не двигаются вместе со страницей.
            val area = OverlayWindows.contentRect(this)
            val mask = thumb.maskOf(rects)
            val ref = trackRef
            if (overlay.isShowing && ref != null) {
                val motion = withContext(Dispatchers.Default) { ScreenMotion.estimate(ref, thumb, mask, thumb.rowMaskOf(rects), area.top, area.bottom) }
                if (BuildConfig.DEBUG) Log.i(TAG, "track: $motion")
                val cells = thumb.cols * thumb.rows
                when (val m = motion) {
                    is Motion.Moved -> {
                        relocateWanted = false
                        overlay.setSuspended(false)
                        overlay.setOffset(m.dx, m.dy)
                    }
                    // Почти ничего не изменилось (дописали пару слов) — всё на месте.
                    is Motion.Same -> if (m.changedCells <= cells * RELOCATE_CHANGE) {
                        relocateWanted = false
                        overlay.setSuspended(false)
                        overlay.setOffset(0, 0)
                    } else {
                        relocateWanted = true
                    }
                    // Не просто прокрутка: увеличили, уменьшили, другая страница. Прячем выделения и ищем строки заново.
                    Motion.Replaced -> {
                        overlay.setSuspended(true)
                        relocateWanted = true
                    }
                }
                // Ищем, когда картинка успокоилась (жест увеличения закончился) и с прошлой попытки что-то поменялось.
                val prevT = prevTrackThumb
                val stable = prevT != null && ScreenMotion.changedCells(prevT, thumb, mask) <= 2
                val last = lastRelocThumb
                val changedSinceTry = last == null || ScreenMotion.changedCells(last, thumb, mask) > cells / 100
                if (relocateWanted && stable && changedSinceTry) {
                    lastRelocThumb = thumb
                    relocateMarks()
                }
                prevTrackThumb = thumb
            }
            if (watching) watchTick(thumb, mask, s.watchPauseSec * 1000L)
        }
    }

    /**
     * Свежий снимок экрана (без наших окон) → на нём заново ищутся строки, и отмеченные строки узнаются
     * по «почерку» с учётом масштаба. Выделения встают на новые места; что не нашлось — остаётся скрытым.
     */
    private suspend fun relocateMarks() {
        val a = mentor.state.value.answer ?: return
        val g = a.geometry ?: return
        val ref = g.page() ?: return
        if (!capturer.isActive || capturing) return
        capturing = true
        setOverlaysHidden(true)
        val full: android.graphics.Bitmap? = try {
            delay(HIDE_DELAY_MS)
            capturer.capture()
        } catch (_: CaptureException) {
            null
        } finally {
            setOverlaysHidden(false)
            capturing = false
        }
        if (full == null) return
        val area = OverlayWindows.contentRect(this)
        val (rects, thumb) = try {
            withContext(Dispatchers.Default) {
                val page = ImageUtils.page(full, area)
                val k = page.scale
                val found = a.marks.map { m ->
                    val nb = m.box ?: return@map null
                    val box = IRect((nb.left * g.imageW).toInt(), (nb.top * g.imageH).toInt(), (nb.right * g.imageW).toInt(), (nb.bottom * g.imageH).toInt())
                    LineMatch.relocate(ref, box, page.image)?.let { r ->
                        IRect(area.left + (r.left / k).toInt(), area.top + (r.top / k).toInt(), area.left + (r.right / k).toInt(), area.top + (r.bottom / k).toInt())
                    }
                }
                found to ImageUtils.thumb(full)
            }
        } finally {
            full.recycle()
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "relocate: $rects")
        // Ответ мог смениться, пока искали.
        if (mentor.state.value.answer?.seq != a.seq) return
        relocateWanted = false
        trackRef = thumb
        prevTrackThumb = thumb
        lastRelocThumb = thumb
        overlay.relocate(rects)
    }

    private fun watchTick(thumb: Thumb, mask: BooleanArray, pauseMs: Long) {
        val now = System.currentTimeMillis()
        val prev = prevThumb
        val prevM = prevMask
        prevThumb = thumb
        prevMask = mask
        if (prev != null) {
            val union = if (prevM != null && prevM.size == mask.size) BooleanArray(mask.size) { mask[it] || prevM[it] } else mask
            if (ScreenMotion.changedCells(prev, thumb, union) >= 2) {
                dirty = true
                lastChangeAt = now
            }
        }
        val answer = mentor.state.value.answer
        // Ответ на вопрос студента не перебиваем тихой проверкой.
        val userAnswerShown = answer != null && answer.kind != AskKind.WATCH && overlay.isShowing
        if (dirty && now - lastChangeAt >= pauseMs && now - lastWatchAt >= MIN_WATCH_GAP_MS &&
            !mentor.isBusy && !userAnswerShown && clean.state.value is CleanState.Idle &&
            napp.settings.notReadyReason() == null
        ) {
            dirty = false
            lastWatchAt = now
            mentor.ask(Prompts.WATCH_PROMPT, AskKind.WATCH) { takeShot() }
        }
    }

    private suspend fun grabThumb(): Thumb? {
        val full = try {
            capturer.capture()
        } catch (_: Exception) {
            return null
        }
        return try {
            withContext(Dispatchers.Default) { ImageUtils.thumb(full) }
        } finally {
            full.recycle()
        }
    }

    // ---------------- экраны приложения ----------------

    private fun openSettings() = openScreen(MainActivity.SCREEN_SETTINGS)

    private fun openScreen(screen: String) {
        bar.dismiss()
        askBar.dismiss()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // ---------------- голос ----------------

    override fun onListening(active: Boolean) = askBar.setListening(active)
    override fun onPartial(text: String) = askBar.setText(text)
    override fun onResult(text: String) = askBar.setText(text)
    override fun onError(message: String, needsPermission: Boolean) {
        if (needsPermission) {
            message(BarOwner.ERROR, message, listOf(BarItem("Разрешить", primary = true) { bar.dismiss(); openScreen(MainActivity.SCREEN_ONBOARDING) }))
        } else {
            notice(message)
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
        const val DEFAULT_WATCH_PAUSE_SEC = 10
        private const val TAG = "Nastavnik"
        private const val NOTIFICATION_ID = 42
        private const val STYLUS_PASS_MS = 1500L
        private const val HIDE_DELAY_MS = 180L
        private const val AFTER_PERMISSION_DELAY_MS = 600L
        private const val PERMISSION_TIMEOUT_MS = 120_000L
        private const val TICK_MS = 2000L
        private const val MIN_WATCH_GAP_MS = 45_000L

        /** Если изменилось больше этой доли экрана, а сдвига нет — страницу увеличили или сменили. */
        private const val RELOCATE_CHANGE = 0.04f

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
