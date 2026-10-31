package app.nastavnik.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class UsageTest {
    @Test
    fun pricesForKnownAndUnknownModels() {
        assertEquals(Pricing.Price(2.0, 10.0), Pricing.of("claude-sonnet-5-5"))
        assertEquals(Pricing.Price(0.10, 0.50), Pricing.of("claude-haiku-5-5"))
        assertEquals(Pricing.Price(4.0, 20.0), Pricing.of("claude-opus-5-5"))
        assertEquals(Pricing.Price(0.10, 0.40), Pricing.of("gemini-flash-lite-latest"))
        assertEquals(Pricing.Price(1.25, 10.0), Pricing.of("gemini-pro-latest"))
        assertEquals(Pricing.DEFAULT, Pricing.of("какая-то-модель"))
        // 10 000 токенов на вход и 1 000 на выход у Sonnet 5.5 — 3 цента.
        assertEquals(0.03, Pricing.cost("claude-sonnet-5-5", Usage(10_000, 1_000)), 1e-9)
    }

    @Test
    fun jpegSizeFromHeader() {
        // Минимальный JPEG-заголовок: SOI, APP0 (16 байт), SOF0 с размером 1200×800.
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        b.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16)); b.write(ByteArray(14))
        b.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (800 shr 8).toByte(), (800 and 0xFF).toByte(), (1200 shr 8).toByte(), (1200 and 0xFF).toByte()))
        b.write(ByteArray(12))
        assertEquals(1200 to 800, JpegSize.of(b.toByteArray()))
        assertEquals(1280, TokenEstimate.image(b.toByteArray()))
        assertNull(JpegSize.of(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun logAddsByDayAndKeepsTwoMonths() {
        var log = UsageLog.add(emptyList(), 100, Usage(1000, 100), 0.01)
        log = UsageLog.add(log, 100, Usage(500, 50), 0.02)
        log = UsageLog.add(log, 101, Usage(1, 1), 0.5)
        assertEquals(2, UsageLog.today(log, 100).requests)
        assertEquals(0.03, UsageLog.today(log, 100).usd, 1e-9)
        assertEquals(0.53, UsageLog.total(log, 101, 7), 1e-9)
        assertEquals(0.5, UsageLog.total(log, 101, 1), 1e-9)
        log = UsageLog.add(log, 100L + UsageLog.KEEP_DAYS + 5, Usage(1, 1), 0.0)
        assertTrue(log.none { it.day == 100L })
        assertEquals(log, UsageLog.decode(UsageLog.encode(log)))
        assertEquals("меньше цента", UsageLog.money(0.004))
        assertEquals("$0.12", UsageLog.money(0.1234))
    }

    @Test
    fun streamTokensAreDecoded() {
        val start = StreamDecoder.decode(SseEvent("message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1200,\"cache_read_input_tokens\":300,\"output_tokens\":1}}}"))
        assertEquals(StreamUpdate.Tokens(1500, 1), start)
        val stop = StreamDecoder.decode(SseEvent("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":420}}"))
        assertEquals(StreamUpdate.Stop("end_turn", 420), stop)
    }

    private class FakeBackend(val tokens: Boolean) : AiBackend {
        override val provider = AiProvider.ANTHROPIC
        override suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? {
            if (tokens) onUpdate(StreamUpdate.Tokens(5000, null))
            onUpdate(StreamUpdate.Text("Ответ из тридцати символов....."))
            onUpdate(StreamUpdate.Stop("end_turn", if (tokens) 77 else null))
            return "end_turn"
        }
        override suspend fun complete(apiKey: String, request: ChatRequest) = Completion("{}", "end_turn", Usage(10, 20))
        override suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = KeyCheck.Ok
        override suspend fun listModels(apiKey: String) = emptyList<String>()
    }

    private class FakeMeter(var blocked: Boolean = false) : UsageMeter {
        val records = ArrayList<Pair<String, Usage>>()
        override fun check() { if (blocked) throw AiErrors.budget("$1") }
        override fun record(model: String, usage: Usage) { records += model to usage }
    }

    @Test
    fun meteredBackendRecordsExactOrEstimatedTokensAndEnforcesLimit() = runBlocking<Unit> {
        val req = ChatRequest("claude-sonnet-5-5", "система", listOf(ChatTurn(Role.USER, "вопрос")), maxTokens = 100)
        val meter = FakeMeter()
        MeteredBackend(FakeBackend(tokens = true), meter).stream("k", req) {}
        assertEquals("claude-sonnet-5-5" to Usage(5000, 77), meter.records.last())
        MeteredBackend(FakeBackend(tokens = false), meter).stream("k", req) {}
        assertEquals(Usage(TokenEstimate.request(req), 11), meter.records.last().second)
        MeteredBackend(FakeBackend(tokens = false), meter).complete("k", req)
        assertEquals(Usage(10, 20), meter.records.last().second)
        meter.blocked = true
        try {
            MeteredBackend(FakeBackend(tokens = true), meter).stream("k", req) {}
            fail("лимит не сработал")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.BUDGET, e.kind)
        }
        assertEquals(3, meter.records.size)
    }
}
