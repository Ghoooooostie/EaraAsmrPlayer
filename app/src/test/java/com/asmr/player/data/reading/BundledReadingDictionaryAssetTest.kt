package com.asmr.player.data.reading

import com.asmr.player.util.annotateLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 校验已提交的注音资产确实读得出来、且常用词读对了。
 * 资产不存在时跳过而不是失败：Task 11 之前跑其它测试属于正常状态。
 */
@RunWith(RobolectricTestRunner::class)
class BundledReadingDictionaryAssetTest {

    @Test
    fun bundledDictionaryReadsCommonWordsCorrectly() = runBlocking {
        val names = RuntimeEnvironment.getApplication().assets.list("reading").orEmpty()
        // aapt2 打包时会剥掉 .gz 后缀，两种拼法都认；真的没有词典资产才跳过。
        if (names.none { it == "words" || it == "words.gz" }) return@runBlocking

        val dictionary = ReadingDictionary(RuntimeEnvironment.getApplication())
        dictionary.warmUp()
        assertTrue("资产存在但没有就绪", dictionary.ready)

        val probes = mapOf(
            "日本語" to "にほんご",
            "美味しい" to "おいしい",
            "昨日" to "きのう"
        )
        for ((surface, reading) in probes) {
            val annotated = annotateLine(surface, dictionary).joinToString("") { token ->
                token.base + (token.reading ?: "")
            }
            assertTrue("$surface 应含 $reading", annotated.contains(reading))
        }
    }
}
