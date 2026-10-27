package app.nastavnik.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import app.nastavnik.R
import app.nastavnik.capture.ImageUtils
import app.nastavnik.ai.QuickAction
import app.nastavnik.notes.CleanView
import app.nastavnik.notes.NoteStorage
import app.nastavnik.notes.WebViews
import app.nastavnik.session.ChatMessage
import app.nastavnik.session.ChatUiState
import app.nastavnik.session.CleanState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Окно наставника «как у Google»: плавающая карточка с радужным свечением у нижнего края экрана.
 * Высота растёт вместе с перепиской. Касания мимо карточки уходят в Touchnotes; пока человек пишет,
 * карточка не исчезает, а становится полупрозрачной (и, если включено, сворачивается через N минут).
 */
class PanelController(baseContext: Context, private val wm: WindowManager, private val actions: Actions) {

    interface Actions {
        fun quick(action: QuickAction)
        fun askTyped(text: String, withShot: Boolean)
        fun startClean()
        fun cleanAddShot()
        fun cleanRemoveShot()
        fun cleanBuild()
        fun cleanSave()
        fun cleanResend()
        fun cleanSendPng()
        fun cleanBackToPreview()
        fun cleanCancel()
        fun collapse()
        fun newTask()
        fun toggleRegion()
        fun toggleAttachShot()
        fun pickSubject(subject: String)
        fun openSettings()
        fun openHistory()
        fun stopService()
        fun mic()
        fun dismissSolved()
    }

    private val ctx = ContextThemeWrapper(baseContext, R.style.Theme_Nastavnik_Overlay)
    private val imm = ctx.getSystemService(InputMethodManager::class.java)

    @SuppressLint("InflateParams")
    val root: TouchAwareFrame = LayoutInflater.from(ctx).inflate(R.layout.panel_view, null) as TouchAwareFrame
    private val card: LinearLayout = root.findViewById(R.id.panel_card)
    private val glow = GlowCardDrawable(ctx)
    private val header: View = root.findViewById(R.id.header)
    private val handleArea: View = root.findViewById(R.id.handle_area)
    private val contentFrame: FrameLayout = root.findViewById(R.id.content_frame)
    private val titleView: TextView = root.findViewById(R.id.title)
    private val subjectView: TextView = root.findViewById(R.id.subject)
    private val btnRegion: ImageButton = root.findViewById(R.id.btn_region)
    private val btnCollapse: ImageButton = root.findViewById(R.id.btn_collapse)
    private val quickButtons: List<Button> = listOf(
        root.findViewById(R.id.q_next),
        root.findViewById(R.id.q_error),
        root.findViewById(R.id.q_theory),
        root.findViewById(R.id.q_clean),
    )
    private val chatHost: FrameLayout = root.findViewById(R.id.chat_host)
    private val cleanBox: View = root.findViewById(R.id.clean_box)
    private val cleanTitle: TextView = root.findViewById(R.id.clean_title)
    private val cleanThumbs: LinearLayout = root.findViewById(R.id.clean_thumbs)
    private val cleanMessage: TextView = root.findViewById(R.id.clean_message)
    private val cleanPreviewHost: FrameLayout = root.findViewById(R.id.clean_preview_host)
    private val cAdd: Button = root.findViewById(R.id.c_add)
    private val cRemove: Button = root.findViewById(R.id.c_remove)
    private val cPrimary: Button = root.findViewById(R.id.c_primary)
    private val cSecondary: Button = root.findViewById(R.id.c_secondary)
    private val cCancel: Button = root.findViewById(R.id.c_cancel)
    private val doneBanner: View = root.findViewById(R.id.done_banner)
    private val statusBar: View = root.findViewById(R.id.status_bar)
    private val statusText: TextView = root.findViewById(R.id.status_text)
    private val statusAction: Button = root.findViewById(R.id.status_action)
    private val input: EditText = root.findViewById(R.id.input)
    private val btnShot: ImageButton = root.findViewById(R.id.btn_shot)
    private val btnMic: ImageButton = root.findViewById(R.id.btn_mic)
    private val btnSend: ImageButton = root.findViewById(R.id.btn_send)

