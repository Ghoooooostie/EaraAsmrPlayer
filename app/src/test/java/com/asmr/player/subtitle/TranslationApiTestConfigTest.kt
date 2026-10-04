package com.asmr.player.subtitle

import com.asmr.player.data.settings.CustomAiApiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationApiTestConfigTest {
    @Test
    fun customEnabled_andComplete_resolvesCustomEndpoint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(
                enabled = true,
                apiUrl = "https://api.groq.com/openai/v1/chat/completions",
                model = "llama-3.3-70b-versatile"
            ),
            customApiKey = "gsk-custom",
            deepSeekApiKey = ""
        )
        assertEquals(
            TranslationApiTestConfig.Endpoint(
                apiUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = "gsk-custom",
                model = "llama-3.3-70b-versatile"
            ),
            config
        )
    }

    @Test
    fun customEnabled_missingKey_reportsApiKeyHint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(enabled = true, apiUrl = "https://a.b/v1/chat/completions", model = "m"),
            customApiKey = "",
            deepSeekApiKey = "sk-ds"
        )
        assertTrue(config is TranslationApiTestConfig.NotReady)
        assertTrue((config as TranslationApiTestConfig.NotReady).message.contains("API Key"))
    }

    @Test
    fun customEnabled_invalidUrl_reportsEndpointHint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(enabled = true, apiUrl = "https://a.b/v1", model = "m"),
            customApiKey = "gsk-custom",
            deepSeekApiKey = "sk-ds"
        )
        assertTrue(config is TranslationApiTestConfig.NotReady)
        assertTrue((config as TranslationApiTestConfig.NotReady).message.contains("端点"))
    }

    @Test
    fun customEnabled_blankModel_reportsEndpointHint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(enabled = true, apiUrl = "https://a.b/v1/chat/completions", model = "  "),
            customApiKey = "gsk-custom",
            deepSeekApiKey = "sk-ds"
        )
        assertTrue(config is TranslationApiTestConfig.NotReady)
        assertTrue((config as TranslationApiTestConfig.NotReady).message.contains("模型名"))
    }

    @Test
    fun deepSeekMode_resolvesBundledEndpoint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(enabled = false),
            customApiKey = "",
            deepSeekApiKey = "sk-ds"
        )
        assertEquals(
            TranslationApiTestConfig.Endpoint(
                apiUrl = "https://api.deepseek.com/chat/completions",
                apiKey = "sk-ds",
                model = DEEPSEEK_SUBTITLE_MODEL
            ),
            config
        )
    }

    @Test
    fun deepSeekMode_missingKey_reportsApiKeyHint() {
        val config = resolveTranslationApiTestConfig(
            customSettings = CustomAiApiSettings(enabled = false),
            customApiKey = "gsk-custom",
            deepSeekApiKey = ""
        )
        assertTrue(config is TranslationApiTestConfig.NotReady)
        assertTrue((config as TranslationApiTestConfig.NotReady).message.contains("API Key"))
    }
}
