package com.asmr.player.data.reading

import com.asmr.player.util.ReadingToken
import com.asmr.player.util.annotateLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class ReadingDictionaryTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** 一个测试床：持有未就绪的词典 + 指向内存 gzip 内容的假资产加载器。 */
    private inner class TestBed(words: String, kanji: String = "", failOn: String? = null) {
        private val loader: (String) -> InputStream = { name ->
            if (name == failOn) throw FileNotFoundException(name)
            when (name) {
                ReadingDictionary.WORDS_ASSET -> ByteArrayInputStream(gzip(words))
                ReadingDictionary.KANJI_ASSET -> ByteArrayInputStream(gzip(kanji))
                else -> throw FileNotFoundException(name)
            }
        }
        val dictionary: ReadingDictionary = ReadingDictionary(RuntimeEnvironment.getApplication())
        suspend fun load() = dictionary.warmUpWith(loader)
        suspend fun loadWith(open: (String) -> InputStream) = dictionary.warmUpWith(open)
    }

    @Test
    fun notReadyBeforeWarmUp() = runBlocking {
        val bed = TestBed("日本\tにほん")
        assertFalse(bed.dictionary.ready)
        assertEquals(0, bed.dictionary.maxSurfaceLength)
        assertNull(bed.dictionary.readingOf("日本"))
    }

    @Test
    fun warmUp_loadsBothAssetsAndBecomesReady() = runBlocking {
        val bed = TestBed("日本\tにほん", "猫\tねこ")
        bed.load()
        assertTrue(bed.dictionary.ready)
        assertEquals("にほん", bed.dictionary.readingOf("日本"))
        assertEquals("ねこ", bed.dictionary.kanjiReadingOf('猫'))
        assertEquals(2, bed.dictionary.maxSurfaceLength)
    }

    @Test
    fun warmUp_isIdempotentAndOnlyTriesOnce() = runBlocking {
        var attempts = 0
        val bed = TestBed("日本\tにほん")
        val failing: (String) -> InputStream = {
            attempts++
            throw FileNotFoundException(it)
        }
        bed.loadWith(failing)
        bed.loadWith(failing)
        assertFalse(bed.dictionary.ready)
        assertEquals("只允许尝试一次资产加载", 1, attempts)
        // 加载失败后 annotateLine 必须仍然安全，且不缓存「不注音」
        assertEquals(listOf(ReadingToken("日本語")), annotateLine("日本語", bed.dictionary))
    }

    @Test
    fun corruptGzipContentMarksUnavailableWithoutThrowing() = runBlocking {
        val bed = TestBed("日本\tにほん")
        bed.loadWith { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) }
        assertFalse(bed.dictionary.ready)
    }

    @Test
    fun emptyWordAssetStillAcceptsKanjiFallback() = runBlocking {
        val bed = TestBed("", "猫\tねこ")
        bed.load()
        assertTrue(bed.dictionary.ready)
        assertNull(bed.dictionary.readingOf("猫"))
        assertEquals("ねこ", bed.dictionary.kanjiReadingOf('猫'))
    }
}
