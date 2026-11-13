package app.nastavnik.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class EncryptedEnvelope(
    val v: Int = 1,
    val salt: String,
    val nonce: String,
    val ciphertext: String,
)

class BadPassphraseException(message: String = "Неверная парольная фраза или повреждённые данные") : SecurityException(message)

/**
 * E2E-шифрование данных синхронизации (AES-256-GCM + PBKDF2WithHmacSHA256, 210 000 итераций).
 * Обеспечивает криптографическую защиту файлов конспектов и снимков устройств перед отправкой в облако.
 */
object SyncCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LEN = 256
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_LEN_BITS = 128
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < 10) return false
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart()
        return head.startsWith("{\"v\":1") || head.startsWith("{\"v\": 1") || head.contains("\"v\":1") || head.contains("\"v\": 1")
    }

    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        if (passphrase.isBlank()) return plaintext
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LEN)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(keySpec).encoded
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val envelope = EncryptedEnvelope(
            v = 1,
            salt = Base64.getEncoder().encodeToString(salt),
            nonce = Base64.getEncoder().encodeToString(nonce),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
        return json.encodeToString(EncryptedEnvelope.serializer(), envelope).encodeToByteArray()
    }

    fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        if (!isEncrypted(data)) return data
        if (passphrase.isBlank()) throw BadPassphraseException("Для расшифровки данных требуется парольная фраза")

        val envelope = try {
            json.decodeFromString(EncryptedEnvelope.serializer(), data.decodeToString())
        } catch (e: Exception) {
            throw BadPassphraseException("Повреждённый конверт шифрования: ${e.message}")
        }

        val salt = Base64.getDecoder().decode(envelope.salt)
        val nonce = Base64.getDecoder().decode(envelope.nonce)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LEN)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(keySpec).encoded
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BadPassphraseException("Не удалось расшифровать: неверная парольная фраза или ошибка аутентификационного тега")
        }
    }
}
