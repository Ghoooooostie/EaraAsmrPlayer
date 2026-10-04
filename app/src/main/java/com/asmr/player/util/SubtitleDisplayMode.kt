package com.asmr.player.util

/**
 * 字幕显示模式：决定播放页、歌词页、悬浮歌词与导出文件使用哪种文本。
 *
 * - [CHINESE]：只显示中文译文（默认，与旧版本一致）。
 * - [JAPANESE]：只显示日文原文；自动生成字幕无原文时回退到中文。
 * - [BILINGUAL]：中英日两行同时显示，先后顺序由 [SubtitleBilingualOrder] 决定。
 */
enum class SubtitleDisplayMode(val storageValue: String) {
    CHINESE("zh"),
    JAPANESE("ja"),
    BILINGUAL("bilingual");

    companion object {
        fun fromStorageValue(value: String?): SubtitleDisplayMode {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue == normalized } ?: CHINESE
        }
    }
}

/** 双语模式下两行的先后顺序。 */
enum class SubtitleBilingualOrder(val storageValue: String) {
    JAPANESE_FIRST("ja_first"),
    CHINESE_FIRST("zh_first");

    companion object {
        fun fromStorageValue(value: String?): SubtitleBilingualOrder {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue == normalized } ?: JAPANESE_FIRST
        }
    }
}

/** 偏好存储的 key，供设置读写与导出流程共用。 */
internal const val SUBTITLE_DISPLAY_MODE_PREF_KEY = "subtitle_display_mode"
internal const val SUBTITLE_BILINGUAL_ORDER_PREF_KEY = "subtitle_bilingual_order"

/** 一行字幕里的一段可独立处理的文本；[japanese] 为 true 才允许进注音词典。 */
data class DisplaySegment(val text: String, val japanese: Boolean)

/**
 * 空分段（中文模式、外挂字幕、未走 withDisplayMode 的原始列表）时退回一个不注音的纯文本段。
 * 所有渲染/测量点都必须经过它，否则这些模式下整行会画成空白。
 */
fun List<DisplaySegment>.orPlainFallback(plain: String): List<DisplaySegment> =
    if (isNotEmpty()) this else listOf(DisplaySegment(plain, japanese = false))

/** 按显示模式取单条字幕的展示文本。 */
fun SubtitleEntry.displayText(
    mode: SubtitleDisplayMode,
    bilingualOrder: SubtitleBilingualOrder
): String {
    val chinese = text.trim()
    val japanese = japaneseText.trim()
    return when (mode) {
        SubtitleDisplayMode.CHINESE -> chinese
        SubtitleDisplayMode.JAPANESE -> japanese.ifBlank { chinese }
        SubtitleDisplayMode.BILINGUAL -> when {
            japanese.isBlank() -> chinese
            chinese.isBlank() -> japanese
            japanese == chinese -> chinese
            bilingualOrder == SubtitleBilingualOrder.JAPANESE_FIRST -> "$japanese\n$chinese"
            else -> "$chinese\n$japanese"
        }
    }
}

/**
 * 文本是否含假名。中文句子几乎不含假名，日文句子基本都含：
 * 用来在「日文原文就存在 text 字段、japaneseText 为空」时把主字段兜底识别成日文，
 * 否则这类字幕会被误当中文而挡在词典外。纯汉字的中日文无法区分，按「不是日文」处理（偏保守）。
 */
private fun String.looksJapanese(): Boolean =
    any { it in '぀'..'ゟ' || it in '゠'..'ヿ' }

/** 按显示模式与双语顺序把单条字幕拆成带语言标记的分段。 */
fun SubtitleEntry.displaySegmentsFor(
    mode: SubtitleDisplayMode,
    order: SubtitleBilingualOrder
): List<DisplaySegment> {
    val chinese = text.trim()
    val japanese = japaneseText.trim()
    val bilingual = listOf(
        DisplaySegment(chinese, japanese = false),
        DisplaySegment(japanese, japanese = true)
    )
    return when (mode) {
        SubtitleDisplayMode.CHINESE -> emptyList()
        SubtitleDisplayMode.JAPANESE -> when {
            japanese.isBlank() -> listOf(DisplaySegment(chinese, japanese = chinese.looksJapanese()))
            else -> listOf(DisplaySegment(japanese, japanese = true))
        }
        SubtitleDisplayMode.BILINGUAL -> when {
            japanese.isBlank() -> listOf(DisplaySegment(chinese, japanese = chinese.looksJapanese()))
            chinese.isBlank() -> listOf(DisplaySegment(japanese, japanese = true))
            japanese == chinese -> listOf(DisplaySegment(chinese, japanese = chinese.looksJapanese()))
            else -> if (order == SubtitleBilingualOrder.JAPANESE_FIRST) bilingual.reversed() else bilingual
        }
    }
}

/** 把整轨字幕的展示文本替换为指定模式与顺序下的文本，并填好语言分段供注音使用；时间轴与顺序保持不变。 */
fun List<SubtitleEntry>.withDisplayMode(
    mode: SubtitleDisplayMode,
    order: SubtitleBilingualOrder
): List<SubtitleEntry> {
    if (isEmpty()) return this
    if (mode == SubtitleDisplayMode.CHINESE) {
        // 中文模式不需要分段，但也不能残留上一次模式的分段。
        return map { if (it.displaySegments.isEmpty()) it else it.copy(displaySegments = emptyList()) }
    }
    return map { entry ->
        val segments = entry.displaySegmentsFor(mode, order)
        entry.copy(text = segments.joinToString("\n") { it.text }, displaySegments = segments)
    }
}

/**
 * 注音渲染所需的全部输入。[source] 为 null 或词典未就绪时等价于不注音。
 * 打包成一个值对象是为了避免把「开关 + 词典」逐层透传到五个渲染点。
 */
data class FuriganaSpec(
    val enabled: Boolean,
    val source: ReadingSource?
) {
    companion object {
        val NONE = FuriganaSpec(enabled = false, source = null)
    }
}
