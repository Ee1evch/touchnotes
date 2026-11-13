package app.nastavnik.sync

/**
 * Облако «Наставника» только на чтение — для приложения родителя: свежая версия нужных записей по снимкам всех
 * устройств студента. Ничего не пишет и не создаёт (своего снимка у родителя нет, устройствам студента он не виден).
 */
class CloudReader(
    private val store: SyncStore,
    private val passphrase: () -> String = { "" },
) {
    /** Свежие версии записей с ключами [keys] («kind/id»); удалённые — не возвращаются. */
    suspend fun latest(keys: Set<String>): Map<String, SyncRecord> {
        val names = store.list("${SyncEngine.ROOT}/devices").filter { it.endsWith(".json.gz") }
        if (names.isEmpty()) throw java.io.IOException("На Диске нет данных «Наставника» — проверь токен и что синхронизация у студента включена")
        val best = HashMap<String, SyncRecord>()
        for (name in names) {
            val snap = runCatching { SyncEngine.decode(store.read("${SyncEngine.ROOT}/devices/$name")!!, passphrase()) }.getOrNull() ?: continue
            for (r in snap.records) {
                if (r.key !in keys) continue
                val cur = best[r.key]
                if (cur == null || SyncEngine.newer(r, cur)) best[r.key] = r
            }
        }
        return best.filterValues { !it.deleted }
    }
}
