package app.nastavnik.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.nastavnik.R
import app.nastavnik.app
import app.nastavnik.grades.LksExtract
import app.nastavnik.grades.SessionCookies
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

const val LKS_URL = "https://lks.siriusuniversity.ru"

/**
 * Встроенное окно ЛКС. Сайт пускает только с российских адресов, поэтому работает именно с телефона.
 * На форме входа стоит Яндекс SmartCaptcha: окно подставляет почту и пароль из настроек, а «Войти» нажимает,
 * когда человек отметит «Я не робот». По просьбе снимает таблицы со страницы.
 */
class LksWeb(private val context: android.content.Context, private val onCapture: (String, String) -> Unit) {
    var onPage: ((String) -> Unit)? = null
    private var loginPages = 0

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(object {
            @JavascriptInterface fun capture(url: String, text: String) = onCapture(url, text)
        }, "NastavnikLks")
        // Перехват ответов сайта — как можно раньше, до скриптов страницы.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(this, LksExtract.CAPTURE_JS, setOf(LKS_URL)) }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(LksExtract.CAPTURE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                val app = context.app
                if (lksPath(url)?.startsWith("/login") == true) {
                    // Пароль подставляем один раз: если сайт его не принял, второй раз не отправляем — человек введёт сам.
                    val password = if (loginPages++ == 0) app.settings.lksPassword() else ""
                    view.postDelayed({ view.evaluateJavascript(LksExtract.loginJs(app.settings.lksLogin(), password), null) }, 700)
                }
                onPage?.invoke(url)
            }
        }
    }

    fun load(url: String) { loginPages = 0; view.loadUrl(url) }

    /** Таблицы со страницы как JSON (или null). */
    fun extract(done: (String?) -> Unit) {
        view.evaluateJavascript(LksExtract.EXTRACT_JS) { raw ->
            done(runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull())
        }
    }
}

private val lksHttpUrl = LKS_URL.toHttpUrl()

/** Путь страницы ЛКС (null — чужой сайт, например капча). */
private fun lksPath(url: String) = url.toHttpUrlOrNull()?.takeIf { it.host == lksHttpUrl.host }?.encodedPath

/** Вход, регистрация, восстановление пароля — пока окно здесь, входа ещё нет. */
private fun isAuthPage(url: String) = lksPath(url)
    ?.let { p -> listOf("/login", "/register", "/forgot-password", "/reset-password").any { p.startsWith(it) } } == true

/** Забыть вход в ЛКС и во встроенном окне (при «Отключить»). */
fun clearLksWebCookies() {
    val cm = CookieManager.getInstance()
    cm.getCookie(LKS_URL).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        .forEach { cm.setCookie(LKS_URL, "$it=; Max-Age=0; Path=/") }
    cm.flush()
}

/**
 * Сайт ЛКС внутри приложения. Если сессия жива — сразу журнал; если нет — форма входа (почта и пароль
 * подставлены, остаётся капча). Как только окно ушло со страницы входа, его куки отдаются приложению,
 * и дальше журнал загружается без окна.
 */
@Composable
fun LksScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var onLogin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val web = remember { LksWeb(context) { _, _ -> } }
    DisposableEffect(Unit) {
        val cm = CookieManager.getInstance()
        // Только живые куки: истёкшая запись затёрла бы в окне более свежую сессию.
        SessionCookies.parse(app.settings.lksCookies(), lksHttpUrl).loadForRequest(lksHttpUrl).forEach { cm.setCookie(LKS_URL, it.toString()) }
        cm.flush()
        var sawLogin = false
        var busy = false
        web.onPage = { url ->
            onLogin = isAuthPage(url)
            if (onLogin) { sawLogin = true; status = null }
            val take = !onLogin && lksPath(url) != null &&
                (sawLogin || !app.settings.lksConfigured || app.grades.lksNeedsLogin.value)
            if (take && !busy) {
                busy = true
                status = "Вход выполнен, загружаю журнал…"
                scope.launch {
                    runCatching { app.grades.connectLksWeb(CookieManager.getInstance().getCookie(LKS_URL).orEmpty()) }
                        .onSuccess { status = null; onDone() }
                        .onFailure { status = it.message ?: "Не удалось загрузить журнал" }
                    sawLogin = false
                    busy = false
                }
            }
        }
        web.load("$LKS_URL/journal")
        onDispose { web.onPage = null; web.view.destroy() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIconButton(R.drawable.ic_arrow_back, "Назад") { if (web.view.canGoBack()) web.view.goBack() else onBack() }
            Text("ЛКС", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        val hint = status ?: if (onLogin) "Отметь «Я не робот» — «Войти» нажму сам. «Запомнить меня» уже отмечено, чтобы входить реже." else null
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            AndroidView(factory = { web.view }, modifier = Modifier.fillMaxSize())
        }
    }
}
