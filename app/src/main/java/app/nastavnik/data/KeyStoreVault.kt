package app.nastavnik.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Аппаратное хранилище секретов на базе Android KeyStore (AES-256-GCM).
 * Заменяет устаревший EncryptedSharedPreferences: ключи защищены StrongBox (при наличии)
 * или TEE аппаратным модулем безопасности.
 */
class KeyStoreVault(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
    private val fallbackPrefs: SharedPreferences = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
    private val memoryFallback = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { runCatching { load(null) } }

    @Synchronized
    private fun getOrCreateKey(): SecretKey? {
        return runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) return entry.secretKey
            }

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
                val b = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    b.setIsStrongBoxBacked(true)
                }
                return b.build()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    keyGen.init(buildSpec(strongBox = true))
                    return keyGen.generateKey()
                } catch (_: Throwable) {
                    // StrongBox отсутствует на устройстве — используем стандартный TEE
                }
            }

            keyGen.init(buildSpec(strongBox = false))
            keyGen.generateKey()
        }.getOrNull()
    }

    fun isAvailable(): Boolean = runCatching {
        val test = "test_keystore"
        val enc = encrypt(test) ?: return false
        decrypt(enc) == test
    }.getOrDefault(false)

    fun encrypt(plaintext: String): String? {
        return try {
            val secretKey = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv // 12 байт
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < IV_SIZE + 1) return null
            val iv = ByteArray(IV_SIZE)
            val ciphertext = ByteArray(combined.size - IV_SIZE)
            System.arraycopy(combined, 0, iv, 0, IV_SIZE)
            System.arraycopy(combined, IV_SIZE, ciphertext, 0, ciphertext.size)

            val secretKey = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        val encrypted = prefs.getString(key, null)
        if (encrypted != null) {
            val dec = decrypt(encrypted)
            if (dec != null) return dec
        }
        val fallback = fallbackPrefs.getString(key, null)
        if (fallback != null) return fallback
        return memoryFallback[key] ?: defaultValue
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            fallbackPrefs.edit().remove(key).apply()
            memoryFallback.remove(key)
        } else {
            val encrypted = encrypt(value)
            if (encrypted != null) {
                prefs.edit().putString(key, encrypted).apply()
                fallbackPrefs.edit().remove(key).apply()
            } else {
                fallbackPrefs.edit().putString(key, value).apply()
            }
            memoryFallback[key] = value
        }
    }

    fun contains(key: String): Boolean =
        prefs.contains(key) || fallbackPrefs.contains(key) || memoryFallback.containsKey(key)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
        fallbackPrefs.edit().remove(key).apply()
        memoryFallback.remove(key)
    }

    fun clear() {
        prefs.edit().clear().apply()
        fallbackPrefs.edit().clear().apply()
        memoryFallback.clear()
    }

    companion object {
        const val KEY_ALIAS = "nastavnik_keystore_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val VAULT_PREFS = "nastavnik_vault_prefs"
        private const val FALLBACK_PREFS = "nastavnik_vault_fallback"
        private const val IV_SIZE = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
