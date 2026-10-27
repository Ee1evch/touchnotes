package app.nastavnik.notes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранение чистовиков:
 * - рабочая копия во внутренней папке приложения (её отдаём другим приложениям через FileProvider);
 * - публичная копия в Documents/Конспекты/<Предмет>/ через MediaStore — её видно в «Файлах».
 */
class NoteStorage(private val context: Context) {

    data class Published(val publicPath: String?, val pdfUri: Uri?, val error: String?)

    fun newWorkDir(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(context.filesDir, "notes/$stamp").apply { mkdirs() }
    }

    fun publish(pdf: File, pngs: List<File>, subject: String, title: String): Published {
        val folder = sanitize(subject).ifBlank { "Без предмета" }
        val date = SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date())
        val base = sanitize(plainTitle(title)).ifBlank { "Конспект" }.take(80).trim()
        val relative = "${Environment.DIRECTORY_DOCUMENTS}/Конспекты/$folder"
        return try {
            val pdfName = "$base — $date.pdf"
            val pdfUri = insert(pdf, pdfName, "application/pdf", relative)
            pngs.forEachIndexed { i, png ->
                val name = if (pngs.size == 1) "$base — $date.png" else "$base — $date — стр. ${i + 1}.png"
                runCatching { insert(png, name, "image/png", relative) }
                    .recoverCatching {
                        // Если в «Документы» картинку не пустили — кладём в «Изображения».
                        insert(png, name, "image/png", "${Environment.DIRECTORY_PICTURES}/Конспекты/$folder")
                    }
            }
            Published("$relative/$pdfName", pdfUri, null)
        } catch (e: Exception) {
            Published(null, null, "Не удалось сохранить в «Документы»: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun insert(src: File, displayName: String, mime: String, relativePath: String): Uri {
        val resolver = context.contentResolver
        val collection = if (mime.startsWith("image/") && relativePath.startsWith(Environment.DIRECTORY_PICTURES)) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore вернул null")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("не открылся файл для записи")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

        fun sanitize(s: String): String = s.replace(ILLEGAL, " ").replace(Regex("\\s+"), " ").trim().trim('.')

        /** Тема без разметки LaTeX: «Предел $\frac{1}{x}$» → «Предел 1/x». */
        // Скобки экранированы явно: регулярки Android (ICU) строже, чем в обычной JVM.
        private val FRAC = Regex("""\\frac\{([^\{\}]*)\}\{([^\{\}]*)\}""")
        private val COMMAND = Regex("""\\[a-zA-Z]+""")
        private val MATH_CHARS = Regex("""[\$\{\}]""")
        private val SPACES = Regex("""\s+""")

        fun plainTitle(title: String): String = title
            .replace(FRAC, "$1/$2")
            .replace(COMMAND, "")
            .replace(MATH_CHARS, "")
            .replace(SPACES, " ")
            .trim()
    }
}
