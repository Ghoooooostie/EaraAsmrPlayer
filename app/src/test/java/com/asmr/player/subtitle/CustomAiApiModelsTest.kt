package com.asmr.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomAiApiModelsTest {
    @Test
    fun modelsUrl_derivesFromChatCompletionsEndpoint() {
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            customAiModelsUrl("https://api.groq.com/openai/v1/chat/completions")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            customAiModelsUrl("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
        )
        assertEquals(
            "https://api.deepseek.com/models",
            customAiModelsUrl("https://api.deepseek.com/chat/completions")
        )
        assertNull(customAiModelsUrl("not-a-url"))
        assertNull(customAiModelsUrl("https://api.example.com/v1"))
    }

    @Test
    fun parseModelIds_readsOpenAiDataIds() {
        val raw = """{"object":"list","data":[{"id":"llama-3.3-70b-versatile","object":"model"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(
            listOf("gpt-4o-mini", "llama-3.3-70b-versatile"),
            parseCustomAiModelIds(raw)
        )
    }

    @Test
    fun parseModelIds_readsGoogleNameAndStripsModelsPrefix() {
        val raw = """
            {"data":[
              {"name":"models/gemini-2.5-pro","supported_generation_methods":["generateContent"]},
              {"name":"models/gemini-2.5-flash"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-pro"),
            parseCustomAiModelIds(raw)
        )
    }

    @Test
    fun parseModelIds_deduplicatesAndIgnoresMalformedEntries() {
        val raw = """
            {"data":[
              {"id":"a","name":"models/a"},
              {"id":"a"},
              {"name":"models/b"},
              {"id":123},
              {}
            ]}
        """.trimIndent()
        assertEquals(listOf("a", "b"), parseCustomAiModelIds(raw))
    }

    @Test
    fun parseModelIds_returnsEmptyForInvalidOrEmptyPayloads() {
        assertEquals(emptyList<String>(), parseCustomAiModelIds("not-json"))
        assertEquals(emptyList<String>(), parseCustomAiModelIds("""{"data":[]}"""))
        assertEquals(emptyList<String>(), parseCustomAiModelIds("""{"models":["x"]}"""))
        assertEquals(emptyList<String>(), parseCustomAiModelIds(""))
    }
}
