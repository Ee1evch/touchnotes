package app.nastavnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupAndUpdateTest {
    @Test
    fun backupEntriesGoOnlyWhereExpected() {
        assertEquals("databases/nastavnik.db", BackupLayout.target("db/nastavnik.db"))
        assertEquals("shared_prefs/settings.xml", BackupLayout.target("prefs/settings.xml"))
        assertEquals("files/notebooks/1/page_2.ink", BackupLayout.target("files/notebooks/1/page_2.ink"))
        assertEquals("files/notes/a/b.pdf", BackupLayout.target("files/notes/a/b.pdf"))
        // Ключи, чужие папки и попытки выйти наружу — мимо.
        assertNull(BackupLayout.target("prefs/secure_settings.xml"))
        assertNull(BackupLayout.target("files/notebooks/../../shared_prefs/x.xml"))
        assertNull(BackupLayout.target("/etc/passwd"))
        assertNull(BackupLayout.target("db/sub/x.db"))
        assertNull(BackupLayout.target("files/other/x"))
    }

    private fun release(tag: String, url: String = "https://github.com/Ee1evch/touchnotes/releases/download/$tag/nastavnik.apk") =
        """{"tag_name":"$tag","name":"Наставник, сборка","assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"nastavnik.apk","size":12000000,"browser_download_url":"$url"}]}"""

    @Test
    fun updateOnlyWhenNewerAndFromOurRepo() {
        val u = UpdateCheck.parse(release("build-57"), currentBuild = 50)!!
        assertEquals(57, u.build)
        assertEquals(12_000_000L, u.sizeBytes)
        assertNull(UpdateCheck.parse(release("build-50"), 50))
        assertNull(UpdateCheck.parse(release("build-60", "https://evil.example/app.apk"), 50))
        assertNull(UpdateCheck.parse("{\"tag_name\":\"build-70\",\"assets\":[]}", 50))
        assertNull(UpdateCheck.parse("не json", 50))
        // В релизе есть и APK родителя — берём свой, даже если родительский идёт первым.
        val both = """{"tag_name":"build-80","assets":[{"name":"parent.apk","browser_download_url":"https://github.com/Ee1evch/touchnotes/releases/download/build-80/parent.apk"},""" +
            """{"name":"nastavnik.apk","size":5,"browser_download_url":"https://github.com/Ee1evch/touchnotes/releases/download/build-80/nastavnik.apk"}]}"""
        assertEquals("https://github.com/Ee1evch/touchnotes/releases/download/build-80/nastavnik.apk", UpdateCheck.parse(both, 50)!!.apkUrl)
        assertNull(UpdateCheck.parse("""{"tag_name":"build-81","assets":[{"name":"parent.apk","browser_download_url":"https://github.com/Ee1evch/touchnotes/releases/download/build-81/parent.apk"}]}""", 50))
        // «Наставник · Студент» берёт свой student.apk.
        val three = """{"tag_name":"build-82","assets":[{"name":"nastavnik.apk","browser_download_url":"https://github.com/Ee1evch/touchnotes/releases/download/build-82/nastavnik.apk"},""" +
            """{"name":"student.apk","browser_download_url":"https://github.com/Ee1evch/touchnotes/releases/download/build-82/student.apk"}]}"""
        assertEquals("https://github.com/Ee1evch/touchnotes/releases/download/build-82/student.apk", UpdateCheck.parse(three, 50, "student.apk")!!.apkUrl)

        // Семантическая версия v1.1.10 корректно распознаётся новее старых сборок
        val semverRelease = release("v1.1.10")
        val semverUpdate = UpdateCheck.parse(semverRelease, currentBuild = 151)!!
        assertEquals(10110, semverUpdate.build)
        assertNull(UpdateCheck.parse(semverRelease, currentBuild = 10110))
    }
}