    private val params = OverlayWindows.params(1, 1, OverlayWindows.PASS_THROUGH_FLAGS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
    }
    var isShowing = false
        private set
    private var landscape = true

    // --- поведение, пока человек пишет ---
    private var writingAlpha = 0.6f
    private var autoCollapseMin = 0
    private var bubbleOnLeft = false
    private var writing = false
    private val autoCollapse = Runnable {
        // Пока нейросеть отвечает, не сворачиваем — отложим ещё на минуту.
        if (busy || lastCleanState is CleanState.Generating || lastCleanState is CleanState.Saving) {
            restartAutoCollapse(60_000L)
        } else if (isShowing) {
            actions.collapse()
        }
    }

    // --- размеры ---
    private var hasMessages = false
    private var chatContentPx = 0

    // --- чат ---
    private val chatWeb = WebView(ctx)
    private var chatReady = false
    private var pendingChat: ChatUiState? = null
    private var renderedConversation = -1L
    private val rendered = HashMap<Long, ChatMessage>()

    // --- чистовик ---
    private var previewView: CleanView? = null
    private var previewJson: String? = null
    private val thumbCache = HashMap<ByteArray, Bitmap?>()
    private var lastCleanState: CleanState = CleanState.Idle
    private var busy = false

    /** Окно, в которое PdfExporter временно встраивает WebView для экспорта. */
    val exportHost: ViewGroup get() = root

    init {
        card.background = glow
        val g = glow.glow
        card.setPadding(g + dp(14), g + dp(2), g + dp(14), g + dp(14))
        // Значки в пилюлях поменьше, чем 24dp по умолчанию.
        quickButtons.forEach { b ->
            val d = b.compoundDrawablesRelative[0]?.mutate() ?: return@forEach
            d.setBounds(0, 0, dp(18), dp(18))
            b.setCompoundDrawablesRelative(d, null, null, null)
        }
        setupChat()
        setupButtons()
        setupHeaderSwipe()
        setupInput()
        root.onOutsideTouch = { onOutsideTouch() }
        root.onInsideTouch = { wakeUp() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // Клавиатура: окно растёт вверх, и поле ввода остаётся над ней.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = ime)
            insets
        }
    }

    private fun dp(v: Int) = OverlayWindows.dp(ctx, v.toFloat())

    /** Настройки поведения окна (из настроек приложения). */
    fun setWindowPrefs(writingAlpha: Float, autoCollapseMin: Int, bubbleOnLeft: Boolean) {
        this.writingAlpha = writingAlpha.coerceIn(0.3f, 1f)
        val changedSide = this.bubbleOnLeft != bubbleOnLeft
        this.bubbleOnLeft = bubbleOnLeft
        if (this.autoCollapseMin != autoCollapseMin) {
            this.autoCollapseMin = autoCollapseMin
            restartAutoCollapse()
        }
        if (writing) card.alpha = this.writingAlpha
        if (changedSide) onScreenChanged()
    }

    // ---------------- пишу мимо окна ----------------

    /** Человек коснулся Touchnotes: прячем клавиатуру, карточку делаем полупрозрачной, но не убираем. */
    private fun onOutsideTouch() {
        setFocusable(false)
        if (!writing && writingAlpha < 1f) {
            writing = true
            card.animate().alpha(writingAlpha).setDuration(250).start()
        }
    }

    /** Касание карточки или новый ответ — снова непрозрачная, таймер сворачивания заново. */
    private fun wakeUp() {
        if (writing) {
            writing = false
            card.animate().alpha(1f).setDuration(150).start()
        }
        restartAutoCollapse()
    }

    private fun restartAutoCollapse(delayMs: Long = autoCollapseMin * 60_000L) {
        root.removeCallbacks(autoCollapse)
        if (isShowing && autoCollapseMin > 0) root.postDelayed(autoCollapse, delayMs)
    }

    // ---------------- окно ----------------

    fun show() {
        if (isShowing) return
        layoutForScreen()
        params.alpha = 1f
        try {
            wm.addView(root, params)
            isShowing = true
        } catch (_: Exception) {
            return
        }
        writing = false
        card.alpha = 0f
        card.translationY = dp(60).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        restartAutoCollapse()
    }

