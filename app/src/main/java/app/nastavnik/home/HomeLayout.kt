package app.nastavnik.home

/**
 * Раскладка главного экрана — без Android (тесты `HomeLayoutTest`). Блоки по порядку, у каждого размер:
 * S — плитка (треть ширины на телефоне, четверть на планшете), M — половина, L — во всю ширину.
 * Ряды заполняются по порядку: блок, который не помещается в ряд, начинает новый.
 */
object HomeLayout {
    enum class Size { S, M, L }

    data class Item(val id: String, val size: Size)

    /** Колонок в сетке: телефон — 6, планшет — 12. */
    fun columns(tablet: Boolean) = if (tablet) 12 else 6

    /** Сколько колонок занимает блок. */
    fun span(size: Size, columns: Int): Int = when (size) {
        Size.S -> columns / (if (columns >= 12) 4 else 3)
        Size.M -> columns / 2
        Size.L -> columns
    }

    /** Ряды: блоки по порядку, пока помещаются. */
    fun rows(items: List<Item>, columns: Int): List<List<Item>> {
        val out = mutableListOf<MutableList<Item>>()
        var used = columns
        for (it in items) {
            val s = span(it.size, columns)
            if (used + s > columns) { out += mutableListOf<Item>(); used = 0 }
            out.last() += it
            used += s
        }
        return out
    }

    /** «day:L,cards:S,…» */
    fun encode(items: List<Item>): String = items.joinToString(",") { "${it.id}:${it.size}" }

    /**
     * Сохранённая раскладка: неизвестные блоки и повторы отбрасываются; пусто или не разобралось — [default].
     * Блоки, которых нет в списке, считаются убранными.
     */
    fun decode(raw: String?, known: Set<String>, default: List<Item>): List<Item> {
        if (raw.isNullOrBlank()) return default
        val seen = mutableSetOf<String>()
        val items = raw.split(',').mapNotNull { part ->
            val (id, size) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val s = runCatching { Size.valueOf(size.trim()) }.getOrNull() ?: return@mapNotNull null
            val key = id.trim()
            if (key !in known || !seen.add(key)) null else Item(key, s)
        }
        return items.ifEmpty { if (raw.trim() == "-") emptyList() else default }
    }

    fun move(items: List<Item>, index: Int, delta: Int): List<Item> {
        val to = index + delta
        if (index !in items.indices || to !in items.indices) return items
        return items.toMutableList().apply { add(to, removeAt(index)) }
    }

    fun resize(items: List<Item>, index: Int, size: Size) = items.mapIndexed { i, it -> if (i == index) it.copy(size = size) else it }

    fun remove(items: List<Item>, index: Int) = items.filterIndexed { i, _ -> i != index }

    fun add(items: List<Item>, id: String, size: Size) = if (items.any { it.id == id }) items else items + Item(id, size)
}
