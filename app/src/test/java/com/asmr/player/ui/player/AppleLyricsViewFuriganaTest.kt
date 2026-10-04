package com.asmr.player.ui.player

import androidx.compose.ui.unit.sp
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleEntry
import com.asmr.player.util.clearAnnotateCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleLyricsViewFuriganaTest {

    private class Source : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private val bilingualEntry = SubtitleEntry(0L, 1_000L, "中文", "日本").copy(
        text = "中文\n日本",
        displaySegments = listOf(
            DisplaySegment("中文", japanese = false),
            DisplaySegment("日本", japanese = true)
        )
    )

    @Before
    fun setUp() = clearAnnotateCache()

    @Test
    fun lyricLineAnnotated_offIsByteIdenticalToPlainText() {
        val built = lyricLineAnnotated(bilingualEntry, FuriganaSpec.NONE, 11.sp)
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_withReadyDictionaryAnnotatesOnlyJapaneseSegment() {
        val built = lyricLineAnnotated(
            bilingualEntry,
            FuriganaSpec(enabled = true, source = Source()),
            11.sp
        )
        assertEquals("中文\n日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun lyricLineAnnotated_nullSourceBehavesLikeOff() {
        // 开关开了但词典还没就绪：文本必须完全不变，且不能崩。
        val built = lyricLineAnnotated(bilingualEntry, FuriganaSpec(enabled = true, source = null), 11.sp)
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_chineseModeEmptySegmentsStillRenderTheWholeLine() {
        // 修订 6 的渲染点落位：中文模式（及一切没有分段的行）不能画成空白。
        val chinese = SubtitleEntry(0L, 1_000L, "中文行")
        assertEquals(emptyList<DisplaySegment>(), chinese.displaySegments)
        val built = lyricLineAnnotated(chinese, FuriganaSpec(enabled = true, source = Source()), 11.sp)
        assertEquals("中文行", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_externalJapaneseSubtitleIsNeverGuessed() {
        // 外挂字幕只有 text、没有分段信息，绝不能进词典（语言未标注 = 不注音）。
        val external = SubtitleEntry(0L, 1_000L, "日本語")
        val built = lyricLineAnnotated(external, FuriganaSpec(enabled = true, source = Source()), 11.sp)
        assertEquals("日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }
}
