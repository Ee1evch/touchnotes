package app.nastavnik.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nastavnik.notes.WebViews
import kotlinx.serialization.json.JsonPrimitive

/**
 * Текст с формулами (Markdown + KaTeX) — тот же движок, что у заметок наставника.
 * Высота подстраивается под содержимое, поэтому текст можно ставить в обычную колонку.
 */
@Composable
fun RichText(text: String, modifier: Modifier = Modifier, sizePx: Int = 17, center: Boolean = false) {
    var heightDp by remember { mutableIntStateOf(24) }
    val holder = remember { RichHolder { heightDp = it } }
    val dark = Brand.dark
    DisposableEffect(Unit) { onDispose { holder.destroy() } }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(heightDp.dp),
        factory = { ctx -> holder.create(ctx) },
        update = { holder.show(text, sizePx, center, dark) },
    )
}

private class RichHolder(private val heightChanged: (Int) -> Unit) {
    private var web: WebView? = null
    private var ready = false
    private var shown: String? = null
    private var pending: Triple<String, Int, Boolean>? = null
    private var pendingDark = false

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: android.content.Context): WebView = WebView(ctx).also { w ->
        web = w
        WebViews.setupLocal(w)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.addJavascriptInterface(Bridge(), "NativeNote")
        w.loadUrl("file:///android_asset/note.html")
    }

    fun show(text: String, sizePx: Int, center: Boolean, dark: Boolean) {
        val key = "$sizePx|$center|$dark|$text"
        if (key == shown) return
        if (!ready) {
            pending = Triple(text, sizePx, center)
            pendingDark = dark
            return
        }
        shown = key
        // В тёмной теме текст светлый; формулы KaTeX наследуют цвет.
        val color = if (dark) "#EDECF1" else "#16161a"
        web?.evaluateJavascript("Note.style($sizePx, $center); document.body.style.color='$color'; Note.set(${JsonPrimitive(text)}, false)", null)
    }

    fun destroy() {
        web?.destroy()
        web = null
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            web?.post {
                ready = true
                pending?.let { (t, s, c) -> show(t, s, c, pendingDark) }
                pending = null
            }
        }

        @JavascriptInterface
        fun onHeight(cssPx: Float) {
            // CSS-пиксели WebView совпадают с dp.
            web?.post { heightChanged(cssPx.toInt().coerceAtLeast(20) + 4) }
        }
    }
}
