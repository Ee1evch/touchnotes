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
        val base = fileBase(title)
        val relative = "${Environment.DIRECTORY_DOCUMENTS}/Конспекты/$folder"
        return try {
            val pdfName = "$base.pdf"
            val pdfUri = insert(pdf, pdfName, "application/pdf", relative)
            pngs.forEachIndexed { i, png ->
                val name = if (pngs.size == 1) "$base.png" else "$base — стр. ${i + 1}.png"
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
        /** «<Тема> — <дата>» без LaTeX и запрещённых в именах файлов символов. */
        fun fileBase(title: String, date: Date = Date()): String {
            val day = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ru")).format(date)
            val topic = sanitize(plainTitle(title)).ifBlank { "Конспект" }.take(80).trim()
            return "$topic — $day"
        }

        private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

        /** «/» из дробей заменяем похожим знаком деления, остальные запрещённые символы — пробелом. */
        fun sanitize(s: String): String =
            s.replace('/', '∕').replace(ILLEGAL, " ").replace(SPACES, " ").trim().trim('.')

        /** Тема без разметки LaTeX: «Предел $\frac{1}{x}$» → «Предел 1/x». */
        // Скобки экранированы явно: регулярки Android (ICU) строже, чем в обычной JVM.
        private val FRAC = Regex("""\\frac\{([^\{\}]*)\}\{([^\{\}]*)\}""")
        private val COMMAND = Regex("""\\[a-zA-Z]+""")
        private val MATH_CHARS = Regex("""[\$\{\}]""")
        private val SPACES = Regex("""\s+""")

        private val SUP = Regex("""\^(?:\{([0-9n+\-]{1,3})\}|([0-9]{1,2}|n))""")
        private val SUB = Regex("""_(?:\{([0-9nik]{1,2})\}|([0-9]{1,2}|[nik]))""")
        private val SUP_CHARS = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', 'n' to 'ⁿ', '+' to '⁺', '-' to '⁻')
        private val SUB_CHARS = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', 'n' to 'ₙ', 'i' to 'ᵢ', 'k' to 'ₖ')

        /** Частые команды LaTeX, у которых есть понятный символ. */
        private val SYMBOLS = mapOf(
            "to" to "→", "infty" to "∞", "cdot" to "·", "times" to "×", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
            "ne" to "≠", "neq" to "≠", "pm" to "±", "pi" to "π", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "Delta" to "Δ", "varepsilon" to "ε", "epsilon" to "ε", "lambda" to "λ", "sum" to "∑", "int" to "∫", "sqrt" to "√",
            "in" to "∈", "forall" to "∀", "exists" to "∃", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "approx" to "≈",
        )

        fun plainTitle(title: String): String = title
            .replace(FRAC, "$1/$2")
            .replace(SUP) { m -> (m.groupValues[1] + m.groupValues[2]).map { SUP_CHARS[it] ?: it }.joinToString("") }
            .replace(SUB) { m -> (m.groupValues[1] + m.groupValues[2]).map { SUB_CHARS[it] ?: it }.joinToString("") }
            .replace(COMMAND) { m -> SYMBOLS[m.value.drop(1)]?.let { " $it " } ?: "" }
            .replace(MATH_CHARS, "")
            .replace(SPACES, " ")
            .trim()
    }
}
