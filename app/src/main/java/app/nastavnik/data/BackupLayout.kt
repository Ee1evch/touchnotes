package app.nastavnik.data

/** Что лежит в резервной копии и куда это возвращается. Без Android — чтобы проверить тестами. */
object BackupLayout {
    const val MANIFEST = "nastavnik-backup.txt"
    const val MAGIC = "nastavnik-backup v1"

    /** Папки внутри files/, которые копируем: тетради, чистовики, запомненные страницы конспекта. */
    val FILE_DIRS = listOf("notebooks", "notes", "notebook")

    /** Настройки без ключей: файл защищённого хранилища в копию не попадает никогда. */
    const val PREFS_FILE = "settings.xml"

    /**
     * Куда распаковать запись архива (путь относительно корня данных приложения) или null,
     * если запись чужая или пытается выйти за пределы папки («../»).
     */
    fun target(entry: String): String? {
        val name = entry.replace('\\', '/')
        if (name.startsWith("/") || name.split('/').any { it == ".." || it == "." }) return null
        return when {
            name.startsWith("db/") && name.count { it == '/' } == 1 -> "databases/" + name.removePrefix("db/")
            name == "prefs/$PREFS_FILE" -> "shared_prefs/$PREFS_FILE"
            FILE_DIRS.any { name.startsWith("files/$it/") } -> name
            else -> null
        }
    }
}
