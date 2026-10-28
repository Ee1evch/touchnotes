package app.nastavnik.notes

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlotFunction(val expr: String = "", val label: String? = null)

@Serializable
data class PlotPoint(val x: Double = 0.0, val y: Double = 0.0, val label: String? = null)

/** Один блок чистовика. Все поля, кроме типа, необязательны — модель заполняет нужные для своего типа. */
@Serializable
data class NoteBlock(
    val type: String = "p",
    val text: String? = null,
    val title: String? = null,
    val tex: String? = null,
    val items: List<String>? = null,
    val functions: List<PlotFunction>? = null,
    val x: List<Double>? = null,
    val y: List<Double>? = null,
    val points: List<PlotPoint>? = null,
    val head: List<String>? = null,
    val rows: List<List<String>>? = null,
)

/** Карточка для повторения: вопрос и короткий ответ, формулы в `$…$`. */
@Serializable
data class StudyCard(val q: String = "", val a: String = "")

@Serializable
data class CleanNote(
    val subject: String = "",
    val title: String = "",
    val blocks: List<NoteBlock> = emptyList(),
    val summary: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    /** Карточки «вопрос — ответ» по конспекту: после сохранения попадают в «Повторение». */
    val cards: List<StudyCard> = emptyList(),
    /** Дата для шапки листа — ставит приложение, а не модель. */
    val date: String? = null,
)

class CleanNoteParseException(message: String) : Exception(message)

/**
 * Терпимый разбор ответа модели:
 * 1. вырезаем от первой `{` до последней `}` (отбрасываем ```json и пояснения);
 * 2. чиним одиночные обратные косые черты LaTeX (`\frac` → `\\frac`), если модель их не удвоила;
 * 3. разбираем нестрогим JSON-парсером и выбрасываем неизвестные типы блоков.
 */
@OptIn(ExperimentalSerializationApi::class)
object CleanNoteParser {
    const val MAX_CARDS = 12
    val KNOWN_TYPES = setOf("h", "p", "def", "thm", "formula", "steps", "plot", "table", "note")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowTrailingComma = true
    }

    private val outJson = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    fun parse(raw: String): CleanNote {
        val body = extractJson(raw) ?: throw CleanNoteParseException("в ответе нет JSON")
        val repaired = repairBackslashes(body)
        val note = runCatching { json.decodeFromString(CleanNote.serializer(), repaired) }
            .recoverCatching { json.decodeFromString(CleanNote.serializer(), body) }
            .getOrElse { throw CleanNoteParseException("JSON не разбирается: ${it.message?.take(120)}") }
        val cleaned = note.copy(
            blocks = note.blocks.filter { it.type in KNOWN_TYPES && it.hasContent() },
            summary = note.summary.filter { it.isNotBlank() },
            questions = note.questions.filter { it.isNotBlank() },
            cards = note.cards
                .map { StudyCard(it.q.trim(), it.a.trim()) }
                .filter { it.q.isNotEmpty() && it.a.isNotEmpty() }
                .distinctBy { it.q }
                .take(MAX_CARDS),
        )
        if (cleaned.title.isBlank() && cleaned.blocks.isEmpty()) {
            throw CleanNoteParseException("в JSON нет ни темы, ни блоков")
        }
        return cleaned
    }

    fun toJson(note: CleanNote): String = outJson.encodeToString(CleanNote.serializer(), note)

    fun fromStoredJson(stored: String): CleanNote = json.decodeFromString(CleanNote.serializer(), stored)

    private fun NoteBlock.hasContent(): Boolean = when (type) {
        "formula" -> !tex.isNullOrBlank()
        "steps" -> !items.isNullOrEmpty()
        "plot" -> !functions.isNullOrEmpty() || !points.isNullOrEmpty()
        "table" -> !rows.isNullOrEmpty() || !head.isNullOrEmpty()
        else -> !text.isNullOrBlank() || !title.isNullOrBlank()
    }

    /** Команды LaTeX, начинающиеся на n, r, t — их нельзя спутать с `\n`, `\r`, `\t`. */
    private val NRT_COMMANDS = setOf(
        "ne", "neq", "nabla", "nu", "neg", "not", "ni", "nmid", "notin", "nleq", "ngeq", "nless", "ngtr",
        "newline", "nolimits", "normalsize", "nparallel", "nsubseteq", "nsupseteq", "nexists", "nearrow", "nwarrow",
        "right", "rho", "rangle", "rceil", "rfloor", "rightarrow", "rightleftharpoons", "rightrightarrows",
        "rVert", "rvert", "rm", "rbrace", "rbrack", "restriction", "rmoustache", "risingdotseq",
        "theta", "tau", "times", "to", "text", "textbf", "textit", "textrm", "textsf", "texttt", "textstyle",
        "tan", "tanh", "top", "tilde", "triangle", "triangleq", "triangleleft", "triangleright", "tfrac",
        "therefore", "tg", "th", "tbinom", "tiny", "tt", "times", "twoheadrightarrow", "trianglelefteq",
    )

    /**
     * Удваивает обратные косые черты внутри строк JSON там, где это явно LaTeX, а не JSON-экранирование.
     * Корректно удвоенные `\\` не трогаем.
     */
    fun repairBackslashes(src: String): String {
        val out = StringBuilder(src.length + 32)
        var inString = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (!inString) {
                if (c == '"') inString = true
                out.append(c)
                i++
                continue
            }
            if (c == '"') {
                inString = false
                out.append(c)
                i++
                continue
            }
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            // c == '\\' внутри строки
            if (i + 1 >= src.length) {
                out.append("\\\\")
                i++
                continue
            }
            val next = src[i + 1]
            val run = letterRun(src, i + 1)
            val keepAsEscape = when {
                next == '\\' || next == '"' || next == '/' -> true
                next == 'u' -> isHex4(src, i + 2)
                next == 'b' || next == 'f' -> run.length == 1
                next == 'n' || next == 'r' || next == 't' -> run !in NRT_COMMANDS
                else -> false
            }
            if (keepAsEscape) {
                out.append(c).append(next)
                i += 2
            } else {
                out.append("\\\\")
                i++
            }
        }
        return out.toString()
    }

    private fun letterRun(s: String, from: Int): String {
        var j = from
        while (j < s.length && s[j].let { it in 'a'..'z' || it in 'A'..'Z' }) j++
        return s.substring(from, j)
    }

    private fun isHex4(s: String, from: Int): Boolean {
        if (from + 4 > s.length) return false
        for (k in from until from + 4) {
            val ch = s[k]
            if (!(ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F')) return false
        }
        return true
    }
}
