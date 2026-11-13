package app.nastavnik.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {

    @Test
    fun roundtripEncryptionAndDecryption() {
        val original = "Тестовые данные конспекта по высшей математике: $\\int x dx = \\frac{x^2}{2} + C$".encodeToByteArray()
        val passphrase = "правильная-секретная-фраза-студента"

        val encrypted = SyncCrypto.encrypt(original, passphrase)
        assertTrue(SyncCrypto.isEncrypted(encrypted))
        assertFalse(original.contentEquals(encrypted))

        val decrypted = SyncCrypto.decrypt(encrypted, passphrase)
        assertArrayEquals(original, decrypted)
        assertEquals(original.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun wrongPassphraseThrowsException() {
        val original = "Секретные записи".encodeToByteArray()
        val encrypted = SyncCrypto.encrypt(original, "верный-пароль")

        val e = assertThrows(BadPassphraseException::class.java) {
            SyncCrypto.decrypt(encrypted, "неверный-пароль")
        }
        assertTrue(e.message!!.contains("Не удалось расшифровать"))
    }

    @Test
    fun tamperedCiphertextThrowsException() {
        val original = "Важные данные".encodeToByteArray()
        val encrypted = SyncCrypto.encrypt(original, "пароль")
        val jsonStr = encrypted.decodeToString()

        // Подменяем один символ в зашифрованном Base64
        val tamperedJson = jsonStr.replace(Regex("\"ciphertext\":\"([^\"]+)"), "\"ciphertext\":\"AAAA$1")
        val tamperedBytes = tamperedJson.encodeToByteArray()

        assertThrows(BadPassphraseException::class.java) {
            SyncCrypto.decrypt(tamperedBytes, "пароль")
        }
    }

    @Test
    fun unencryptedLegacyDataPassesThroughTransparently() {
        val plaintext = "Обычные незашифрованные данные от старой версии".encodeToByteArray()
        assertFalse(SyncCrypto.isEncrypted(plaintext))

        val result = SyncCrypto.decrypt(plaintext, "любой-пароль")
        assertArrayEquals(plaintext, result)
    }

    @Test
    fun emptyPassphraseOnEncryptedDataThrowsException() {
        val original = "Конспект".encodeToByteArray()
        val encrypted = SyncCrypto.encrypt(original, "пароль")

        val e = assertThrows(BadPassphraseException::class.java) {
            SyncCrypto.decrypt(encrypted, "")
        }
        assertTrue(e.message!!.contains("требуется парольная фраза"))
    }
}
