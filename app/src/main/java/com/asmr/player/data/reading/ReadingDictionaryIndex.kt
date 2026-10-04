package com.asmr.player.data.reading

import com.asmr.player.util.ReadingSource

/**
 * 只读读音索引：排序数组 + 二分查找。
 *
 * 用两个并行数组而不是 HashMap，是为了省掉哈希桶开销——手机上常驻内存预算 20 MB。
 * 算法层按窗口长度递减逐个精确查询，所以这里只需要精确匹配。
 */
class ReadingDictionaryIndex private constructor(
    private val surfaces: Array<String>,
    private val readings: Array<String>,
    private val kanjiChars: CharArray,
    private val kanjiReadings: Array<String>
) : ReadingSource {

    override val ready: Boolean get() = surfaces.isNotEmpty() || kanjiChars.isNotEmpty()

    override val maxSurfaceLength: Int = surfaces.maxOfOrNull { it.length } ?: 0

    /** 词条数，供资产体积校验使用。 */
    val surfaceCount: Int get() = surfaces.size

    override fun readingOf(surface: String): String? {
        if (surface.isEmpty()) return null
        val at = surfaces.binarySearchSurface(surface)
        return if (at >= 0) readings[at] else null
    }

    override fun kanjiReadingOf(kanji: Char): String? {
        val at = kanjiChars.binarySearch(kanji)
        return if (at >= 0) kanjiReadings[at] else null
    }

    private fun Array<String>.binarySearchSurface(key: String): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                this[mid] < key -> low = mid + 1
                this[mid] > key -> high = mid - 1
                // 重复表层（同表层多读音）时线性回退到第一条；
                // parse 用的是稳定排序，先出现的词条因此排在前面。
                else -> {
                    var first = mid
                    while (first > 0 && this[first - 1] == key) first--
                    return first
                }
            }
        }
        return -1
    }

    companion object {
        /**
         * 从 `表层\t读音` 行构建索引。非法行（无制表符、读音为空）直接丢弃。
         * [kanjiLines] 为同样的行格式，键是单个汉字。
         */
        fun parse(wordLines: List<String>, kanjiLines: List<String>): ReadingDictionaryIndex {
            val parsedWords = wordLines.mapNotNull { parseEntry(it) }
            val sortedWords = parsedWords.sortedBy { it.first }
            val parsedKanji = kanjiLines.mapNotNull { parseEntry(it) }
                .filter { it.first.length == 1 }
                .sortedBy { it.first[0] }
            return ReadingDictionaryIndex(
                surfaces = sortedWords.map { it.first }.toTypedArray(),
                readings = sortedWords.map { it.second }.toTypedArray(),
                kanjiChars = parsedKanji.map { it.first[0] }.toCharArray(),
                kanjiReadings = parsedKanji.map { it.second }.toTypedArray()
            )
        }

        private fun parseEntry(line: String): Pair<String, String>? {
            val tab = line.indexOf('\t')
            if (tab <= 0) return null
            val surface = line.substring(0, tab)
            val reading = line.substring(tab + 1).trim()
            if (surface.isBlank() || reading.isEmpty()) return null
            return surface to reading
        }

        /**
         * 由词级词条派生单字回退表。
         *
         * 只用「恰好含一个汉字」的词条：此时把送假名从读音尾部去掉，剩下的就是这个汉字的读音。
         * 多汉字词条无法把读音可靠对齐到单个汉字，所以一律不参与派生，宁缺勿错。
         */
        fun deriveKanjiLines(wordLines: List<String>): List<String> {
            val counter = HashMap<String, HashMap<String, Int>>()
            for ((surface, reading) in wordLines.mapNotNull { parseEntry(it) }) {
                val kanjis = surface.filter(::isKanjiChar)
                if (kanjis.length != 1) continue
                val okurigana = surface.substringAfter(kanjis[0])
                val kanjiReading = if (okurigana.isNotEmpty() &&
                    reading.length > okurigana.length &&
                    reading.endsWith(okurigana)
                ) reading.dropLast(okurigana.length) else reading
                if (kanjiReading.isEmpty()) continue
                counter.getOrPut(kanjis) { HashMap() }.merge(kanjiReading, 1) { a, b -> a + b }
            }
            return counter.entries
                .map { (kanji, readings) -> "$kanji\t${mostFrequentReading(readings)}" }
                .sortedBy { it[0] }
        }

        /** 次数多的优先；次数相同取字典序小的，保证同一份词库生成出的资产可复现。 */
        private fun mostFrequentReading(readings: Map<String, Int>): String {
            var bestKey = ""
            var bestCount = -1
            for ((key, count) in readings) {
                if (count > bestCount || (count == bestCount && key < bestKey)) {
                    bestKey = key
                    bestCount = count
                }
            }
            return bestKey
        }

        /** 与算法层同一判定，但本文件在 data 层，拿不到 util 的 internal 函数，所以自行实现。 */
        private fun isKanjiChar(ch: Char): Boolean =
            ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF
    }
}
