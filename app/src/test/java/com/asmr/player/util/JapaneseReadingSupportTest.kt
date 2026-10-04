package com.asmr.player.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class JapaneseReadingSupportTest {

    private class FakeSource(
        private val words: Map<String, String>,
        private val singleKanji: Map<Char, String> = emptyMap(),
        override val ready: Boolean = true
    ) : ReadingSource {
        override val maxSurfaceLength: Int get() = words.keys.maxOfOrNull { it.length } ?: 0
        override fun readingOf(surface: String): String? = words[surface]
        override fun kanjiReadingOf(kanji: Char): String? = singleKanji[kanji]
    }

    /**
     * 断言 tokens 拼回的基字与 expectedBase 相同，且 (基字 -> 读音) 序列与 readings 一致。
     * readings 必须逐 token 列全：不注音的整段文本也是一个 token（读音为 null）。
     */
    private fun assertAnnotation(
        text: String,
        source: ReadingSource?,
        expectedBase: String,
        vararg readings: Pair<String, String?>
    ) {
        val tokens = annotateJapanese(text, source)
        assertEquals(expectedBase, tokens.joinToString("") { it.base })
        assertEquals(readings.toList(), tokens.map { it.base to it.reading })
    }

    @Test
    fun annotateJapanese_prefersWordOverSingleKanji() {
        val source = FakeSource(
            words = mapOf("昨日" to "きのう", "日" to "ひ"),
            singleKanji = mapOf('昨' to "さく", '日' to "にち")
        )
        assertAnnotation("昨日", source, "昨日", "昨日" to "きのう")
    }

    @Test
    fun annotateJapanese_extendsIntoOkurigana() {
        val source = FakeSource(
            words = mapOf("美味しい" to "おいしい", "美味" to "びみ"),
            singleKanji = mapOf('美' to "び", '味' to "み")
        )
        // 关键用例：没有送假名扩展就会显示成「びみしい」
        assertAnnotation(
            "美味しい",
            source,
            "美味しい",
            "美味" to "おい",
            "しい" to null
        )
    }

    @Test
    fun annotateJapanese_trimsOnlyMatchingOkuriganaTail() {
        val source = FakeSource(words = mapOf("難しい" to "むずかしい"))
        assertAnnotation("難しい", source, "難しい", "難" to "むずか", "しい" to null)
    }

    @Test
    fun annotateJapanese_keepsFullReadingWhenReadingIsNotLongerThanTail() {
        // 词典里 行こう/こう：读音尾部并不包含送假名，dropLast 会把假名吃光，
        // 所以长度不够时必须整条保留——这是 trimReading 的长度守卫。
        val source = FakeSource(words = mapOf("行こう" to "こう"))
        assertAnnotation("行こう", source, "行こう", "行" to "こう", "こう" to null)
    }

    @Test
    fun annotateJapanese_consumesKanjiPrefixPartially() {
        // 日本語 本身没收录，最长匹配退到前缀 日本，剩下的 語 交给下一轮。
        val source = FakeSource(words = mapOf("日本" to "にほん"))
        assertAnnotation(
            "日本語",
            source,
            "日本語",
            "日本" to "にほん",
            "語" to null
        )
    }

    @Test
    fun annotateJapanese_fallsBackToSingleKanji() {
        val source = FakeSource(words = emptyMap(), singleKanji = mapOf('猫' to "ねこ"))
        assertAnnotation("猫", source, "猫", "猫" to "ねこ")
    }

    @Test
    fun annotateJapanese_leavesUnknownKanjiUnannotated() {
        val source = FakeSource(words = emptyMap())
        assertAnnotation("囍", source, "囍", "囍" to null)
    }

    @Test
    fun annotateJapanese_neverAnnotatesKanaDigitsLatinAndPunctuation() {
        val source = FakeSource(words = mapOf("猫" to "ねこ"))
        assertAnnotation(
            "ねこ 12 abc 。",
            source,
            "ねこ 12 abc 。",
            "ねこ 12 abc 。" to null
        )
    }

    @Test
    fun annotateJapanese_absorbsParenthesisedReadingInAllFourBracketForms() {
        val source = FakeSource(words = emptyMap(), singleKanji = mapOf('漢' to "かん"))
        for (open in listOf('（', '(', '［', '[')) {
            val close = mapOf('（' to '）', '(' to ')', '［' to '］', '[' to ']').getValue(open)
            assertAnnotation(
                "漢${open}かん${close}字",
                source,
                "漢字",
                "漢" to "かん",
                "字" to null
            )
        }
    }

    @Test
    fun annotateJapanese_keepsParenthesesWhenInsideIsNotPureKana() {
        val source = FakeSource(words = emptyMap())
        assertAnnotation("漢(A)字", source, "漢(A)字", "漢(A)字" to null)
    }

    @Test
    fun annotateJapanese_nullSourceReturnsPlainText() {
        assertEquals(listOf(ReadingToken("日本語")), annotateJapanese("日本語", null))
        assertEquals(emptyList<ReadingToken>(), annotateJapanese("", null))
    }

    @Test
    fun annotateJapanese_notReadySourceBehavesLikeNull() {
        val source = FakeSource(words = mapOf("日本" to "にほん"), ready = false)
        assertEquals(listOf(ReadingToken("日本")), annotateJapanese("日本", source))
    }

    @Test
    fun annotateJapanese_annotatesChineseBecauseCallerDecidesLanguageNotTheMatcher() {
        // 算法本身不判语言：中文段之所以不会被注音，靠的是 Task 3 的语言分段。
        val source = FakeSource(words = emptyMap(), singleKanji = mapOf('中' to "ちゅう"))
        assertAnnotation("中文", source, "中文", "中" to "ちゅう", "文" to null)
    }

    @Before
    fun setUp() {
        clearAnnotateCache()
    }

    @Test
    fun annotateLine_reusesCachedResultForSameText() {
        var lookups = 0
        val counting = object : ReadingSource {
            private val delegate = FakeSource(mapOf("日本" to "にほん"))
            override val ready get() = true
            override val maxSurfaceLength get() = delegate.maxSurfaceLength
            override fun readingOf(surface: String): String? {
                lookups++
                return delegate.readingOf(surface)
            }
            override fun kanjiReadingOf(kanji: Char): String? = delegate.kanjiReadingOf(kanji)
        }

        val first = annotateLine("日本語", counting)
        val cachedCalls = lookups
        val second = annotateLine("日本語", counting)

        assertEquals(first, second)
        assertEquals(lookups, cachedCalls)
        org.junit.Assert.assertTrue("首调必须发生过词典查询", cachedCalls > 0)
    }

    @Test
    fun annotateLine_doesNotCacheWhileSourceIsNotReady() {
        val notReady = FakeSource(mapOf("日本" to "にほん"), ready = false)
        assertEquals(listOf(ReadingToken("日本語")), annotateLine("日本語", notReady))

        // 预热完成后，同一行必须立刻拿到注音——证明未就绪的结果没有进缓存。
        val ready = FakeSource(mapOf("日本" to "にほん"))
        val annotated = annotateLine("日本語", ready)
        org.junit.Assert.assertTrue(annotated.any { it.reading != null })
    }

    @Test
    fun annotateLine_nullSourceIsNotCached() {
        assertEquals(listOf(ReadingToken("漢字")), annotateLine("漢字", null))
        val ready = FakeSource(mapOf("漢" to "かん"), singleKanji = mapOf('漢' to "かん"))
        org.junit.Assert.assertTrue(annotateLine("漢字", ready).any { it.reading != null })
    }
}
