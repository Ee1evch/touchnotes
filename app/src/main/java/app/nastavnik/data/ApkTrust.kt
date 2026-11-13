package app.nastavnik.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Результат проверки целостности и доверенности APK-файла. */
sealed class TrustResult {
    data object Valid : TrustResult()
    data class Rejected(val reason: String, val isSignatureMismatch: Boolean = false) : TrustResult()
}

/**
 * Защита обновления приложения (ApkTrust):
 * 1. Проверка контрольной суммы SHA-256 файла.
 * 2. Проверка соответствия applicationId (пакета).
 * 3. Защита от отката версии (downgrade attack): versionCode обновления >= текущего.
 * 4. Проверка отпечатка ключа подписи (SHA-256 сертификата) против установленного приложения.
 * 5. В случае отклонения файл немедленно удаляется.
 */
object ApkTrust {

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeCertFingerprint(certBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(certBytes).joinToString(":") { "%02X".format(it) }
    }

    fun verifyMetadata(
        apkPackage: String?,
        expectedPackage: String,
        apkVersionCode: Long,
        currentVersionCode: Long,
        apkCertFingerprints: List<String>,
        currentCertFingerprints: List<String>,
        fileDigestSha256: String,
        expectedDigestSha256: String? = null
    ): TrustResult {
        if (apkPackage == null || apkPackage != expectedPackage) {
            return TrustResult.Rejected("Пакет обновления ($apkPackage) не совпадает с установленным ($expectedPackage)")
        }
        if (apkVersionCode < currentVersionCode) {
            return TrustResult.Rejected("Попытка отката версии: $apkVersionCode < $currentVersionCode")
        }
        if (expectedDigestSha256 != null && !fileDigestSha256.equals(expectedDigestSha256, ignoreCase = true)) {
            return TrustResult.Rejected("Контрольная сумма SHA-256 ($fileDigestSha256) не совпадает с ожидаемой ($expectedDigestSha256)")
        }
        if (currentCertFingerprints.isNotEmpty() && apkCertFingerprints.isNotEmpty()) {
            val matches = currentCertFingerprints.any { cur -> apkCertFingerprints.contains(cur) }
            if (!matches) {
                return TrustResult.Rejected(
                    "Ключ подписи релиза изменился. Сделайте резервную копию настроек и переустановите приложение.",
                    isSignatureMismatch = true
                )
            }
        }
        return TrustResult.Valid
    }

    fun getInstalledCertFingerprints(context: Context): List<String> {
        return runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
            extractCertFingerprints(pi)
        }.getOrDefault(emptyList())
    }

    fun getApkCertFingerprints(context: Context, apkFile: File): Pair<PackageInfo?, List<String>> {
        return runCatching {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            pi to (pi?.let { extractCertFingerprints(it) } ?: emptyList())
        }.getOrDefault(null to emptyList())
    }

    private fun extractCertFingerprints(pi: PackageInfo): List<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pi.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pi.signatures
        } ?: return emptyList()

        return certs.map { computeCertFingerprint(it.toByteArray()) }
    }

    fun verify(
        context: Context,
        apkFile: File,
        expectedPackage: String = context.packageName,
        currentVersionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        },
        expectedDigestSha256: String? = null
    ): TrustResult {
        val fileSha256 = try {
            computeSha256(apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            return TrustResult.Rejected("Не удалось вычислить SHA-256: ${e.message}")
        }

        val (pi, apkCerts) = getApkCertFingerprints(context, apkFile)
        if (pi == null) {
            apkFile.delete()
            return TrustResult.Rejected("Файл APK повреждён или не является пакетом Android")
        }

        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }

        val currentCerts = getInstalledCertFingerprints(context)
        val result = verifyMetadata(
            apkPackage = pi.packageName,
            expectedPackage = expectedPackage,
            apkVersionCode = apkVersionCode,
            currentVersionCode = currentVersionCode,
            apkCertFingerprints = apkCerts,
            currentCertFingerprints = currentCerts,
            fileDigestSha256 = fileSha256,
            expectedDigestSha256 = expectedDigestSha256
        )

        if (result is TrustResult.Rejected) {
            apkFile.delete()
        }
        return result
    }
}
