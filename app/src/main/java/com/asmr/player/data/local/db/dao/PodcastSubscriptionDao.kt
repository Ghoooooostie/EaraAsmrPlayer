package com.asmr.player.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.asmr.player.data.local.db.entities.PodcastSubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastSubscriptionDao {
    @Query("SELECT * FROM podcast_subscriptions ORDER BY subscribedAtMs DESC")
    fun observeAll(): Flow<List<PodcastSubscriptionEntity>>

    @Query("SELECT * FROM podcast_subscriptions ORDER BY subscribedAtMs DESC")
    suspend fun getAllOnce(): List<PodcastSubscriptionEntity>

    @Query("SELECT * FROM podcast_subscriptions WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getByFeedUrlOnce(feedUrl: String): PodcastSubscriptionEntity?

    @Query("SELECT COUNT(*) > 0 FROM podcast_subscriptions WHERE feedUrl = :feedUrl")
    fun observeIsSubscribed(feedUrl: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: PodcastSubscriptionEntity)

    @Query("DELETE FROM podcast_subscriptions WHERE feedUrl = :feedUrl")
    suspend fun deleteByFeedUrl(feedUrl: String)
}
