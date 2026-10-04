package com.asmr.player.data.repository

import com.asmr.player.data.local.db.dao.PodcastSubscriptionDao
import com.asmr.player.data.local.db.entities.PodcastSubscriptionEntity
import com.asmr.player.domain.model.PodcastFeed
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastSubscriptionRepository @Inject constructor(
    private val subscriptionDao: PodcastSubscriptionDao
) {
    fun observeAll(): Flow<List<PodcastSubscriptionEntity>> = subscriptionDao.observeAll()

    fun observeIsSubscribed(feedUrl: String): Flow<Boolean> = subscriptionDao.observeIsSubscribed(feedUrl)

    suspend fun getByFeedUrlOnce(feedUrl: String): PodcastSubscriptionEntity? =
        subscriptionDao.getByFeedUrlOnce(feedUrl)

    suspend fun subscribe(feed: PodcastFeed) {
        subscriptionDao.upsert(
            PodcastSubscriptionEntity(
                feedUrl = feed.id,
                title = feed.title,
                author = feed.author,
                artworkUrl = feed.artworkUrl,
                country = feed.country,
                genre = feed.genre,
                subscribedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun unsubscribe(feedUrl: String) {
        subscriptionDao.deleteByFeedUrl(feedUrl.trim())
    }

    suspend fun toggle(feed: PodcastFeed): Boolean {
        val subscribed = subscriptionDao.getByFeedUrlOnce(feed.id) != null
        if (subscribed) {
            unsubscribe(feed.id)
        } else {
            subscribe(feed)
        }
        return !subscribed
    }
}
