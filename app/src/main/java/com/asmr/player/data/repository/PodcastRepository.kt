package com.asmr.player.data.repository

import com.asmr.player.data.remote.podcast.ItunesPodcastClient
import com.asmr.player.data.remote.podcast.PodcastFeedParser
import com.asmr.player.domain.model.PodcastEpisode
import com.asmr.player.domain.model.PodcastFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播客内容仓库:榜单/搜索/剧集,带 TTL 内存缓存与单飞合并。
 * 数据源:iTunes Search API + Apple RSS 榜单 + 节目 RSS。
 */
@Singleton
class PodcastRepository @Inject constructor(
    private val itunesClient: ItunesPodcastClient,
    private val feedParser: PodcastFeedParser
) {
    private data class CacheEntry<T>(
        val value: T,
        val createdAtMs: Long
    )

    private val mutex = Mutex()
    private val topCache = LinkedHashMap<String, CacheEntry<List<PodcastFeed>>>()
    private val searchCache = LinkedHashMap<String, CacheEntry<List<PodcastFeed>>>()
    private val feedCache = LinkedHashMap<String, CacheEntry<PodcastFeedParser.ParsedFeed>>()
    private val inFlightFeeds = HashMap<String, CompletableDeferred<PodcastFeedParser.ParsedFeed>>()

    suspend fun topPodcasts(country: String, genreId: String? = null): List<PodcastFeed> {
        val cacheKey = "$country|${genreId.orEmpty()}"
        mutex.withLock {
            topCache[cacheKey]?.takeIf { isFresh(it, TOP_TTL_MS) }?.let { return it.value }
        }
        val fresh = itunesClient.topPodcasts(country = country, genreId = genreId)
        mutex.withLock {
            topCache[cacheKey] = CacheEntry(fresh, System.currentTimeMillis())
            trimCache(topCache)
        }
        return fresh
    }

    suspend fun searchPodcasts(country: String, term: String): List<PodcastFeed> {
        val cacheKey = "$country|${term.trim()}"
        mutex.withLock {
            searchCache[cacheKey]?.takeIf { isFresh(it, SEARCH_TTL_MS) }?.let { return it.value }
        }
        val fresh = itunesClient.searchPodcasts(country = country, term = term)
        mutex.withLock {
            searchCache[cacheKey] = CacheEntry(fresh, System.currentTimeMillis())
            trimCache(searchCache)
        }
        return fresh
    }

    /** 拉取节目剧集;相同 feedUrl 的并发请求合并为一次网络访问。 */
    suspend fun episodes(feedUrl: String): List<PodcastEpisode> {
        return parsedFeed(feedUrl).episodes
    }

    /** 频道信息来自同一份 RSS 解析结果;未缓存时同样触发一次抓取。 */
    suspend fun channel(feedUrl: String): PodcastFeed? {
        return parsedFeed(feedUrl).channel
    }

    private suspend fun parsedFeed(feedUrl: String): PodcastFeedParser.ParsedFeed {
        val key = feedUrl.trim()
        mutex.withLock {
            feedCache[key]?.takeIf { isFresh(it, FEED_TTL_MS) }?.let { return it.value }
        }
        val deferred = CompletableDeferred<PodcastFeedParser.ParsedFeed>()
        mutex.withLock {
            feedCache[key]?.takeIf { isFresh(it, FEED_TTL_MS) }?.let { return it.value }
            inFlightFeeds[key]?.let { return it.await() }
            inFlightFeeds[key] = deferred
        }
        try {
            val parsed = feedParser.fetchAndParse(key)
            mutex.withLock {
                feedCache[key] = CacheEntry(parsed, System.currentTimeMillis())
                trimCache(feedCache)
            }
            deferred.complete(parsed)
            return parsed
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { inFlightFeeds.remove(key) }
        }
    }

    private fun isFresh(entry: CacheEntry<*>, ttlMs: Long): Boolean {
        return System.currentTimeMillis() - entry.createdAtMs < ttlMs
    }

    private fun <T> trimCache(cache: LinkedHashMap<String, CacheEntry<T>>) {
        while (cache.size > MAX_CACHE_ENTRIES) {
            val eldestKey = cache.keys.firstOrNull() ?: break
            cache.remove(eldestKey)
        }
    }

    companion object {
        private const val TOP_TTL_MS = 30 * 60 * 1000L
        private const val SEARCH_TTL_MS = 10 * 60 * 1000L
        private const val FEED_TTL_MS = 15 * 60 * 1000L
        private const val MAX_CACHE_ENTRIES = 32
    }
}
