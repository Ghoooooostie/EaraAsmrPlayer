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
}
