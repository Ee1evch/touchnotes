package app.nastavnik.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** REST API Яндекс Диска на мок-сервере: папки, загрузка через ссылку, список, скачивание, синхронизация целиком. */
class YandexDiskStoreTest {
    private class FakeDisk : Dispatcher() {
        val files = HashMap<String, ByteArray>()
        val dirs = HashSet<String>()
        lateinit var base: String
        val auth = ArrayList<String?>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val rawPath = url.queryParameter("path").orEmpty()
            val path = rawPath.removePrefix("app:/").removePrefix("disk:/")
            return when {
                url.encodedPath == "/v1/disk/resources" && request.method == "PUT" -> {
                    auth += request.getHeader("Authorization")
                    if (dirs.add(path!!)) MockResponse().setResponseCode(201) else MockResponse().setResponseCode(409)
                }
                url.encodedPath == "/v1/disk/resources" && request.method == "GET" -> {
                    if (path !in dirs) return MockResponse().setResponseCode(404)
                    val items = files.keys.filter { it.substringBeforeLast('/') == path }.map { it.substringAfterLast('/') }
                    MockResponse().setBody("""{"_embedded":{"items":[${items.joinToString(",") { "{\"name\":\"$it\",\"type\":\"file\"}" }}],"total":${items.size}}}""")
                }
                url.encodedPath == "/v1/disk/resources/upload" -> MockResponse().setBody("""{"href":"${base}put/${path}"}""")
                url.encodedPath == "/v1/disk/resources/download" ->
                    if (path in files) MockResponse().setBody("""{"href":"${base}get/${path}"}""") else MockResponse().setResponseCode(404)
                url.encodedPath.startsWith("/put/") -> { files[url.encodedPath.removePrefix("/put/")] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                url.encodedPath.startsWith("/get/") -> MockResponse().setBody(okio.Buffer().write(files[url.encodedPath.removePrefix("/get/")]!!))
                else -> MockResponse().setResponseCode(400)
            }
        }
    }

    @Test fun storeAndSyncThroughRestApi() = runBlocking {
        val server = MockWebServer()
        val disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        disk.base = server.url("/").toString()
        val store = YandexDiskStore("тестовый-токен".let { "y0_test" }, server.url("/").toString(), OkHttpClient())
        store.check()
        store.check()
        assertEquals("OAuth y0_test", disk.auth.first())
        assertEquals(emptyList<String>(), store.list("Nastavnik/devices"))
        store.ensureDir("Nastavnik/blobs")
        store.write("Nastavnik/blobs/ab", "привет".toByteArray())
        assertEquals(listOf("ab"), store.list("Nastavnik/blobs"))
        assertEquals("привет", store.read("Nastavnik/blobs/ab")!!.decodeToString())
        assertNull(store.read("Nastavnik/blobs/нет"))

        // Две «машины» синхронизируются через этот Диск.
        val a = SyncEngineTest.Device("a", store)
        val b = SyncEngineTest.Device("b", store)
        a.notes["x"] = "интеграл"
        a.books["k"] = File(a.dir, "k.src").apply { writeText("страница") }
        a.sync()
        b.sync()
        assertEquals("интеграл", b.notes["x"])
        assertEquals("страница", b.books["k"]!!.readText())
        assertTrue(disk.files.keys.any { it.startsWith("Nastavnik/devices/") })
        server.shutdown()
    }

    @Test fun usesAppFolderScopeByDefault() = runBlocking {
        val server = MockWebServer()
        val disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        disk.base = server.url("/").toString()
        val store = YandexDiskStore("test-token", server.url("/").toString(), OkHttpClient())
        store.ensureDir("Nastavnik/devices")
        val req = server.takeRequest()
        assertTrue(req.requestUrl!!.queryParameter("path")!!.startsWith("app:/"))
        server.shutdown()
    }
}
