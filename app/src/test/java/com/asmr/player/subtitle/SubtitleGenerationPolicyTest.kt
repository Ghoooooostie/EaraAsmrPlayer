package com.asmr.player.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleGenerationPolicyTest {
    @Test
    fun supportsFileName_acceptsCommonLocalAudioIgnoringCase() {
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.MP3"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.wav"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.FLAC"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.m4a"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.ogg"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("voice.opus"))
        assertTrue(SubtitleGenerationPolicy.supportsFileName("01_sample.AAC"))
        assertFalse(SubtitleGenerationPolicy.supportsFileName("clip.mp4"))
        assertFalse(SubtitleGenerationPolicy.supportsFileName("cover.jpg"))
    }
}
