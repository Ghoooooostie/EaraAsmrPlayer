package com.asmr.player.ui.common

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FURIGANA_READING_SCALE
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.clearAnnotateCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FuriganaTextTest {

    private class FakeSource(
        private val words: Map<String, String>,
        private val singleKanji: Map<Char, String> = emptyMap()
    ) : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = words.keys.maxOfOrNull { it.length } ?: 0
        override fun readingOf(surface: String): String? = words[surface]
        override fun kanjiReadingOf(kanji: Char): String? = singleKanji[kanji]
    }

    private val spec = FuriganaSpec(enabled = true, source = FakeSource(mapOf("日本" to "にほん")))
    private val off = FuriganaSpec(enabled = false, source = spec.source)
    private val ruby = 21.sp * FURIGANA_READING_SCALE

    private val bilingual = listOf(
        DisplaySegment("中文行", japanese = false),
        DisplaySegment("日本語", japanese = true)
    )

    @Before
    fun setUp() = clearAnnotateCache()

    /** 删掉所有注音 span 覆盖的字符，剩下的必须就是原始展示文本。 */
    private fun stripReadings(built: androidx.compose.ui.text.AnnotatedString): String {
        val drop = built.spanStyles.flatMap { it.start until it.end }
            .toSet()
        return built.text.filterIndexed { index, _ -> index !in drop }
    }

    @Test
    fun annotated_whenOff_isByteIdenticalToPlainText() {
        val built = buildFuriganaAnnotatedString(bilingual, "", off, ruby)
        assertEquals("中文行\n日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_onlyJapaneseSegmentGetsSpans() {
        val segments = listOf(
            DisplaySegment("猫", japanese = false),
            DisplaySegment("日本", japanese = true)
        )
        val built = buildFuriganaAnnotatedString(segments, "", spec, ruby)

        assertEquals("猫\n日本にほん", built.text)
        val span = built.spanStyles.single()
        assertEquals(ruby, span.item.fontSize)
        assertEquals(built.text.indexOf("にほん"), span.start)
        assertEquals(built.text.length, span.end)
    }

    @Test
    fun annotated_insertsReadingRightAfterTheKanjiItBelongsTo() {
        // 行内小字注音：读音紧跟自己的基字，送假名留在原位——
        // 美味しい 显示成「美味[おい]しい」，不是把整条读音堆到词尾。
        val okuriganaSpec = FuriganaSpec(
            enabled = true,
            source = FakeSource(mapOf("美味しい" to "おいしい"))
        )
        val built = buildFuriganaAnnotatedString(
            listOf(DisplaySegment("美味しい", japanese = true)),
            "",
            okuriganaSpec,
            ruby
        )
        assertEquals("美味おいしい", built.text)
        assertEquals(1, built.spanStyles.size)
        assertEquals("美味しい", stripReadings(built))
    }

    @Test
    fun annotated_separatorCollapsesSegmentBreaksForMarqueeSurfaces() {
        // 跑马灯那个渲染点把多行折叠成一行，分段连接符必须跟着变，
        // 否则开关关掉时的文本就和现状（normalizeSingleLineText 的结果）不一致。
        val built = buildFuriganaAnnotatedString(bilingual, "", spec, ruby, separator = " ")
        // 读音插在它自己的基字后面：日本[にほん]語
        assertEquals("中文行 日本にほん語", built.text)
        val offBuilt = buildFuriganaAnnotatedString(bilingual, "", off, ruby, separator = " ")
        assertEquals("中文行 日本語", offBuilt.text)
        assertTrue(offBuilt.spanStyles.isEmpty())
    }

    @Test
    fun annotated_emptySegmentsFallBackToPlainFallbackText() {
        // 修订 6：中文模式 / 外挂字幕的分段是空的，必须退回 plainFallback，
        // 否则整行渲染成空白，等于把现有歌词全屏蔽掉。
        val built = buildFuriganaAnnotatedString(emptyList(), "中文", spec, ruby)
        assertEquals("中文", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_blankSegmentsAlsoFallBackToPlainFallbackText() {
        // 悬浮歌词对空白行用 " " 占位；空白段必须同样退回，不能画成真正的空串。
        val built = buildFuriganaAnnotatedString(
            listOf(DisplaySegment("  ", japanese = false)),
            " ",
            spec,
            ruby
        )
        assertEquals(" ", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_plainFallbackTextIsNeverSentToDictionary() {
        val everyWord = object : ReadingSource {
            override val ready get() = true
            override val maxSurfaceLength get() = 3
            override fun readingOf(surface: String) = "かんじ"
            override fun kanjiReadingOf(kanji: Char) = "かんじ"
        }
        val built = buildFuriganaAnnotatedString(
            emptyList(),
            "日本語",
            FuriganaSpec(enabled = true, source = everyWord),
            ruby
        )
        assertEquals("日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun spanned_whenOff_returnsPlainString() {
        val built = buildFuriganaSpanned(bilingual, "", off)
        assertEquals("中文行\n日本語", built.toString())
        assertTrue("关闭时不应产生 Spanned", built is String)
    }

    @Test
    fun spanned_appliesRelativeSizeOnlyToReadings() {
        val segments = listOf(DisplaySegment("日本", japanese = true))
        val built = buildFuriganaSpanned(segments, "", spec) as Spanned
        assertEquals("日本にほん", built.toString())
        val spans = built.getSpans(0, built.length, RelativeSizeSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(FURIGANA_READING_SCALE, spans.single().getSizeChange())
        assertEquals(built.toString().indexOf("にほん"), built.getSpanStart(spans.single()))
        assertEquals(built.length, built.getSpanEnd(spans.single()))
    }

    @Test
    fun spanned_emptySegmentsFallBackToPlainFallbackText() {
        val built = buildFuriganaSpanned(emptyList(), "只有中文", spec)
        assertEquals("只有中文", built.toString())
        assertTrue(built is String)
    }

    @Test
    fun rubyFontSize_followsTheLineStyleSoUserFontScaleIsInherited() {
        val style = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.Default)
        assertEquals(20.sp * FURIGANA_READING_SCALE, furiganaRubyFontSize(style))
    }
}
