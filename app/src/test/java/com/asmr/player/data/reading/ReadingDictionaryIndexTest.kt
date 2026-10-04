package com.asmr.player.data.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingDictionaryIndexTest {

    private val words = listOf(
        "日本語\tにほんご",
        "日本\tにほん",
        "美味しい\tおいしい",
        "難しい\tむずかしい",
        "猫\tねこ",
        "食べる\tたべる"
    )

    private fun index(): ReadingDictionaryIndex =
        ReadingDictionaryIndex.parse(words, ReadingDictionaryIndex.deriveKanjiLines(words))

    @Test
    fun parse_isReadyAndExposesLongestSurface() {
        val index = index()
        assertTrue(index.ready)
        assertEquals(4, index.maxSurfaceLength) // 日本語 = 3 字，美味しい = 4 字
    }

    @Test
    fun readingOf_findsExactEntryRegardlessOfInputOrder() {
        assertEquals("にほんご", index().readingOf("日本語"))
        assertEquals("おいしい", index().readingOf("美味しい"))
    }

    @Test
    fun readingOf_returnsNullForUnknownSurface() {
        assertNull(index().readingOf("猫猫"))
        assertNull(index().readingOf(""))
    }

    @Test
    fun readingOf_ignoresLinesWithoutTabOrWithBlankReading() {
        val index = ReadingDictionaryIndex.parse(listOf("坏行", "空\t", "好\tかん"), emptyList())
        assertNull(index.readingOf("坏行"))
        assertNull(index.readingOf("空"))
        assertEquals("かん", index.readingOf("好"))
    }

    @Test
    fun parse_keepsFirstReadingForDuplicateSurface() {
        val index = ReadingDictionaryIndex.parse(
            listOf("日本\tにほん", "日本\tにほん", "日本\tやまと"),
            emptyList()
        )
        // 生成脚本已按词频排序，同表层多读音时保留先出现的一条；重复条目不得让查询崩溃或错乱。
        assertEquals("にほん", index.readingOf("日本"))
        assertEquals(3, index.surfaceCount)
    }

    @Test
    fun deriveKanjiLines_onlyUsesSingleKanjiWordsAndTrimsOkurigana() {
        val derived = ReadingDictionaryIndex.deriveKanjiLines(words).toMap()
        assertEquals("ねこ", derived.getValue("猫"))   // 无送假名，整条读音就是这个字
        assertEquals("た", derived.getValue("食"))     // 食べる/たべる 去掉送假名 べる
        assertEquals("むずか", derived.getValue("難")) // 難しい/むずかしい 去掉送假名 しい
        // 修订 4：含两个以上汉字的词条一律不参与派生——
        // 日本語/にほんご、美味しい/おいしい 的读音无法可靠切给单个汉字，宁缺勿错。
        assertFalse(derived.containsKey("日"))
        assertFalse(derived.containsKey("本"))
        assertFalse(derived.containsKey("美"))
        assertFalse(derived.containsKey("味"))
    }

    @Test
    fun deriveKanjiLines_prefersTheMostFrequentReadingPerKanji() {
        val lines = ReadingDictionaryIndex.deriveKanjiLines(
            listOf("行\tいく", "行\tこう", "行\tこう")
        ).toMap()
        assertEquals("こう", lines.getValue("行"))
    }

    @Test
    fun emptyDictionaryReportsNotReady() {
        val index = ReadingDictionaryIndex.parse(emptyList(), emptyList())
        assertFalse(index.ready)
        assertEquals(0, index.maxSurfaceLength)
        assertNull(index.kanjiReadingOf('猫'))
    }

    private fun List<String>.toMap(): Map<String, String> =
        associate { it.substringBefore('\t') to it.substringAfter('\t') }
}
