package app.nastavnik.notes

/**
 * Поиск по всему, что накопилось: конспекты, задачи, тетради, карточки.
 * Без учёта регистра и «ё»; все слова запроса должны встретиться (в любом порядке).
 */
object Search {
    fun normalize(s: String): String = s.lowercase().replace('ё', 'е')

    fun words(query: String): List<String> = normalize(query).split(Regex("[\\s,.;:!?«»\"()]+")).filter { it.length >= 2 || it.any(Char::isDigit) }

    fun matches(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val t = normalize(text)
        return words.all { t.contains(it) }
    }

    /** Насколько хорошо подходит: совпадения в заголовке весомее, чем в тексте. */
    fun score(title: String, body: String, words: List<String>): Int {
        val t = normalize(title)
        val b = normalize(body)
        return words.sumOf { w -> (if (t.contains(w)) 10 else 0) + minOf(occurrences(b, w), 5) }
    }

    /** Кусочек текста вокруг первого совпадения — чтобы было видно, где нашлось. */
    fun snippet(text: String, words: List<String>, radius: Int = 70): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val n = normalize(flat)
        val at = words.mapNotNull { w -> n.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: return flat.take(radius * 2)
        val from = (at - radius).coerceAtLeast(0).let { s -> if (s == 0) 0 else flat.indexOf(' ', s).takeIf { it in s until at } ?: s }
        val to = (at + radius).coerceAtMost(flat.length).let { e -> if (e == flat.length) e else flat.lastIndexOf(' ', e).takeIf { it > at } ?: e }
        return (if (from > 0) "…" else "") + flat.substring(from, to).trim() + (if (to < flat.length) "…" else "")
    }

    private fun occurrences(s: String, w: String): Int {
        var i = s.indexOf(w)
        var n = 0
        while (i >= 0) {
            n++
            i = s.indexOf(w, i + w.length)
        }
        return n
    }
}
