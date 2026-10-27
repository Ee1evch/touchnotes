package app.nastavnik.claude

/** Убирает маркер `[[ГОТОВО]]` из ответа наставника и сообщает, был ли он. */
object DoneMarker {
    data class Result(val text: String, val done: Boolean)

    private val markerRegex = Regex("""\[\[\s*ГОТОВО\s*]]""", RegexOption.IGNORE_CASE)
    private const val MARKER = Prompts.DONE_MARKER

    /**
     * @param streaming ответ ещё печатается: тогда прячем и недописанное начало маркера
     * в самом конце текста («[[ГОТ»), чтобы оно не мигало на экране.
     */
    fun process(raw: String, streaming: Boolean = false): Result {
        val done = markerRegex.containsMatchIn(raw)
        var text = markerRegex.replace(raw, "")
        if (streaming && !done) text = stripPartialSuffix(text)
        return Result(text.trimEnd(), done)
    }

    private fun stripPartialSuffix(text: String): String {
        val trimmed = text.trimEnd()
        for (len in MARKER.length - 1 downTo 1) {
            val prefix = MARKER.substring(0, len)
            if (trimmed.endsWith(prefix, ignoreCase = true)) return trimmed.dropLast(len)
        }
        return text
    }
}
