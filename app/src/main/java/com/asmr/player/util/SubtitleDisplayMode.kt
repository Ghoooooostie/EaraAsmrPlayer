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

/** 把整轨字幕的展示文本替换为指定模式下的文本，时间轴与顺序保持不变。 */
fun List<SubtitleEntry>.withDisplayMode(
    mode: SubtitleDisplayMode,
    bilingualOrder: SubtitleBilingualOrder
): List<SubtitleEntry> {
    if (mode == SubtitleDisplayMode.CHINESE || isEmpty()) return this
    return map { entry -> entry.copy(text = entry.displayText(mode, bilingualOrder)) }
}
