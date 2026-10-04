package com.asmr.player.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 播客订阅;feedUrl 为主键,与 PodcastFeed.id 一致。 */
@Entity(tableName = "podcast_subscriptions")
data class PodcastSubscriptionEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val author: String = "",
    val artworkUrl: String = "",
    val country: String = "",
    val genre: String = "",
    val itunesId: String = "",
    val subscribedAtMs: Long = System.currentTimeMillis()
)