    fun hide() {
        if (!isShowing) return
        root.removeCallbacks(autoCollapse)
        setFocusable(false)
        runCatching { wm.removeView(root) }
        isShowing = false
    }

    /** На время снимка панель становится полностью прозрачной. */
    fun setCaptureHidden(hidden: Boolean) {
        if (!isShowing) return
        params.alpha = if (hidden) 0f else 1f
        runCatching { wm.updateViewLayout(root, params) }
    }

    fun onScreenChanged() {
        if (!isShowing) return
        layoutForScreen()
        runCatching { wm.updateViewLayout(root, params) }
    }

    /**
     * Карточка у нижнего края: в альбомной — у той стороны, где кнопка (чтобы середина листа
     * оставалась свободной), в портретной — по центру во всю ширину. Высота — по содержимому.
     */
    private fun layoutForScreen() {
        val size = OverlayWindows.screenSize(ctx)
        landscape = OverlayWindows.isLandscape(ctx)
        val margin = dp(8)
        if (landscape) {
            params.width = (size.x * 0.46f).toInt().coerceIn(dp(360), dp(640)).coerceAtMost(size.x)
            params.gravity = Gravity.BOTTOM or (if (bubbleOnLeft) Gravity.START else Gravity.END)
        } else {
            params.width = (size.x - 2 * margin).coerceAtMost(dp(780))
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = if (landscape) margin else 0
        params.y = margin
        updateContentSize()
    }

    /** Высота области переписки/чистовика: по содержимому, но не больше доли экрана. */
    private fun updateContentSize() {
        val size = OverlayWindows.screenSize(ctx)
        val maxChat = (size.y * if (landscape) 0.5f else 0.38f).toInt()
        val cleanHeight = (size.y * if (landscape) 0.7f else 0.56f).toInt()
        val h = when {
            lastCleanState !is CleanState.Idle -> cleanHeight
            hasMessages -> chatContentPx.coerceIn(dp(90), maxChat)
            else -> 0
        }
        contentFrame.isVisible = h > 0
        val lp = contentFrame.layoutParams
        if (lp.height != h) {
            lp.height = h
            contentFrame.layoutParams = lp
        }
    }

    /** Поле ввода: на время набора окно получает фокус, чтобы открылась клавиатура. */
    private fun setFocusable(focusable: Boolean) {
        val f = params.flags
        val want = if (focusable) f and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!focusable) {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        if (want != f) {
            params.flags = want
            if (isShowing) runCatching { wm.updateViewLayout(root, params) }
        }
        if (focusable) {
            input.post {
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // ---------------- настройка ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupChat() {
        WebViews.setupLocal(chatWeb)
        chatWeb.setBackgroundColor(android.graphics.Color.WHITE)
        chatWeb.addJavascriptInterface(ChatBridge(), "NativeChat")
        chatHost.addView(chatWeb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chatWeb.loadUrl("file:///android_asset/chat.html")
    }

    private inner class ChatBridge {
        @JavascriptInterface
        fun onReady() {
            root.post {
                chatReady = true
                renderedConversation = -1L
                pendingChat?.let { renderChat(it) }
            }
        }

        /** Страница сообщает высоту переписки в CSS-пикселях — карточка подстраивается. */
        @JavascriptInterface
        fun onHeight(cssPx: Float) {
            root.post {
                chatContentPx = (cssPx * ctx.resources.displayMetrics.density).toInt()
                updateContentSize()
            }
        }
    }

    private fun setupButtons() {
        quickButtons[0].setOnClickListener { actions.quick(QuickAction.NEXT_STEP) }
        quickButtons[1].setOnClickListener { actions.quick(QuickAction.FIND_ERROR) }
        quickButtons[2].setOnClickListener { actions.quick(QuickAction.THEORY) }
        quickButtons[3].setOnClickListener { actions.startClean() }
        btnRegion.setOnClickListener { actions.toggleRegion() }
        btnCollapse.setOnClickListener { actions.collapse() }
        root.findViewById<View>(R.id.btn_new).setOnClickListener { actions.newTask() }
        root.findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        subjectView.setOnClickListener { showSubjects(it) }
        root.findViewById<View>(R.id.done_clean).setOnClickListener { actions.startClean() }
        root.findViewById<View>(R.id.done_close).setOnClickListener { actions.dismissSolved() }
        root.findViewById<View>(R.id.status_close).setOnClickListener { hideStatus() }
        btnShot.setOnClickListener { actions.toggleAttachShot() }
        btnMic.setOnClickListener { actions.mic() }
        btnSend.setOnClickListener { send() }

        cAdd.setOnClickListener { actions.cleanAddShot() }
        cRemove.setOnClickListener { actions.cleanRemoveShot() }
        cCancel.setOnClickListener { actions.cleanCancel() }
        cPrimary.setOnClickListener {
            when (val s = lastCleanState) {
                is CleanState.Collecting -> actions.cleanBuild()
                is CleanState.Preview -> actions.cleanSave()
                is CleanState.Saved -> actions.cleanResend()
                is CleanState.Failed -> if (s.shots.isNotEmpty()) actions.cleanBuild() else actions.startClean()
                else -> Unit
            }
        }
        cSecondary.setOnClickListener {
            when (val s = lastCleanState) {
                is CleanState.Preview -> actions.cleanBuild()
                is CleanState.Saved -> actions.cleanSendPng()
                is CleanState.Failed -> if (s.needsSettings) actions.openSettings() else actions.cleanBackToPreview()
                else -> Unit
            }
        }
    }

    /** Смахни карточку вниз за ручку или шапку — свернётся в кнопку. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHeaderSwipe() {
        var startX = 0f
        var startY = 0f
        val threshold = dp(60)
        val listener = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    card.translationY = (e.rawY - startY).coerceAtLeast(0f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (e.actionMasked == MotionEvent.ACTION_UP && dy > threshold && abs(dy) > abs(dx)) {
                        card.animate().translationY(card.height.toFloat()).alpha(0f).setDuration(150).withEndAction {
                            card.translationY = 0f
                            card.alpha = 1f
                            actions.collapse()
                        }.start()
                    } else {
                        card.animate().translationY(0f).setDuration(150).start()
                    }
                    true
                }
                else -> false
            }
        }
        handleArea.setOnTouchListener(listener)
        header.setOnTouchListener(listener)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInput() {
        input.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                setFocusable(true)
            }
            false
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                send()
                true
            } else {
                false
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        input.setText("")
        setFocusable(false)
        actions.askTyped(text, attachShot)
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "История задач и чистовиков")
        menu.menu.add(0, 2, 1, "Настройки")
        menu.menu.add(0, 3, 2, "Свернуть в кнопку")
        menu.menu.add(0, 4, 3, "Выключить наставника")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> actions.openHistory()
                2 -> actions.openSettings()
                3 -> actions.collapse()
                4 -> actions.stopService()
            }
            true
        }
        menu.show()
    }

    private var subjects: List<String> = emptyList()

    private fun showSubjects(anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        subjects.forEachIndexed { i, s -> menu.menu.add(0, i, i, s) }
        menu.menu.add(0, 1000, 1000, "Другой — в настройках…")
        menu.setOnMenuItemClickListener {
            if (it.itemId == 1000) actions.openSettings() else actions.pickSubject(subjects[it.itemId])
            true
        }
        menu.show()
    }

    // ---------------- состояние ----------------

    private var attachShot = true

    fun setOptions(subject: String, allSubjects: List<String>, regionMode: Boolean, attach: Boolean, aiLabel: String) {
        titleView.text = "Наставник · $aiLabel"
        subjectView.text = subject
        subjects = (allSubjects + subject).distinct()
        btnRegion.isSelected = regionMode
        btnRegion.setColorFilter(ContextCompat.getColor(ctx, if (regionMode) R.color.accent else R.color.text_muted))
        btnRegion.contentDescription = if (regionMode) "Выделять область: включено" else "Выделять область: выключено"
        attachShot = attach
        btnShot.setColorFilter(ContextCompat.getColor(ctx, if (attach) R.color.accent else R.color.text_muted))
        btnShot.alpha = if (attach) 1f else 0.6f
    }

    fun setListening(listening: Boolean) {
        btnMic.setColorFilter(ContextCompat.getColor(ctx, if (listening) R.color.error_text else R.color.text_muted))
        input.hint = if (listening) "Говори, я слушаю…" else "Спросить наставника"
    }

    fun setInputText(text: String) {
        input.setText(text)
        input.setSelection(text.length)
    }

    fun showStatus(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        statusText.text = text
        statusBar.isVisible = true
        statusAction.isVisible = actionLabel != null
        statusAction.text = actionLabel
        statusAction.setOnClickListener {
            hideStatus()
            action?.invoke()
        }
    }

    fun hideStatus() {
        statusBar.isVisible = false
    }

    fun renderChat(state: ChatUiState) {
        // Ответ дописан — карточка снова непрозрачная, чтобы его было удобно читать.
        if (busy && !state.busy) wakeUp()
        busy = state.busy
        glow.thinking = state.busy || lastCleanState is CleanState.Generating || lastCleanState is CleanState.Saving
        val nowHasMessages = state.messages.isNotEmpty()
        if (nowHasMessages != hasMessages) {
            hasMessages = nowHasMessages
            if (!hasMessages) chatContentPx = 0
            updateContentSize()
        }
        doneBanner.isVisible = state.solved && lastCleanState is CleanState.Idle
        quickButtons.forEach { it.isEnabled = !state.busy && lastCleanState !is CleanState.Generating && lastCleanState !is CleanState.Saving }
        btnSend.isEnabled = !state.busy
        btnSend.alpha = if (state.busy) 0.4f else 1f
        if (!chatReady) {
            pendingChat = state
            return
        }
        pendingChat = null
        if (renderedConversation == -1L) {
            // Первая отрисовка после загрузки страницы — весь список разом. Дальше — только изменения.
            renderedConversation = state.conversationId
            rendered.clear()
            state.messages.forEach { rendered[it.id] = it }
            js("Chat.setMessages(${JsonArray(state.messages.map { it.toJson() })})")
            return
        }
        val ids = state.messages.map { it.id }.toSet()
        rendered.keys.filter { it !in ids }.forEach { id ->
            rendered.remove(id)
            js("Chat.remove($id)")
        }
        for (m in state.messages) {
            if (rendered[m.id] != m) {
                rendered[m.id] = m
                js("Chat.upsert(${m.toJson()})")
            }
        }
    }

    private fun ChatMessage.toJson() = buildJsonObject {
        put("id", id)
        put("role", role)
        put("text", text)
        put("hadImage", hadImage)
        put("error", isError)
        put("streaming", streaming)
        status?.let { put("status", it) }
    }

    private fun js(code: String) = chatWeb.evaluateJavascript(code, null)

    fun renderClean(state: CleanState) {
        val wasIdle = lastCleanState is CleanState.Idle
        lastCleanState = state
        glow.thinking = busy || state is CleanState.Generating || state is CleanState.Saving
        if (wasIdle != (state is CleanState.Idle)) updateContentSize()
        val active = state !is CleanState.Idle
        cleanBox.isVisible = active
        chatHost.isVisible = !active
        if (active) doneBanner.isVisible = false
        if (!active) {
            thumbCache.clear()
            cleanThumbs.removeAllViews()
            return
        }
        renderThumbs(state.shots)
        val n = state.shots.size
        fun buttons(add: Boolean = false, remove: Boolean = false, primary: String? = null, secondary: String? = null, cancel: String? = "Отмена", enabled: Boolean = true) {
            cAdd.isVisible = add
            cRemove.isVisible = remove
            cPrimary.isVisible = primary != null
            cPrimary.text = primary
            cSecondary.isVisible = secondary != null
            cSecondary.text = secondary
            cCancel.isVisible = cancel != null
            cCancel.text = cancel
            listOf(cAdd, cRemove, cPrimary, cSecondary).forEach { it.isEnabled = enabled }
        }
        var showPreview = false
        when (state) {
            is CleanState.Collecting -> {
                cleanTitle.text = "Чистовик · снимков: $n из ${CleanState.MAX_SHOTS}"
                cleanMessage.text = when {
                    state.capturing -> "Делаю снимок экрана…"
                    state.message != null -> state.message
                    else -> "Если конспект длиннее одного экрана, прокрути Touchnotes и нажми «+ Добавить экран». Когда всё готово — «Собрать чистовик»."
                }
                buttons(add = n < CleanState.MAX_SHOTS, remove = n > 0, primary = "Собрать чистовик", enabled = !state.capturing)
                cAdd.isEnabled = !state.capturing && n < CleanState.MAX_SHOTS
                cPrimary.isEnabled = !state.capturing && n > 0
                cCancel.isEnabled = true
            }
            is CleanState.Generating -> {
                cleanTitle.text = "Собираю чистовик…"
                cleanMessage.text = "Нейросеть оформляет конспект по снимкам ($n). Обычно это занимает до минуты." +
                    if (state.retry) "\nПервый ответ не разобрался — пробую ещё раз." else ""
                buttons()
            }
            is CleanState.Preview -> {
                showPreview = true
                showPreviewJson(state.json)
                cleanTitle.text = NoteStorage.plainTitle(state.note.title).ifBlank { "Чистовик" }
                cleanMessage.text = "Проверь предпросмотр. «Сохранить» — PDF и PNG в «Документы/Конспекты» и отправка в Touchnotes."
                buttons(primary = "Сохранить", secondary = "Пересобрать")
            }
            is CleanState.Saving -> {
                showPreview = true
                showPreviewJson(state.json)
                cleanMessage.text = "Сохраняю PDF и картинки…"
                buttons(primary = "Сохранить", secondary = "Пересобрать", enabled = false)
            }
            is CleanState.Saved -> {
                showPreview = true
                showPreviewJson(state.json)
                cleanTitle.text = "Готово: " + NoteStorage.plainTitle(state.note.title)
                cleanMessage.text = state.sendMessage
                buttons(primary = "Отправить ещё раз", secondary = "Отправить картинками", cancel = "Закрыть")
            }
            is CleanState.Failed -> {
                showPreview = state.json != null
                state.json?.let(::showPreviewJson)
                cleanTitle.text = "Не получилось"
                cleanMessage.text = state.message
                buttons(
                    primary = if (state.shots.isNotEmpty()) "Пересобрать" else "Снять экран заново",
                    secondary = when {
                        state.needsSettings -> "Настройки"
                        state.note != null -> "К предпросмотру"
                        else -> null
                    },
                )
            }
            CleanState.Idle -> Unit
        }
        cleanPreviewHost.isVisible = showPreview
        quickButtons.forEach { it.isEnabled = !busy && state !is CleanState.Generating && state !is CleanState.Saving }
    }

    private fun showPreviewJson(json: String) {
        // Контейнер видим до отрисовки — иначе страница сверстается под нулевую ширину.
        cleanPreviewHost.isVisible = true
        val view = previewView ?: CleanView(WebView(ctx), "preview").also { v ->
            cleanPreviewHost.addView(v.webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            previewView = v
        }
        if (previewJson != json) {
            previewJson = json
            view.show(json)
        }
    }

    private fun renderThumbs(shots: List<ByteArray>) {
        if (cleanThumbs.childCount == shots.size && thumbCache.keys.containsAll(shots)) return
        cleanThumbs.removeAllViews()
        val h = OverlayWindows.dp(ctx, 64f)
        shots.forEachIndexed { i, jpeg ->
            val bmp = thumbCache.getOrPut(jpeg) { ImageUtils.thumbnail(jpeg, h * 2) }
            val iv = ImageView(ctx).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Снимок ${i + 1}"
                setBackgroundResource(R.drawable.input_bg)
                setPadding(2, 2, 2, 2)
            }
            cleanThumbs.addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h).apply {
                marginEnd = OverlayWindows.dp(ctx, 6f)
            })
        }
        thumbCache.keys.retainAll(shots.toSet())
    }

    fun destroy() {
        glow.thinking = false
        hide()
        chatWeb.destroy()
        previewView?.webView?.destroy()
    }
}
