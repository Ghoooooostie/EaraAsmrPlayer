package com.asmr.player.util

/**
 * OtomeKoe（otomekoe.moe）相关的固定资源地址模式与判断。
 *
 * 站点是 WordPress 博客，每篇帖子对应一部 DLsite 作品（标题含 RJ 码），
 * 音频为单条 HLS 流，地址与封面都遵循可由 RJ 码直接推导的固定模式：
 * - 音频流：https://v.weeab0o.xyz/{RJ}.m3u8
 * - 封面：  https://pic.weeabo0.xyz/{RJ}_img_main.jpg
 * 流与封面均有防盗链，需携带 Referer: https://otomekoe.moe/。
 */
object OtomeKoeMedia {
    const val SITE_HOST = "otomekoe.moe"
    const val SITE_BASE_URL = "https://otomekoe.moe/"
    const val STREAM_HOST = "v.weeab0o.xyz"
    const val COVER_HOST = "pic.weeabo0.xyz"
    const val IMAGE_HOST = "img.weeabo0.xyz"

    fun normalizeRj(rj: String): String = rj.trim().uppercase()

    fun streamUrlFor(rj: String): String = "https://$STREAM_HOST/${normalizeRj(rj)}.m3u8"

    fun coverUrlFor(rj: String): String = "https://$COVER_HOST/${normalizeRj(rj)}_img_main.jpg"

    fun isOtomeKoeStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.contains(".m3u8", ignoreCase = true)) return false
        return trimmed.contains(STREAM_HOST, ignoreCase = true)
    }

    /**
     * 需要携带 OtomeKoe Referer 的媒体主机（音频流、封面、图片）。
     * CDN 备源（bxcdn/bkcdn）无防盗链，不在其列。
     */
    fun isRefererRequiredHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return normalized == STREAM_HOST ||
            normalized == COVER_HOST ||
            normalized == IMAGE_HOST ||
            normalized.endsWith(".$STREAM_HOST") ||
            normalized.endsWith(".$COVER_HOST") ||
            normalized.endsWith(".$IMAGE_HOST")
    }
}
