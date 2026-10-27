package app.nastavnik.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    private val sample = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-5-5","stop_reason":null}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: ping
        data: {"type": "ping"}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Вспомни "}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"определение $\\lim$."}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    @Test
    fun parsesEventsAndData() {
        val events = SseParser.parseAll(sample)
        assertEquals(10, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("message_stop", events.last().event)
    }

    @Test
    fun decodesTextThinkingAndStop() {
        val updates = SseParser.parseAll(sample).mapNotNull(StreamDecoder::decode)
        val text = updates.filterIsInstance<StreamUpdate.Text>().joinToString("") { it.delta }
        assertEquals("Вспомни определение \$\\lim\$.", text)
        assertTrue(updates.first() is StreamUpdate.Thinking)
        val stops = updates.filterIsInstance<StreamUpdate.Stop>()
        assertEquals("end_turn", stops.first().stopReason)
    }

    @Test
    fun multiLineDataIsJoinedAndCommentsSkipped() {
        val p = SseParser()
        assertNull(p.feedLine(": comment"))
        assertNull(p.feedLine("event: x"))
        assertNull(p.feedLine("data: line1"))
        assertNull(p.feedLine("data:line2\r"))
        val ev = p.feedLine("")
        assertEquals(SseEvent("x", "line1\nline2"), ev)
    }

    @Test
    fun lastEventWithoutTrailingBlankLineIsFlushed() {
        val events = SseParser.parseAll("event: message_stop\ndata: {\"type\":\"message_stop\"}")
        assertEquals(1, events.size)
        assertTrue(StreamDecoder.decode(events[0]) is StreamUpdate.Stop)
    }

    @Test
    fun decodesStreamErrorAndFallbackRestart() {
        val err = StreamDecoder.decode(
            SseEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""),
        )
        assertEquals(StreamUpdate.Error("overloaded_error", "Overloaded"), err)
        val restart = StreamDecoder.decode(
            SseEvent(
                "content_block_start",
                """{"type":"content_block_start","index":2,"content_block":{"type":"fallback","model":"claude-sonnet-5"}}""",
            ),
        )
        assertEquals(StreamUpdate.Restart, restart)
    }

    @Test
    fun ignoresGarbage() {
        assertNull(StreamDecoder.decode(SseEvent("ping", "not json")))
        assertNull(StreamDecoder.decode(SseEvent(null, "")))
    }
}
