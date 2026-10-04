package com.asmr.player.subtitle

import com.asmr.player.data.settings.CustomAiApiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBackendGateTest {
    @Test
    fun deepSeekKeyIsAcceptedWhenCustomApiDisabled() {
        assertTrue(hasUsableTranslationBackend(true, CustomAiApiSettings(), false))
    }

    @Test
    fun blocksWhenDeepSeekMissingAndCustomDisabled() {
        assertFalse(hasUsableTranslationBackend(false, CustomAiApiSettings(), false))
    }

    @Test
    fun customEnabled_requiresKeyUrlAndModel() {
        val complete = CustomAiApiSettings(
            enabled = true,
            apiUrl = "https://x.com/v1/chat/completions",
            model = "kimi-k2"
        )
        assertTrue(hasUsableTranslationBackend(false, complete, true))
        assertFalse(hasUsableTranslationBackend(false, complete, false))
        assertFalse(hasUsableTranslationBackend(false, complete.copy(model = "  "), true))
        assertFalse(hasUsableTranslationBackend(false, complete.copy(apiUrl = "https://x.com/v1"), true))
    }

    @Test
    fun customEnabledDoesNotFallBackToDeepSeekWhenIncomplete() {
        val enabledButEmpty = CustomAiApiSettings(enabled = true)
        assertFalse(hasUsableTranslationBackend(true, enabledButEmpty, false))
    }
}
