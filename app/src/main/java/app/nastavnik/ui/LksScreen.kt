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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

const val LKS_URL = "https://lks.siriusuniversity.ru"

/**
 * Встроенное окно ЛКС. Сайт пускает только с российских адресов, поэтому работает именно с телефона:
 * сам входит по логину и паролю из настроек, запоминает куки, по просьбе снимает таблицы со страницы.
 */
class LksWeb(private val context: android.content.Context, private val autoLogin: Boolean = false, private val onCapture: (String, String) -> Unit) {
    var onPage: ((String) -> Unit)? = null
    private var loginTries = 0

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
                if (autoLogin && url.contains("/login") && app.settings.lksPassword().isNotEmpty() && loginTries < 2) {
                    loginTries++
                    view.postDelayed({ view.evaluateJavascript(LksExtract.loginJs(app.settings.lksLogin(), app.settings.lksPassword()), null) }, 700)
                    return
                }
                onPage?.invoke(url)
            }
        }
    }

    fun load(url: String) { loginTries = 0; view.loadUrl(url) }

    /** Таблицы со страницы как JSON (или null). */
    fun extract(done: (String?) -> Unit) {
        view.evaluateJavascript(LksExtract.EXTRACT_JS) { raw ->
            done(runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull())
        }
    }
}

/**
 * Вход в ЛКС внутри приложения: на сайте капча, поэтому входит сам человек (почта, пароль, «я не робот»).
 * Галочку «Запомнить меня» ставим за него. Как только сайт пустил дальше страницы входа — берём куки
 * сессии, и дальше оценки приложение забирает само, без этого окна.
 */
@Composable
fun LksScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Войди как обычно: почта, пароль и «я не робот». Это нужно один раз.") }
    var done by remember { mutableStateOf(false) }
    val web = remember { LksWeb(context, autoLogin = false) { _, _ -> } }
    DisposableEffect(Unit) {
        web.onPage = { url ->
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            if (path.startsWith("/login") || path.isEmpty()) {
                // «Запомнить меня» — чтобы сессия жила долго и входить приходилось редко.
                web.view.evaluateJavascript("(function(){var r=document.querySelector(\"input[name='remember']\");if(r&&!r.checked){r.click();}})()", null)
            } else if (!done && url.startsWith(LKS_URL)) {
                done = true
                CookieManager.getInstance().flush()
                val cookies = CookieManager.getInstance().getCookie(LKS_URL).orEmpty()
                status = "Вошёл. Забираю оценки…"
                scope.launch {
                    app.grades.connectLksSession(cookies)
                    onDone()
                }
            }
        }
        web.load("$LKS_URL/login")
        onDispose { web.view.destroy() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIconButton(R.drawable.ic_arrow_back, "Назад") { if (web.view.canGoBack()) web.view.goBack() else onBack() }
            Column(Modifier.weight(1f)) {
                Text("Вход в ЛКС", style = MaterialTheme.typography.titleLarge)
                Text(status, style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
            }
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            AndroidView(factory = { web.view }, modifier = Modifier.fillMaxSize())
        }
    }
}
