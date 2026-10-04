package com.asmr.player.ui.podcast

import com.asmr.player.domain.model.PodcastFeed

/**
 * 播客详情页的内存载荷:详情路由不携带 URL 参数,导航前 set,目标页读取。
 * 保留最后一次值以支持旋转重建;再次导航总会覆盖。
 */
object PodcastNavPayload {
    @Volatile
    private var current: PodcastFeed? = null

    fun set(feed: PodcastFeed) {
        current = feed
    }

    fun peek(): PodcastFeed? = current
}
