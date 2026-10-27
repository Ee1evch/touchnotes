package app.nastavnik.notes

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest

/** Общая настройка WebView: только локальные файлы из assets, без сети. */
object WebViews {
    @SuppressLint("SetJavaScriptEnabled")
    fun setupLocal(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = object : WebViewClient() {
            // Ссылки из ответа модели никуда не ведут: не открываем чужие страницы внутри панели.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
    }
}

/**
 * Обёртка над WebView с assets/clean.html: ждёт загрузки страницы и рисует JSON чистовика.
 * @param mode "preview" — листы с тенью, вписанные по ширине; "export" — листы встык для PDF.
 */
class CleanView(val webView: WebView, private val mode: String) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null

    var onRendered: ((pages: Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        WebViews.setupLocal(webView)
        webView.setBackgroundColor(if (mode == "export") Color.WHITE else Color.parseColor("#E6E9F0"))
        webView.addJavascriptInterface(Bridge(), "NativeClean")
        webView.loadUrl(URL)
    }

    fun show(noteJson: String) {
        if (ready) evaluate(noteJson) else pending = noteJson
    }

    private fun evaluate(noteJson: String) {
        // JSON — это корректный JavaScript-литерал, подставляем как есть.
        webView.evaluateJavascript("Clean.render($noteJson, '$mode');", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(@Suppress("UNUSED_PARAMETER") s: String) {
            main.post {
                ready = true
                pending?.let(::evaluate)
                pending = null
            }
        }

        @JavascriptInterface
        fun onRendered(pages: Int) {
            main.post { onRendered?.invoke(pages) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            main.post { onError?.invoke(message) }
        }
    }

    companion object {
        const val URL = "file:///android_asset/clean.html"

        /** Размер листа A4 в CSS-пикселях — должен совпадать с clean.css. */
        const val PAGE_W_CSS = 794
        const val PAGE_H_CSS = 1123
    }
}
