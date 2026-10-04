package com.asmr.player.util

/**
 * 日文中汉字注音（行内小字假名）的切分算法。
 *
 * 本文件刻意不依赖 Android 与 Compose，只处理「给定文本 + 词典，怎么切」；
 * 是否该给这段文本注音由调用方通过语言分段决定，所以中文传进来一样会注音。
 */

/** 一个注音单元：[base] 为基字文本，[reading] 为需要小字显示的假名，null 表示不注音。 */
data class ReadingToken(
    val base: String,
    val reading: String? = null
)

/** 注音相对基字字号的比例，让注音跟随用户可调的歌词字号。 */
const val FURIGANA_READING_SCALE = 0.55f

/**
 * 读音查询能力。由 data 层的词典实现，算法层据此保持可在 JVM 上测试。
 * [ready] 为 false 时查询结果不可信，调用方需要按「不注音」处理。
 */
interface ReadingSource {
    val ready: Boolean

    /** 词典里最长的表层字数，用于限制匹配窗口。未就绪时为 0。 */
    val maxSurfaceLength: Int

    /** 词级读音查询；未收录返回 null。 */
    fun readingOf(surface: String): String?

    /** 单字回退读音；未收录返回 null。 */
    fun kanjiReadingOf(kanji: Char): String?
}

/** 汉字：CJK 统一表意文字、扩展 A、兼容汉字（按码位判定，避免依赖源文件编码）。 */
internal fun isKanji(ch: Char): Boolean =
    ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF

/** 假名：平假名、片假名（不含半角假名）与长音符「ー」。 */
internal fun isKana(ch: Char): Boolean =
    ch.code in 0x3041..0x309F || ch.code in 0x30A1..0x30FA || ch.code == 0x30FC

/** 括号形态与右括号的对应关系；用于吸收源文本里自带的注音。 */
private val rubyBrackets = mapOf('（' to '）', '(' to ')', '［' to '］', '[' to ']')

/** 送假名最多向后扩展几个假名，超过这个宽度就不再尝试词级匹配。 */
private const val MAX_OKURIGANA_LENGTH = 4

/**
 * 把文本切成注音单元。[source] 为 null 或未就绪时整段原样返回（只有一个 token）。
 */
fun annotateJapanese(text: String, source: ReadingSource?): List<ReadingToken> {
    if (text.isEmpty()) return emptyList()
    if (source == null || !source.ready) return listOf(ReadingToken(text))

    val tokens = mutableListOf<ReadingToken>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            tokens += ReadingToken(plain.toString())
            plain.setLength(0)
        }
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (!isKanji(ch)) {
            plain.append(ch)
            i++
            continue
        }
        var runEnd = i
        while (runEnd < text.length && isKanji(text[runEnd])) runEnd++

        val kanjiPart = text.substring(i, runEnd)
        val absorbed = absorbedReadingAt(text, runEnd)
        if (absorbed != null) {
            flushPlain()
            tokens += ReadingToken(kanjiPart, absorbed.first)
            i = absorbed.second
            continue
        }

        val match = bestSurfaceMatch(text, i, runEnd, source)
        if (match != null) {
            flushPlain()
            val okurigana = text.substring(i + match.kanjiLength, i + match.surfaceLength)
            tokens += ReadingToken(
                kanjiPart.take(match.kanjiLength),
                trimReading(match.reading, okurigana)
            )
            // 只跨过汉字部分，送假名留给下一轮原样输出，避免被二次匹配。
            i += match.kanjiLength
            continue
        }

        val singleReading = source.kanjiReadingOf(ch)
        if (singleReading != null) {
            flushPlain()
            tokens += ReadingToken(ch.toString(), singleReading)
        } else {
            plain.append(ch)
        }
        i++
    }
    flushPlain()
    return tokens
}

/** 返回「汉字段后面紧跟（假名）」的读音与其右括号之后的位置，否则 null。 */
private fun absorbedReadingAt(text: String, kanjiRunEnd: Int): Pair<String, Int>? {
    val open = text.getOrNull(kanjiRunEnd) ?: return null
    val close = rubyBrackets[open] ?: return null
    val contentStart = kanjiRunEnd + 1
    val closeIndex = text.indexOf(close, contentStart)
    if (closeIndex < 0) return null
    val content = text.substring(contentStart, closeIndex)
    if (content.isEmpty() || !content.all(::isKana)) return null
    return content to (closeIndex + 1)
}

private class SurfaceMatch(val kanjiLength: Int, val surfaceLength: Int, val reading: String)

/**
 * 最大正向匹配：先保证覆盖更多汉字，再在汉字段后扩展连续送假名。
 * 「单汉字且无送假名」的组合不在这里查（`continue` 跳过），交给
 * [ReadingSource.kanjiReadingOf] 兜底——单字表也从词库派生，两条路给出的是同一个读音，
 * 少一次二分查找。
 */
private fun bestSurfaceMatch(
    text: String,
    start: Int,
    runEnd: Int,
    source: ReadingSource
): SurfaceMatch? {
    val maxKanji = minOf(source.maxSurfaceLength, runEnd - start)
    // 送假名窗口只数「紧跟汉字段的连续假名」，否则窗口会伸进标点与空格，
    // 拼出一个永不在词典里的表层字（还白做几次查询）。
    var trailingKana = 0
    while (trailingKana < MAX_OKURIGANA_LENGTH &&
        runEnd + trailingKana < text.length &&
        isKana(text[runEnd + trailingKana])
    ) trailingKana++
    for (kanjiLength in maxKanji downTo 1) {
        for (kanaLength in trailingKana downTo 0) {
            if (kanjiLength == 1 && kanaLength == 0) continue
            val surface = text.substring(start, start + kanjiLength + kanaLength)
            val reading = source.readingOf(surface) ?: continue
            return SurfaceMatch(kanjiLength, kanjiLength + kanaLength, reading)
        }
    }
    return null
}

/** 送假名已在基字里显示，就从读音尾部去掉，得到只属于汉字的注音。 */
private fun trimReading(reading: String, okurigana: String): String =
    if (okurigana.isNotEmpty() &&
        reading.length > okurigana.length &&
        reading.endsWith(okurigana)
    ) reading.dropLast(okurigana.length) else reading

private const val annotateCacheLimit = 2000

/**
 * 行级缓存。歌词滚动与悬浮歌词换句都会重复处理同一行，缓存让注音开销接近零。
 * 词典未就绪时的结果一律不写入，好让预热完成后下一次渲染自动带上注音，
 * 不需要「就绪」通知机制。
 */
private val annotateCache = object : LinkedHashMap<String, List<ReadingToken>>(64, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReadingToken>>?): Boolean =
        size > annotateCacheLimit
}

fun annotateLine(text: String, source: ReadingSource?): List<ReadingToken> {
    val cacheable = source?.ready == true
    if (!cacheable) return annotateJapanese(text, source)
    val cached = synchronized(annotateCache) { annotateCache[text] }
    if (cached != null) return cached
    val result = annotateJapanese(text, source)
    synchronized(annotateCache) { annotateCache[text] = result }
    return result
}

/** 测试与词典热替换用；生产路径不调用。 */
internal fun clearAnnotateCache() = synchronized(annotateCache) { annotateCache.clear() }
