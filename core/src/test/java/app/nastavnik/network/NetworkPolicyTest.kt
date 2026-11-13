package app.nastavnik.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkPolicyTest {

    private fun testChain(url: String): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response =
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody(null))
                    .build()
            override fun connection(): Connection? = null
            override fun call(): Call = throw UnsupportedOperationException()
            override fun connectTimeoutMillis(): Int = 0
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 0
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 0
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        }
    }

    @Test
    fun externalHttpIsRejected() {
        val policy = NetworkPolicy(allowInsecureLocal = { false })
        val chain = testChain("http://example.com/api/test")
        val e = assertThrows(IOException::class.java) {
            policy.intercept(chain)
        }
        assertTrue(e.message!!.contains("Незащищённое HTTP-соединение запрещено для внешних хостов"))
    }

    @Test
    fun externalHttpsIsAllowed() {
        val policy = NetworkPolicy(allowInsecureLocal = { false })
        val chain = testChain("https://example.com/api/test")
        val resp = policy.intercept(chain)
        assertEquals(200, resp.code)
        assertEquals("ok", resp.body?.string())
    }

    @Test
    fun rfc1918WithOptInIsAllowed() {
        val policy = NetworkPolicy(
            allowInsecureLocal = { true },
            trustedProxyHosts = { setOf("192.168.1.5") },
        )
        val chain = testChain("http://192.168.1.5:13456/proxy/v1/messages")
        val resp = policy.intercept(chain)
        assertEquals(200, resp.code)
        assertEquals("ok", resp.body?.string())
    }

    @Test
    fun rfc1918WithoutOptInIsRejected() {
        val policy = NetworkPolicy(
            allowInsecureLocal = { false },
            trustedProxyHosts = { setOf("192.168.1.5") },
        )
        val chain = testChain("http://192.168.1.5:13456/proxy")
        val e = assertThrows(IOException::class.java) {
            policy.intercept(chain)
        }
        assertTrue(e.message!!.contains("запрещено политикой безопасности"))
    }

    @Test
    fun officialApisOverHttpAreRejected() {
        val policy = NetworkPolicy(
            allowInsecureLocal = { true },
            trustedProxyHosts = { emptySet() },
        )

        for (domain in NetworkPolicy.OFFICIAL_DOMAINS) {
            val chain = testChain("http://$domain/v1/chat")
            val e = assertThrows(IOException::class.java) {
                policy.intercept(chain)
            }
            assertTrue(e.message!!.contains("требует обязательного HTTPS-соединения"))
        }
    }
}
