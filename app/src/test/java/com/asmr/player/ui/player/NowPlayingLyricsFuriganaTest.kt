package com.asmr.player.ui.player

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleBilingualOrder
import com.asmr.player.util.SubtitleDisplayMode
import com.asmr.player.util.SubtitleEntry
import com.asmr.player.util.withDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsFuriganaTest {

    private val source = object : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private val entry = SubtitleEntry(0L, 1_000L, "中文", "日本")

    @Test
    fun trackAnnotationIsDerivedFromEntryIndexSoPreviewContentKeepsPlainStrings() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(
            sortedLyrics = lyrics,
            index = 0,
            fallback = "暂无歌词",
            furigana = FuriganaSpec(enabled = true, source = source),
            rubyFontSize = 11.sp
        )
        assertEquals("中文\n日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun trackAnnotation_offProducesByteIdenticalPlainText() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(
            lyrics,
            0,
            "暂无歌词",
            FuriganaSpec.NONE,
            11.sp
        )
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun trackAnnotation_offKeepsExistingWhitespaceNormalization() {
        // 预览轨道原本对文本做「压空格 / 去 BOM / 逐行 trim」。分段本身不带这个规则，
        // 所以 upcomingLyricText 必须逐段归一化，否则关掉注音后输出就和现状不同。
        val messy = SubtitleEntry(0L, 1_000L, "中文  带空格", "日本")
        val lyrics = listOf(messy).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val normalized = normalizeMultilineText(lyrics.first().text)
        val built = upcomingLyricText(lyrics, 0, normalized, FuriganaSpec.NONE, 11.sp)
        assertEquals(normalized, built.text)
    }

    @Test
    fun marqueeAnnotation_offMatchesCurrentSingleLineCollapse() {
        // 跑马灯今天显示的是 normalizeSingleLineText(整行文本)，
        // 关掉注音时新路径必须给出同一个字符串。
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val collapsed = normalizeSingleLineText(lyrics.first().text)
        val built = marqueeLyricText(lyrics, 0, lyrics.first().text, FuriganaSpec.NONE, 11.sp)
        assertEquals(collapsed, built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun marqueeAnnotation_insertsReadingInsideTheCollapsedLine() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = marqueeLyricText(
            lyrics,
            0,
            lyrics.first().text,
            FuriganaSpec(enabled = true, source = source),
            11.sp
        )
        assertEquals("中文 日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun trackAnnotation_unknownIndexUsesFallback() {
        val built = upcomingLyricText(emptyList(), 3, "暂无歌词", FuriganaSpec(true, source), 11.sp)
        assertEquals(AnnotatedString("暂无歌词").text, built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun trackAnnotation_blankEntryTextFallsBackLikeCurrentBehavior() {
        val lyrics = listOf(SubtitleEntry(0L, 1_000L, "  ", "  ")).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(lyrics, 0, "暂无歌词", FuriganaSpec(true, source), 11.sp)
        // 现有逻辑会用 normalizeMultilineText + ifBlank 兜底，注音不能破坏它
        assertEquals("", built.text.trim())
    }

    @Test
    fun trackAnnotation_followsBilingualOrderInsteadOfHardcodingIt() {
        // 上面的用例统一钉在 CHINESE_FIRST，是为了让逐字节断言专注注音本身；
        // 这一条证明注音真的跟随分段顺序，而不是把「中文在前」写死在渲染层。
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        val built = upcomingLyricText(
            lyrics,
            0,
            "暂无歌词",
            FuriganaSpec(enabled = true, source = source),
            11.sp
        )
        assertEquals("日本にほん\n中文", built.text)
        assertEquals(1, built.spanStyles.size)
    }
}
