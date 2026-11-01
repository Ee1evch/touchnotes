package app.nastavnik.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavStoreTest {
    @Test fun parsesPropfind() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/Nastavnik/devices/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/Nastavnik/devices/tablet-1.json.gz</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
            <d:response><d:href>/Nastavnik/devices/%D1%82%D0%B5%D0%BB.json.gz</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
            <d:response><d:href>/Nastavnik/devices/sub/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        assertEquals(listOf("tablet-1.json.gz", "тел.json.gz"), WebDavStore.parseList(xml, "Nastavnik/devices"))
    }

    @Test fun talksWebDav() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("hello"))
        val store = WebDavStore(server.url("/").toString(), "user", "секрет", OkHttpClient())
        store.ensureDir("Nastavnik")
        store.write("Nastavnik/blobs/ab", "x".toByteArray())
        assertNull(store.read("Nastavnik/devices/none.json.gz"))
        assertEquals("hello", store.read("Nastavnik/a b")!!.decodeToString())
        val mk = server.takeRequest()
        assertEquals("MKCOL", mk.method)
        assertTrue(mk.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals("PUT", server.takeRequest().method)
        server.takeRequest()
        assertEquals("/Nastavnik/a%20b", server.takeRequest().path)
        server.shutdown()
    }
}
