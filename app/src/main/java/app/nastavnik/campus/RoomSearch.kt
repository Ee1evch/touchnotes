package app.nastavnik.campus

/**
 * Поиск помещения: по строке поиска и по аудитории из расписания.
 * Расписание Сириуса пишет аудитории по-разному: «Альфа 5.8», «1-14-19.Альфа 4.1», «К_4», «К_У. К4 (комп)»,
 * а на плане они записаны как «Альфа 5.8», номер «1-07-18» или «К4».
 */
object RoomSearch {
    private val spaces = Regex("""\s+""")
    // Буква после номера — часть номера («2.2м»), но не начало следующего слова («5.8Альфа 5.8» на сайте).
    private val building = Regex("""(Альфа|Бета|Гамма|Дельта)\s*(\d+(?:\.\d+)*(?:[а-яё](?![а-яё]))?)""", RegexOption.IGNORE_CASE)
    private val code = Regex("""\b(\d-\d{2}-\d{2}(?:\.\d)?)""")
    private val kRoom = Regex("""К_?\s*(\d{1,2})\b""")

    fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace(spaces, " ").trim()

    /** Сайт расписания иногда повторяет название дважды: «Альфа 5.8Альфа 5.8». */
    fun undouble(s: String): String {
        if (s.length % 2 == 0) {
            val half = s.length / 2
            if (s.substring(0, half) == s.substring(half)) return s.substring(0, half)
        }
        return s
    }

    /** Помещения по запросу: сначала точные совпадения названия или номера, потом начала слов, потом вхождения. */
    fun search(sites: List<Site>, query: String, limit: Int = 30): List<Pair<Site, Room>> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        return sites.flatMap { s -> s.rooms.filter { !it.isGeneric }.map { s to it } }
            .mapNotNull { (s, r) ->
                val keys = (r.nums + r.names + r.func).map(::normalize)
                val score = when {
                    keys.any { it == q } -> 0
                    keys.any { it.startsWith(q) } -> 1
                    words.all { w -> keys.any { k -> k.split(' ', '·', '«', '»', '(', ')').any { it.startsWith(w) } } } -> 2
                    words.all { w -> r.searchText.contains(w) } -> 3
                    else -> return@mapNotNull null
                }
                Triple(s, r, score)
            }
            .sortedWith(compareBy({ it.third }, { it.second.title.length }, { it.second.floor }))
            .distinctBy { it.first.id to it.second.title }
            .take(limit)
            .map { it.first to it.second }
    }

    /** Помещение для аудитории из расписания или null, если на карте такой нет. */
    fun forClassroom(sites: List<Site>, classroom: String?): Pair<Site, Room>? {
        val text = undouble(classroom?.trim().orEmpty().removeSuffix("(Основной)").trim()).takeIf { it.isNotEmpty() } ?: return null
        val all = sites.flatMap { s -> s.rooms.map { s to it } }
        building.find(text)?.let { m ->
            val name = "${m.groupValues[1].replaceFirstChar { it.uppercase() }} ${m.groupValues[2]}"
            all.firstOrNull { (_, r) -> r.names.any { normalize(it).startsWith(normalize(name)) && normalize(it).removePrefix(normalize(name)).let { rest -> rest.isEmpty() || !rest[0].isDigit() && rest[0] != '.' } } }
                ?.let { return it }
        }
        code.find(text)?.let { m ->
            all.firstOrNull { (_, r) -> m.groupValues[1] in r.nums }?.let { return it }
        }
        kRoom.find(text)?.let { m ->
            val k = "К${m.groupValues[1]}"
            all.firstOrNull { (_, r) -> r.nums.any { it.equals(k, ignoreCase = true) } }?.let { return it }
        }
        // Как есть: «Зал 7», «Конференц-зал».
        val q = normalize(text.substringBefore('(').trim())
        if (q.length >= 3) {
            all.firstOrNull { (_, r) -> (r.nums + r.names).any { normalize(it) == q } }?.let { return it }
        }
        return null
    }
}
