package com.asmr.player.service

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import com.asmr.player.ui.common.buildFuriganaSpanned
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleBilingualOrder
import com.asmr.player.util.SubtitleDisplayMode
import com.asmr.player.util.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsFuriganaTest {

    private val source = object : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private fun overlay(): FloatingLyricsView =
        FloatingLyricsView(RuntimeEnvironment.getApplication())

    @Test
    fun updateLine_withAnnotatedText_rendersSpansIntoTextView() {
        val view = overlay()
        val entry = SubtitleEntry(0L, 1_000L, "中文", "日本")
        // 钉在 CHINESE_FIRST：这条测试要断的是注音 span 进到了 TextView，不是双语顺序。
        val plain = entry.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val annotated = buildFuriganaSpanned(
            segments = entry.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST),
            plainFallback = plain,
            furigana = FuriganaSpec(enabled = true, source = source)
        )

        view.updateLine(text = plain, cue = entry, annotated = annotated)

        val shown = findTextView(view).text
        assertEquals("中文\n日本にほん", shown.toString())
        assertNotNull((shown as Spanned).getSpans(0, shown.length, RelativeSizeSpan::class.java).firstOrNull())
    }

    @Test
    fun updateLine_chineseModeEmptySegmentsStillShowTheWholeLine() {
        // 修订 6 在覆盖层的落点：中文模式没有分段，兜底文本必须原样显示。
        val view = overlay()
        val entry = SubtitleEntry(0L, 1_000L, "只有中文")
        val plain = entry.displayText(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        val annotated = buildFuriganaSpanned(
            segments = entry.displaySegmentsFor(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST),
            plainFallback = plain,
            furigana = FuriganaSpec(enabled = true, source = source)
        )
        view.updateLine(text = plain, cue = entry, annotated = annotated)
        assertEquals("只有中文", findTextView(view).text.toString())
    }

    @Test
    fun updateLine_withoutAnnotatedText_keepsPlainBehavior() {
        val view = overlay()
        view.updateLine(text = "只有中文", cue = null, annotated = null)
        assertEquals("只有中文", findTextView(view).text.toString())
    }

    @Test
    fun updateLine_dedupesByPlainTextNotBySpannedIdentity() {
        val view = overlay()
        view.updateLine(text = "日本", annotated = "日本A")
        val first = findTextView(view).text
        // 纯文本相同则不重建，避免每帧 setText 触发覆盖层重绘
        view.updateLine(text = "日本", annotated = "日本B")
        assertEquals(first, findTextView(view).text)
    }

    @Test
    fun updateLine_plainTextChangedButAnnotationSame_replacesText() {
        val view = overlay()
        view.updateLine(text = "日本", annotated = "日本にほん")
        view.updateLine(text = "日本語", annotated = "日本語にほんご")
        assertEquals("日本語にほんご", findTextView(view).text.toString())
    }

    private fun findTextView(view: FloatingLyricsView): TextView {
        var found: TextView? = null
        fun walk(node: android.view.View): Boolean {
            if (node is TextView) { found = node; return true }
            if (node is android.view.ViewGroup) {
                for (i in 0 until node.childCount) if (walk(node.getChildAt(i))) return true
            }
            return false
        }
        walk(view)
        return requireNotNull(found) { "覆盖层里找不到 TextView" }
    }
}
