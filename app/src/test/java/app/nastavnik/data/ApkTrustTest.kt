package app.nastavnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkTrustTest {

    @Test
    fun computeSha256MatchesExpected() {
        val temp = File.createTempFile("test_sha", ".bin").apply {
            writeBytes("hello world".toByteArray())
        }
        try {
            // sha256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
            val hash = ApkTrust.computeSha256(temp)
            assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun verifyMetadataValidWhenAllMatches() {
        val result = ApkTrust.verifyMetadata(
            apkPackage = "app.nastavnik",
            expectedPackage = "app.nastavnik",
            apkVersionCode = 10111,
            currentVersionCode = 10110,
            apkCertFingerprints = listOf("AA:BB:CC"),
            currentCertFingerprints = listOf("AA:BB:CC"),
            fileDigestSha256 = "112233",
            expectedDigestSha256 = "112233"
        )
        assertTrue(result is TrustResult.Valid)
    }

    @Test
    fun verifyMetadataRejectsPackageMismatch() {
        val result = ApkTrust.verifyMetadata(
            apkPackage = "com.attacker.fake",
            expectedPackage = "app.nastavnik",
            apkVersionCode = 10111,
            currentVersionCode = 10110,
            apkCertFingerprints = listOf("AA:BB:CC"),
            currentCertFingerprints = listOf("AA:BB:CC"),
            fileDigestSha256 = "112233"
        )
        assertTrue(result is TrustResult.Rejected)
        assertTrue((result as TrustResult.Rejected).reason.contains("Пакет обновления"))
    }

    @Test
    fun verifyMetadataRejectsDowngrade() {
        val result = ApkTrust.verifyMetadata(
            apkPackage = "app.nastavnik",
            expectedPackage = "app.nastavnik",
            apkVersionCode = 10100,
            currentVersionCode = 10110,
            apkCertFingerprints = listOf("AA:BB:CC"),
            currentCertFingerprints = listOf("AA:BB:CC"),
            fileDigestSha256 = "112233"
        )
        assertTrue(result is TrustResult.Rejected)
        assertTrue((result as TrustResult.Rejected).reason.contains("Попытка отката"))
    }

    @Test
    fun verifyMetadataRejectsShaMismatch() {
        val result = ApkTrust.verifyMetadata(
            apkPackage = "app.nastavnik",
            expectedPackage = "app.nastavnik",
            apkVersionCode = 10111,
            currentVersionCode = 10110,
            apkCertFingerprints = listOf("AA:BB:CC"),
            currentCertFingerprints = listOf("AA:BB:CC"),
            fileDigestSha256 = "112233",
            expectedDigestSha256 = "998877"
        )
        assertTrue(result is TrustResult.Rejected)
        assertTrue((result as TrustResult.Rejected).reason.contains("Контрольная сумма SHA-256"))
    }

    @Test
    fun verifyMetadataRejectsSignatureMismatchWithFlag() {
        val result = ApkTrust.verifyMetadata(
            apkPackage = "app.nastavnik",
            expectedPackage = "app.nastavnik",
            apkVersionCode = 10111,
            currentVersionCode = 10110,
            apkCertFingerprints = listOf("OTHER_CERT"),
            currentCertFingerprints = listOf("ORIGINAL_CERT"),
            fileDigestSha256 = "112233"
        )
        assertTrue(result is TrustResult.Rejected)
        val rejected = result as TrustResult.Rejected
        assertTrue(rejected.isSignatureMismatch)
        assertTrue(rejected.reason.contains("Ключ подписи"))
    }
}
