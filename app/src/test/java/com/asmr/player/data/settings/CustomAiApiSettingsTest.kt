package com.asmr.player.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomAiApiSettingsTest {
    @Test
    fun normalizeCustomAiApiUrl_acceptsPlainChatCompletionsUrl() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            normalizeCustomAiApiUrl("https://api.deepseek.com/chat/completions")
        )
    }

    @Test
    fun normalizeCustomAiApiUrl_acceptsVersionedPathAndTrimsWhitespaceAndTrailingSlash() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            normalizeCustomAiApiUrl("  https://example.com/v1/chat/completions/  ")
        )
    }

    @Test
    fun normalizeCustomAiApiUrl_acceptsHttpForLoopbackHosts() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            normalizeCustomAiApiUrl("http://127.0.0.1:11434/v1/chat/completions")
        )
        assertEquals(
            "http://localhost:1234/v1/chat/completions",
            normalizeCustomAiApiUrl("http://localhost:1234/v1/chat/completions")
        )
    }

    @Test
    fun normalizeCustomAiApiUrl_rejectsInsecureHttpForRemoteHosts() {
        assertNull(normalizeCustomAiApiUrl("http://example.com/chat/completions"))
    }

    @Test
    fun normalizeCustomAiApiUrl_rejectsBlankAndUnparsable() {
        assertNull(normalizeCustomAiApiUrl(""))
        assertNull(normalizeCustomAiApiUrl("   "))
        assertNull(normalizeCustomAiApiUrl("not a url"))
        assertNull(normalizeCustomAiApiUrl("ftp://example.com/chat/completions"))
    }

    @Test
    fun normalizeCustomAiApiUrl_rejectsUrlWithoutChatCompletionsPath() {
        assertNull(normalizeCustomAiApiUrl("https://example.com"))
        assertNull(normalizeCustomAiApiUrl("https://example.com/v1"))
        assertNull(normalizeCustomAiApiUrl("https://example.com/completions"))
    }
}
