package app.nastavnik.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nastavnik.app
import app.nastavnik.study.Event
import app.nastavnik.study.FocusFeed
import app.nastavnik.study.FocusFeed.Mode
import kotlinx.coroutines.delay
import java.time.LocalDate

private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
/** Как обычный Chrome на телефоне: встроенное окно с пометкой «wv» TikTok может не показывать вовсе (белый экран). */
private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * «Лента»: настоящий TikTok (вход один раз, куки сохраняются), но по правилам учёбы — см. [FocusFeed].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FeedScreen() {
    val context = LocalContext.current
    val app = context.app
    var mode by remember { mutableStateOf(Mode.STUDY) }
    var message by remember { mutableStateOf<String?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty().contains("sessionid")) }
    // Защита от зацикливания: если TikTok раз за разом уводит на запрещённую страницу — не воюем, а объясняем.
    var bounces by remember { mutableStateOf(0) }
    var lastBounce by remember { mutableLongStateOf(0L) }
    val nowMs = remember { System.currentTimeMillis() }
    val activity by remember { app.db.activity().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val due by remember(nowMs) { app.db.cards().observeDueCount(nowMs) }.collectAsStateWithLifecycle(initialValue = 0)
    val events = remember(activity) { activity.map { Event(it.kind, it.topic, it.errors, it.at) } }
    var used by remember { mutableLongStateOf(app.settings.feedUsedToday()) }
    val gate = remember(events, due, used) { FocusFeed.gate(events, due, System.currentTimeMillis(), used) }
    var subjects by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val week = (0L..6L).flatMap { app.schedule.day(LocalDate.now().plusDays(it)) }.map { it.discipline }
        val cardSubjects = runCatching { app.db.cards().all().map { it.subject.ifBlank { it.topic } } }.getOrDefault(emptyList())
        subjects = (week + cardSubjects).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val queries = remember(events, subjects) { FocusFeed.queries(events, subjects, System.currentTimeMillis()) }
    var query by remember(queries) { mutableStateOf(queries.firstOrNull()) }

    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = MOBILE_UA
        }
    }
    // Правила для текущего режима: лишнее — назад к разрешённой странице.
    val rewardActive = mode == Mode.REWARD && gate.unlocked
    fun home(m: Mode): String = when (m) {
        Mode.MESSAGES -> FocusFeed.MESSAGES_URL
        Mode.REWARD -> FocusFeed.FEED_URL
        Mode.STUDY -> query?.let { FocusFeed.searchUrl(it) } ?: FocusFeed.searchUrl("наука объяснение")
    }
    web.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
    }
    web.webViewClient = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) loadError = "TikTok не загрузился: ${error.description}. Проверь интернет и нажми «Обновить»."
        }

        override fun onPageFinished(view: WebView, url: String) {
            loggedIn = CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty().contains("sessionid")
            CookieManager.getInstance().flush()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val ok = FocusFeed.allowed(request.url.toString(), mode, rewardActive)
            if (!ok) message = blockedText(mode, gate)
            return !ok
        }

        // TikTok — одностраничный сайт: переходы внутри него тоже проверяем.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!FocusFeed.allowed(url, mode, rewardActive)) {
                val now = System.currentTimeMillis()
                bounces = if (now - lastBounce < 8_000) bounces + 1 else 1
                lastBounce = now
                if (bounces <= 2) {
                    message = blockedText(mode, gate)
                    view.post { view.loadUrl(home(mode)) }
                } else {
                    // TikTok упорно ведёт на ленту (например, без входа) — останавливаемся и показываем вход.
                    view.stopLoading()
                    loadError = if (!loggedIn) "TikTok просит войти. Нажми «Войти в TikTok» — это нужно один раз." else blockedText(mode, gate)
                }
            }
            canBack = view.canGoBack()
            CookieManager.getInstance().flush()
        }
    }
    LaunchedEffect(mode, query) {
        // Сообщения у TikTok полноценно работают в версии для компьютера.
        web.settings.userAgentString = if (mode == Mode.MESSAGES) DESKTOP_UA else MOBILE_UA
        loadError = null
        bounces = 0
        web.loadUrl(home(mode))
    }
    // Награда идёт по времени: кончилось — обратно к учёбе.
    LaunchedEffect(mode, gate.unlocked) {
        while (mode == Mode.REWARD && gate.unlocked) {
            delay(5_000)
            used = app.settings.addFeedUsed(5_000)
        }
        if (mode == Mode.REWARD && !gate.unlocked) { mode = Mode.STUDY; message = "Время обычной ленты на сегодня вышло — завтра снова" }
    }
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush(); web.destroy() } }
    BackHandler(enabled = canBack) { web.goBack() }

    Column(Modifier.fillMaxSize().background(Brand.Paper)) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!loggedIn) {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), 6.dp, strong = true).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Войди в TikTok один раз — сообщения и видео сохранятся", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    InkButton("Войти") { loadError = null; bounces = 0; web.loadUrl("https://www.tiktok.com/login") }
                }
            }
            ModeSwitch(mode, gate) { m ->
                if (m == Mode.REWARD && !gate.unlocked) message = blockedText(Mode.STUDY, gate) else mode = m
            }
            AnimatedVisibility(mode == Mode.STUDY && queries.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    queries.forEach { q -> ChipButton(q.removeSuffix(" объяснение"), selected = q == query) { query = q } }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "TikTok" })
            // Полоса загрузки — чтобы пустой экран не казался зависанием.
            if (progress in 1..99) {
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth(progress / 100f).height(3.dp).background(Brand.Accent))
            }
            loadError?.let { err ->
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 12.dp, strong = true).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton("Войти в TikTok") { loadError = null; bounces = 0; web.loadUrl("https://www.tiktok.com/login") }
                        SoftButton("Обновить") { loadError = null; bounces = 0; web.loadUrl(home(mode)) }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                message != null,
                enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    message.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), 10.dp, strong = true).clickable { message = null }.padding(14.dp),
                )
            }
        }
    }
    LaunchedEffect(message) { if (message != null) { delay(4500); message = null } }
}

