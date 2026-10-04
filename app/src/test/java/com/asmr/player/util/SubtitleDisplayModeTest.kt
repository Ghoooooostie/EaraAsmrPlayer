package com.asmr.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDisplayModeTest {

    private val generated = SubtitleEntry(startMs = 0L, endMs = 1_000L, text = "中文", japaneseText = "日文")

    @Test
    fun chineseMode_usesChineseOnly() {
        assertEquals("中文", generated.displayText(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST))
    }

    @Test
    fun japaneseMode_fallsBackToChineseWithoutOriginal() {
        assertEquals("日文", generated.displayText(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST))
        assertEquals(
            "中文",
            SubtitleEntry(0L, 1_000L, "中文").displayText(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun bilingualMode_japaneseAboveChinese() {
        assertEquals("日文\n中文", generated.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST))
    }

    @Test
    fun bilingualMode_chineseAboveJapanese() {
        assertEquals("中文\n日文", generated.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST))
    }

    @Test
    fun bilingualMode_missingLine_staysSingleLineInBothOrders() {
        val noJapanese = SubtitleEntry(0L, 1_000L, "中文")
        val noChinese = SubtitleEntry(0L, 1_000L, "", japaneseText = "日文")
        assertEquals("中文", noJapanese.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST))
        assertEquals("中文", noJapanese.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST))
        assertEquals("日文", noChinese.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST))
    }

    @Test
    fun bilingualMode_identicalText_staysSingleLine() {
        assertEquals(
            "同じ",
            SubtitleEntry(0L, 1_000L, "同じ", "同じ").displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun withDisplayMode_appliesBilingualOrderAndPreservesTimeline() {
        val mapped = listOf(generated).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        assertEquals(1, mapped.size)
        assertEquals("日文\n中文", mapped.first().text)
        assertEquals(0L, mapped.first().startMs)
        assertEquals(1_000L, mapped.first().endMs)
        assertEquals("日文", mapped.first().japaneseText)
    }

    @Test
    fun withDisplayMode_chineseReturnsSameList() {
        val source = listOf(generated)
        assertEquals(source, source.withDisplayMode(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST))
    }

    @Test
    fun fromStorageValue_unknownFallsBackToChinese() {
        assertEquals(SubtitleDisplayMode.CHINESE, SubtitleDisplayMode.fromStorageValue(null))
        assertEquals(SubtitleDisplayMode.CHINESE, SubtitleDisplayMode.fromStorageValue("unknown"))
        assertEquals(SubtitleDisplayMode.JAPANESE, SubtitleDisplayMode.fromStorageValue("ja"))
        assertEquals(SubtitleDisplayMode.BILINGUAL, SubtitleDisplayMode.fromStorageValue("bilingual"))
    }

    @Test
    fun bilingualOrder_fromStorageValue_defaultsToJapaneseFirst() {
        assertEquals(SubtitleBilingualOrder.JAPANESE_FIRST, SubtitleBilingualOrder.fromStorageValue(null))
        assertEquals(SubtitleBilingualOrder.JAPANESE_FIRST, SubtitleBilingualOrder.fromStorageValue("garbage"))
        assertEquals(SubtitleBilingualOrder.CHINESE_FIRST, SubtitleBilingualOrder.fromStorageValue("zh_first"))
        assertEquals(SubtitleBilingualOrder.JAPANESE_FIRST, SubtitleBilingualOrder.fromStorageValue("ja_first"))
    }

    @Test
    fun displaySegmentsFor_chineseModeHasNoSegments() {
        assertEquals(
            emptyList<DisplaySegment>(),
            generated.displaySegmentsFor(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_japaneseModeMarksSingleJapaneseSegment() {
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true)),
            generated.displaySegmentsFor(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        // 没有日文原文时回退中文，且不能标记成日文，否则中文会被送进词典。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文")
                .displaySegmentsFor(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_bilingualFollowsSelectedOrderAndMarksOnlyTheJapaneseLine() {
        // 双语顺序由兄弟特性提供，分段顺序必须与 displayText 的压平顺序一致。
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true), DisplaySegment("中文", japanese = false)),
            generated.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false), DisplaySegment("日文", japanese = true)),
            generated.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_bilingualDegenerateCasesStaySingleSegmentAndMatchText() {
        val identical = SubtitleEntry(0L, 1_000L, "同じ", "同じ")
        // 两栏字面相同仍是单行；内容是日文（含假名）就照常标日文，否则这类字幕永远注不上音。
        assertEquals(
            listOf(DisplaySegment("同じ", japanese = true)),
            identical.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        // 相同但确实是中文 → 仍然挡住，不进词典。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文", "中文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true)),
            SubtitleEntry(0L, 1_000L, "", "日文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_japanesePrimarySubtitleInMainFieldIsStillMarkedJapanese() {
        // 日文播客/AI 生成字幕常常把日文放在 text 字段、japaneseText 留空。
        // 若按字段一律当中文处理，这类字幕永远进不了词典（真机上表现为「开关开了但没注音」）。
        val japaneseInMainField = SubtitleEntry(0L, 1_000L, "先週はもうアップロードせずに")
        for (mode in listOf(SubtitleDisplayMode.JAPANESE, SubtitleDisplayMode.BILINGUAL)) {
            assertEquals(
                listOf(DisplaySegment("先週はもうアップロードせずに", japanese = true)),
                japaneseInMainField.displaySegmentsFor(mode, SubtitleBilingualOrder.JAPANESE_FIRST)
            )
        }
        // 纯中文仍必须挡住：中文句子不含假名。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        // 中文模式永远空分段（验收：中文模式三处都无注音）。
        assertEquals(
            emptyList<DisplaySegment>(),
            japaneseInMainField.displaySegmentsFor(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        // japaneseText 被填成原文（两栏字面相同）时同样要能注音——真机数据就是这条路径。
        assertEquals(
            listOf(DisplaySegment("先週はもうアップロードせずに", japanese = true)),
            SubtitleEntry(0L, 1_000L, "先週はもうアップロードせずに", "先週はもうアップロードせずに")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_alwaysRoundTripsIntoDisplayText() {
        for (entry in listOf(
            generated,
            SubtitleEntry(0L, 1_000L, "中文"),
            SubtitleEntry(0L, 1_000L, "", "日文"),
            SubtitleEntry(0L, 1_000L, "同じ", "同じ")
        )) {
            for (mode in SubtitleDisplayMode.entries) {
                for (order in SubtitleBilingualOrder.entries) {
                    assertEquals(
                        entry.displayText(mode, order),
                        entry.displaySegmentsFor(mode, order).orPlainFallback(entry.text).joinToString("\n") { it.text }
                    )
                }
            }
        }
    }

    @Test
    fun withDisplayMode_fillsSegmentsInSelectedOrder() {
        val japanese = listOf(generated)
            .withDisplayMode(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(listOf(DisplaySegment("日文", japanese = true)), japanese.displaySegments)

        val bilingual = listOf(generated)
            .withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true), DisplaySegment("中文", japanese = false)),
            bilingual.displaySegments
        )
        assertEquals("日文\n中文", bilingual.text)
    }

    @Test
    fun orPlainFallback_keepsSegmentsWhenPresent() {
        val segments = listOf(DisplaySegment("日文", japanese = true))
        assertEquals(segments, segments.orPlainFallback("整行文本"))
    }

    @Test
    fun orPlainFallback_wrapsPlainTextAsNonJapaneseWhenSegmentsAreEmpty() {
        // 中文模式与外挂字幕都是空分段；不兜底的话渲染点会拿到空列表并画出空白行。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            emptyList<DisplaySegment>().orPlainFallback("中文")
        )
        assertEquals(
            listOf(DisplaySegment(" ", japanese = false)),
            emptyList<DisplaySegment>().orPlainFallback(" ")
        )
    }

    @Test
    fun withDisplayMode_chineseLeavesSegmentsEmpty() {
        val entry = listOf(generated.copy(displaySegments = listOf(DisplaySegment("旧", true))))
            .withDisplayMode(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(emptyList<DisplaySegment>(), entry.displaySegments)
        // 空分段必须能被渲染点安全兜底回原句文本——这是修订 6 的落点。
        assertEquals(
            listOf(DisplaySegment(entry.text, japanese = false)),
            entry.displaySegments.orPlainFallback(entry.text)
        )
    }
}
