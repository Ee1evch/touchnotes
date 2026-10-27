package app.nastavnik.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigParserTest {

    @Test
    fun claudexEnvForClaudeCode() {
        val c = ProxyConfigParser.parse(
            """
            export ANTHROPIC_BASE_URL=http://192.168.1.5:13456/proxy/codex
            export ANTHROPIC_AUTH_TOKEN="claudex-passthrough"
            export ANTHROPIC_MODEL=gpt-5-codex
            """.trimIndent(),
        )!!
        assertEquals(AiProvider.ANTHROPIC_COMPAT, c.provider)
        assertEquals("http://192.168.1.5:13456/proxy/codex", c.baseUrl)
        assertEquals("claudex-passthrough", c.apiKey)
        assertEquals("gpt-5-codex", c.model)
        assertEquals(AuthMode.BEARER, c.authMode)
    }

    @Test
    fun claudeCodeSettingsJson() {
        val c = ProxyConfigParser.parse(
            """
            "env": {
              "ANTHROPIC_BASE_URL": "https://proxy.example.com",
              "ANTHROPIC_API_KEY": "sk-proxy-123456",
            }
            """.trimIndent(),
        )!!
        assertEquals(AiProvider.ANTHROPIC_COMPAT, c.provider)
        assertEquals("https://proxy.example.com", c.baseUrl)
        assertEquals("sk-proxy-123456", c.apiKey)
        assertEquals(AuthMode.X_API_KEY, c.authMode)
    }

    @Test
    fun openAiStyleAndWindowsSet() {
        val c = ProxyConfigParser.parse("set OPENAI_BASE_URL=http://10.0.0.2:8317/v1\nset OPENAI_API_KEY=my-key-123")!!
        assertEquals(AiProvider.OPENAI_COMPAT, c.provider)
        assertEquals("http://10.0.0.2:8317/v1", c.baseUrl)
        assertEquals("my-key-123", c.apiKey)
    }

    @Test
    fun looseUrlAndKey() {
        val c = ProxyConfigParser.parse("http://192.168.0.10:8317/v1\nsk-abcdef123456")!!
        assertEquals(AiProvider.OPENAI_COMPAT, c.provider)
        assertEquals("sk-abcdef123456", c.apiKey)
        val g = ProxyConfigParser.parse("AIzaSyD-test-key-1234567")!!
        assertEquals(AiProvider.GEMINI, g.provider)
        val p = ProxyConfigParser.parse("http://127.0.0.1:13456/proxy/openrouter-claude")!!
        assertEquals(AiProvider.ANTHROPIC_COMPAT, p.provider)
    }

    @Test
    fun nothingUseful() {
        assertNull(ProxyConfigParser.parse("привет, как дела"))
        assertNull(ProxyConfigParser.parse(""))
    }

    @Test
    fun claudeBaseUrlNormalization() {
        assertEquals("http://1.2.3.4:13456/proxy/x", ClaudeClient.normalizeBaseUrl("http://1.2.3.4:13456/proxy/x/v1/messages"))
        assertEquals("https://api.anthropic.com", ClaudeClient.normalizeBaseUrl("https://api.anthropic.com/v1/"))
        assertEquals(ClaudeClient.DEFAULT_BASE_URL, ClaudeClient.normalizeBaseUrl("  "))
    }
}
