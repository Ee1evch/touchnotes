package app.nastavnik.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import app.nastavnik.capture.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Тетради приложения: хранилище, подложки-фото, импорт фото и PDF, превью и снимки страниц для наставника.
 */
class Notebooks(private val context: Context) {
    val store = NotebookStore(File(context.filesDir, "notebooks"))

    /** Подложки в памяти: страница открывается мгновенно, если её уже показывали. */
    private val bgCache = object : android.util.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun defaultTitle(now: Long = System.currentTimeMillis()): String =
        "Тетрадь · " + SimpleDateFormat("d MMMM", Locale("ru")).format(Date(now))

    /** Подложка страницы (фото или страница PDF), не больше [maxSide] пикселей по длинной стороне. */
    suspend fun background(notebookId: Long, page: InkPage, maxSide: Int = 2400): Bitmap? {
        val name = page.background ?: return null
        val key = "$notebookId/$name/$maxSide"
        bgCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = store.background(notebookId, name)
            if (!f.exists()) return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.also { bgCache.put(key, it) }
    }

    /** Снимок страницы для наставника или чистовика: картинка не больше [maxPixels]. */
    suspend fun render(notebookId: Long, page: InkPage, maxPixels: Int): Bitmap {
        val bg = background(notebookId, page)
        return withContext(Dispatchers.Default) { PageRenderer.render(page, bg, PageRenderer.widthFor(page, maxPixels)) }
    }

    suspend fun renderJpeg(notebookId: Long, page: InkPage, maxPixels: Int, quality: Int = 85): ByteArray {
        val bmp = render(notebookId, page, maxPixels)
        return try {
            withContext(Dispatchers.Default) { ImageUtils.toJpeg(bmp, quality) }
        } finally {
            bmp.recycle()
        }
    }

    /** Превью первой страницы для списка тетрадей. */
    suspend fun saveThumb(meta: NotebookMeta, first: InkPage) {
        val bmp = render(meta.id, first, 260_000)
        withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(store.thumb(meta.id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        }
        bmp.recycle()
    }

    // ---------------- импорт ----------------

    /** Новая тетрадь из фото (камера, галерея, «Поделиться»). Фото поворачиваются по EXIF сами. */
    suspend fun importImages(uris: List<Uri>, title: String? = null): NotebookMeta? = withContext(Dispatchers.IO) {
        val pages = uris.take(MAX_IMPORT).mapNotNull { pageFromImage(it) }
        if (pages.isEmpty()) return@withContext null
        val meta = store.createWith(title ?: ("Фото · " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date())), pages.map { it.first })
        pages.forEach { (p, bytes) -> store.background(meta.id, p.background!!).writeBytes(bytes) }
        meta
    }

    /** Добавить фото страницами в конец тетради. */
    suspend fun appendImages(meta: NotebookMeta, uris: List<Uri>): NotebookMeta = withContext(Dispatchers.IO) {
        var m = meta
        for (u in uris.take(MAX_IMPORT)) {
            val (p, bytes) = pageFromImage(u) ?: continue
            store.background(m.id, p.background!!).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            m = store.addPage(m, p)
        }
        m
    }

    /** Новая тетрадь из PDF: каждая страница PDF — страница тетради, поверх неё можно писать. */
    suspend fun importPdf(uri: Uri, title: String? = null): NotebookMeta? = withContext(Dispatchers.IO) {
        val pages = pagesFromPdf(uri)
        if (pages.isEmpty()) return@withContext null
        val meta = store.createWith(title ?: displayName(uri)?.removeSuffix(".pdf") ?: "PDF", pages.map { it.first })
        pages.forEach { (p, bytes) -> store.background(meta.id, p.background!!).writeBytes(bytes) }
        meta
    }

    suspend fun appendPdf(meta: NotebookMeta, uri: Uri): NotebookMeta = withContext(Dispatchers.IO) {
        var m = meta
        for ((p, bytes) in pagesFromPdf(uri)) {
            store.background(m.id, p.background!!).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            m = store.addPage(m, p)
        }
        m
    }

    private fun pageFromImage(uri: Uri): Pair<InkPage, ByteArray>? = runCatching {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val k = minOf(1f, MAX_SIDE.toFloat() / max(w, h))
            if (k < 1f) decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
        }
        val bytes = ImageUtils.toJpeg(bmp, 88)
        val page = InkPage(store.newId(), Paper.BLANK, NotebookStore.backgroundName(0), PAGE_W * bmp.height / bmp.width)
        bmp.recycle()
        withName(page) to bytes
    }.getOrNull()

    private fun pagesFromPdf(uri: Uri): List<Pair<InkPage, ByteArray>> = runCatching {
        val out = ArrayList<Pair<InkPage, ByteArray>>()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { pdf ->
                for (i in 0 until minOf(pdf.pageCount, MAX_PDF_PAGES)) {
                    pdf.openPage(i).use { pg ->
                        val w = PDF_WIDTH
                        val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val page = InkPage(store.newId(), Paper.BLANK, null, PAGE_W * h / w)
                        out += withName(page) to ImageUtils.toJpeg(bmp, 88)
                        bmp.recycle()
                    }
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    /** У страницы с подложкой имя файла подложки — по её id. */
    private fun withName(p: InkPage) = InkPage(p.id, p.paper, NotebookStore.backgroundName(p.id), p.height, p.strokes)

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Куда камера кладёт снимок (через FileProvider). */
    fun cameraFile(): File = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot_${System.currentTimeMillis()}.jpg") }

    companion object {
        private const val MAX_SIDE = 2400
        private const val PDF_WIDTH = 1600
        private const val MAX_IMPORT = 30
        private const val MAX_PDF_PAGES = 40
    }
}
