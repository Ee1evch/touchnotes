package app.nastavnik.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

class ExportException(message: String) : Exception(message)

/**
 * Превращает чистовик в PDF и PNG-страницы.
 *
 * Отдельный WebView шириной ровно в лист A4 вставляется в окно [host] внутри контейнера 1×1 px
 * (пользователь его не видит, но WebView остаётся «живым» и честно верстает страницу).
 * Затем каждый лист рисуется в Bitmap через [WebView.draw] — для этого в приложении
 * включён [WebView.enableSlowWholeDocumentDraw].
 */
class PdfExporter(private val context: Context) {

    data class Result(val pdf: File, val pngs: List<File>, val pageCount: Int)

    /** @param baseName имя файлов без расширения — под ним PDF увидит приложение для записей. */
    suspend fun export(host: ViewGroup, noteJson: String, outDir: File, baseName: String = "Конспект"): Result {
        outDir.mkdirs()
        val density = context.resources.displayMetrics.density
        val widthPx = ceil(CleanView.PAGE_W_CSS * density).toInt()
        val pageHpx = CleanView.PAGE_H_CSS * density

        val container = FrameLayout(context)
        val webView = WebView(context)
        container.addView(webView, FrameLayout.LayoutParams(widthPx, ceil(pageHpx).toInt()))
        host.addView(container, ViewGroup.LayoutParams(1, 1))
        try {
            val cleanView = CleanView(webView, "export")
            val pages = withTimeout(RENDER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    cleanView.onRendered = { n -> if (cont.isActive) cont.resume(n) }
                    cleanView.onError = { msg -> if (cont.isActive) cont.resumeWithException(ExportException(msg)) }
                    cleanView.show(noteJson)
                }
            }
            if (pages <= 0) throw ExportException("Пустой чистовик")

            // Растягиваем WebView на все листы и ждём, пока он перерисуется.
            webView.layoutParams = webView.layoutParams.apply { height = ceil(pages * pageHpx).toInt() }
            webView.requestLayout()
            delay(SETTLE_MS)

            val scale = OUT_W.toFloat() / widthPx
            val pngs = ArrayList<File>()
            val pdf = PdfDocument()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            try {
                for (i in 0 until pages) {
                    val bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    canvas.scale(scale, scale)
                    canvas.translate(0f, -i * pageHpx)
                    webView.draw(canvas)

                    val png = File(outDir, if (pages == 1) "$baseName.png" else "$baseName — стр. ${i + 1}.png")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    pngs += png

                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W_PT, A4_H_PT, i + 1).create())
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, A4_W_PT.toFloat(), A4_H_PT.toFloat()), paint)
                    pdf.finishPage(page)
                    bmp.recycle()
                }
                val pdfFile = File(outDir, "$baseName.pdf")
                withContext(Dispatchers.IO) { FileOutputStream(pdfFile).use { pdf.writeTo(it) } }
                return Result(pdfFile, pngs, pages)
            } finally {
                pdf.close()
            }
        } finally {
            host.removeView(container)
            webView.destroy()
        }
    }

    companion object {
        /** Ширина PNG-страницы: ≈200 dpi для A4. */
        const val OUT_W = 1654
        const val OUT_H = 2339
        const val A4_W_PT = 595
        const val A4_H_PT = 842
        private const val RENDER_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 700L
    }
}