private fun blockedText(mode: Mode, gate: FocusFeed.Gate): String = when {
    mode == Mode.MESSAGES -> "Здесь только сообщения — огоньки в безопасности"
    gate.todo.isNotEmpty() -> "Обычная лента закрыта. Чтобы открыть на ${FocusFeed.REWARD_MINUTES} мин: " + gate.todo.joinToString("; ")
    gate.minutesLeft <= 0 -> "Время обычной ленты на сегодня вышло — завтра снова"
    else -> "В «Учёбе» — только видео по твоим темам. Обычная лента — во вкладке «Награда»"
}

/** Переключатель «Учёба / Сообщения / Награда» с пружинящей подложкой; у «Награды» — замок или оставшиеся минуты. */
@Composable
private fun ModeSwitch(mode: Mode, gate: FocusFeed.Gate, onPick: (Mode) -> Unit) {
    val items = listOf(Mode.STUDY to "Учёба", Mode.MESSAGES to "Сообщения", Mode.REWARD to if (gate.unlocked) "Награда · ${gate.minutesLeft} мин" else "Награда 🔒")
    BoxWithConstraints(Modifier.fillMaxWidth().height(46.dp).glass(RoundedCornerShape(23.dp), 8.dp, strong = true)) {
        val cell = maxWidth / items.size
        val x by animateDpAsState(cell * items.indexOfFirst { it.first == mode }, spring(dampingRatio = 0.68f, stiffness = 420f), label = "feedMode")
        Box(Modifier.offset(x = x).width(cell).height(46.dp).padding(4.dp).clip(RoundedCornerShape(19.dp)).background(Brand.Accent))
        Row(Modifier.fillMaxSize()) {
            items.forEach { (m, label) ->
                Box(Modifier.width(cell).fillMaxSize().clip(RoundedCornerShape(19.dp)).clickable { onPick(m) }, contentAlignment = Alignment.Center) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (m == mode) Brand.OnAccent else if (m == Mode.REWARD && !gate.unlocked) Brand.InkSoft else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
