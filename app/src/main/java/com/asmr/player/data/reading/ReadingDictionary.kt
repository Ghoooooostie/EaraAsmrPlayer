package com.asmr.player.data.reading

import android.content.Context
import android.util.Log
import com.asmr.player.util.ReadingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读音词典的进程级单例：从自带 gzip 资产加载 [ReadingDictionaryIndex]。
 *
 * 只在注音开关打开后由 [warmUp] 加载一次。任何失败（资产缺失、解压失败、内容非法）
 * 都被吞掉并标记不可用，调用方按「不注音」处理——绝不让歌词界面因为词典而崩。
 */
@Singleton
class ReadingDictionary @Inject constructor(
    @ApplicationContext private val context: Context
) : ReadingSource {

    @Volatile private var index: ReadingDictionaryIndex? = null
    @Volatile private var attempted = false
    private val mutex = Mutex()

    override val ready: Boolean get() = index?.ready == true

    override val maxSurfaceLength: Int get() = index?.maxSurfaceLength ?: 0

    override fun readingOf(surface: String): String? = index?.readingOf(surface)

    override fun kanjiReadingOf(kanji: Char): String? = index?.kanjiReadingOf(kanji)

    /** 生产入口：读取 assets/reading/ 下的 gzip 词库。 */
    suspend fun warmUp() = warmUpWith { context.assets.open(it) }

    /**
     * 幂等的加载入口，[open] 是测试缝口（Dagger 只认单参数的 @Inject 构造器，
     * 所以假加载器只能走方法参数）。无论成功失败，一个实例只尝试一次，
     * 避免失败后每次渲染都重试。
     */
    internal suspend fun warmUpWith(open: (String) -> InputStream) {
        if (attempted) return
        mutex.withLock {
            if (attempted) return
            attempted = true
            index = runCatching { loadFrom(open) }
                .getOrElse {
                    Log.i(TAG, "furigana dictionary unavailable", it)
                    null
                }
        }
    }

    private suspend fun loadFrom(open: (String) -> InputStream): ReadingDictionaryIndex =
        withContext(Dispatchers.Default) {
            val words = readLines(WORDS_ASSET, open)
            val kanji = try {
                readLines(KANJI_ASSET, open)
            } catch (_: FileNotFoundException) {
                emptyList()
            }
            val parsed = ReadingDictionaryIndex.parse(words, kanji)
            check(parsed.ready) { "furigana dictionary is empty" }
            parsed
        }

    private fun readLines(asset: String, open: (String) -> InputStream): List<String> =
        open(asset).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines()
            }
        }

    companion object {
        private const val TAG = "ReadingDictionary"
        const val WORDS_ASSET = "reading/words.gz"
        const val KANJI_ASSET = "reading/kanji.gz"
    }
}
