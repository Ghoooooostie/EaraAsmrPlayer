package com.asmr.player.subtitle

internal object SubtitleGenerationPolicy {
    // 与下载入库时识别的音频扩展名、LocalAudioDecoder(MediaExtractor+MediaCodec) 可解码范围保持一致
    private val supportedAudioExtensions = setOf(
        "mp3", "flac", "wav", "m4a", "m4b", "ogg", "oga", "aac", "opus"
    )

    fun supportsFileName(fileName: String): Boolean {
        return fileName.substringAfterLast('.', "").lowercase() in supportedAudioExtensions
    }
}
