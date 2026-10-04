package com.asmr.player.domain.model

import androidx.compose.runtime.Immutable

/** 播客节目;id 即 RSS feed URL,作为全局稳定标识。 */
@Immutable
data class PodcastFeed(
    val id: String,
    val title: String,
    val author: String = "",
    val artworkUrl: String = "",
    val genre: String = "",
    val country: String = "",
    val description: String = ""
) {
    companion object {
        const val WORK_ID_PREFIX = "podcast:"

        fun workIdFor(feedUrl: String): String = "$WORK_ID_PREFIX${feedUrl.trim()}"

        fun feedUrlFromWorkId(workId: String): String? {
            return workId.takeIf { it.startsWith(WORK_ID_PREFIX) }?.removePrefix(WORK_ID_PREFIX)
        }
    }
}

/** 播客单集;audioUrl 同时充当 mediaId(在线播放与收藏/播放列表快照一致)。 */
@Immutable
data class PodcastEpisode(
    val podcastId: String,
    val podcastTitle: String = "",
    val title: String,
    val audioUrl: String,
    val pubDateMs: Long = 0L,
    val durationMs: Long = 0L,
    val description: String = "",
    val artworkUrl: String = "",
    /** 已下载剧集的本地文件路径;为空表示走 audioUrl 流媒体。 */
    val localPath: String = ""
)
