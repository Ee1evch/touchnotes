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
        var decryptedCount = 0
        var encryptedEncountered = false
        var lastCryptoError: Throwable? = null

        for (name in names) {
            val bytes = store.read("${SyncEngine.ROOT}/devices/$name") ?: continue
            if (SyncCrypto.isEncrypted(bytes)) encryptedEncountered = true

            val snap = try {
                SyncEngine.decode(bytes, passphrase())
            } catch (e: BadPassphraseException) {
                lastCryptoError = e
                continue
            } catch (e: Exception) {
                continue
            }
            decryptedCount++
            for (r in snap.records) {
                if (r.key !in keys) continue
                val cur = best[r.key]
                if (cur == null || SyncEngine.newer(r, cur)) best[r.key] = r
            }
        }

        if (decryptedCount == 0 && encryptedEncountered) {
            val phrase = passphrase().trim()
            if (phrase.isEmpty()) {
                throw BadPassphraseException("Данные зашифрованы E2E-паролем — введите парольную фразу студента в настройках")
            }
            throw (lastCryptoError ?: BadPassphraseException("Неверная парольная фраза E2E — не удалось расшифровать данные студента"))
        }

        return best.filterValues { !it.deleted }
    }
}
