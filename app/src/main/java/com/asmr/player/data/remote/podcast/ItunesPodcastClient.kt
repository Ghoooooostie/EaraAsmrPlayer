package com.asmr.player.data.remote.podcast

import com.asmr.player.BuildConfig
import com.asmr.player.data.remote.NetworkHeaders
import com.asmr.player.domain.model.PodcastFeed
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ItunesPodcastResult(
    @SerializedName("trackId") val trackId: Long = 0L,
    @SerializedName("collectionName") val collectionName: String = "",
    @SerializedName("artistName") val artistName: String = "",
    @SerializedName("artworkUrl100") val artworkUrl100: String = "",
    @SerializedName("artworkUrl600") val artworkUrl600: String = "",
    @SerializedName("feedUrl") val feedUrl: String = "",
    @SerializedName("primaryGenreName") val primaryGenreName: String = "",
    @SerializedName("description") val description: String = ""
) {
    val bestArtworkUrl: String
        get() = when {
            artworkUrl600.isNotBlank() -> artworkUrl600
            artworkUrl100.isNotBlank() -> artworkUrl100.upsizeItunesArtwork()
            else -> ""
        }
}

data class ItunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int = 0,
    @SerializedName("results") val results: List<ItunesPodcastResult> = emptyList()
)

data class ItunesChartFeed(
    @SerializedName("feed") val feed: ItunesChartFeedBody = ItunesChartFeedBody()
)

data class ItunesChartFeedBody(
    @SerializedName("entry") val entry: List<ItunesChartEntry> = emptyList()
)

data class ItunesChartEntry(
    @SerializedName("id") val id: ItunesChartId = ItunesChartId(),
    @SerializedName("title") val title: ItunesChartLabel = ItunesChartLabel(),
    @SerializedName("im:artist") val artist: ItunesChartLabel = ItunesChartLabel(),
    @SerializedName("im:image") val images: List<ItunesChartLabel> = emptyList(),
    @SerializedName("category") val category: ItunesChartCategory = ItunesChartCategory(),
    @SerializedName("summary") val summary: ItunesChartLabel = ItunesChartLabel()
)

data class ItunesChartId(
    @SerializedName("attributes") val attributes: ItunesChartAttributes = ItunesChartAttributes()
)

data class ItunesChartLabel(
    @SerializedName("label") val label: String = ""
)

data class ItunesChartAttributes(
    @SerializedName("im:id") val imId: String = ""
)

data class ItunesChartCategory(
    @SerializedName("attributes") val attributes: ItunesChartCategoryAttributes = ItunesChartCategoryAttributes()
)

data class ItunesChartCategoryAttributes(
    @SerializedName("label") val label: String = ""
)

private fun String.upsizeItunesArtwork(): String {
    return replace("100x100bb", "600x600bb")
}

/**
 * iTunes 播客接口客户端:搜索、国家/分类榜单、feedUrl 解析。
 * 详见 https://performance-partners.apple.com/search-api 与 Apple RSS 榜单。
 */
@Singleton
class ItunesPodcastClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private val baseUrl: String = BuildConfig.PODCAST_ITUNES_BASE_URL.trimEnd('/')

    /** 关键词搜索播客;结果自带 feedUrl。 */
    suspend fun searchPodcasts(country: String, term: String, limit: Int = 50): List<PodcastFeed> {
        val normalizedTerm = term.trim()
        if (normalizedTerm.isEmpty()) return emptyList()
        val response = getJson<ItunesSearchResponse>(
            path = "search",
            queryParameters = mapOf(
                "media" to "podcast",
                "entity" to "podcast",
                "country" to country,
                "term" to normalizedTerm,
                "limit" to limit.coerceIn(1, 200).toString()
            )
        ) ?: return emptyList()
        // iTunes 可能对同一 RSS 返回多条结果(不同 trackId),去重避免下游列表出现重复 key。
        return response.results
            .filter { it.feedUrl.isNotBlank() }
            .distinctBy { it.feedUrl.trim() }
            .map { result ->
                PodcastFeed(
                    id = result.feedUrl.trim(),
                    title = result.collectionName,
                    author = result.artistName,
                    artworkUrl = result.bestArtworkUrl,
                    genre = result.primaryGenreName,
                    country = country,
                    description = result.description
                )
            }
    }

    /** 国家/分类榜单。榜单条目只有 iTunes id,需一次批量 lookup 解析 feedUrl。 */
    suspend fun topPodcasts(country: String, genreId: String? = null, limit: Int = 50): List<PodcastFeed> {
        val pathBuilder = StringBuilder("$country/rss/toppodcasts/limit=${limit.coerceIn(1, 200)}")
        if (!genreId.isNullOrBlank()) {
            pathBuilder.append("/genre=$genreId")
        }
        pathBuilder.append("/json")
        val chart = getJson<ItunesChartFeed>(path = pathBuilder.toString()) ?: return emptyList()
        // Apple 榜单 JSON 的条目在 feed.entry 下，不是顶层。
        val entries = chart.feed.entry.filter { it.id.attributes.imId.isNotBlank() }
        if (entries.isEmpty()) return emptyList()

        val feedUrlById = lookupFeedUrls(entries.map { it.id.attributes.imId })
        val feeds = entries.mapNotNull { entry ->
            val feedUrl = feedUrlById[entry.id.attributes.imId] ?: return@mapNotNull null
            PodcastFeed(
                id = feedUrl,
                title = entry.title.label,
                author = entry.artist.label,
                artworkUrl = entry.images.lastOrNull()?.label.orEmpty(),
                genre = entry.category.attributes.label,
                country = country,
                description = entry.summary.label
            )
        }
        // 多个榜单条目可能解析到同一 feedUrl,去重避免下游列表出现重复 key。
        return feeds.distinctBy { it.id }
    }

    /** 按 iTunes id 解析 feedUrl;一次请求最多 200 个 id,返回按 id 索引的映射。 */
    suspend fun lookupFeedUrls(itunesIds: List<String>): Map<String, String> {
        if (itunesIds.isEmpty()) return emptyMap()
        val chunks = itunesIds.distinct().chunked(200)
        val result = LinkedHashMap<String, String>()
        for (chunk in chunks) {
            val response = getJson<ItunesSearchResponse>(
                path = "lookup",
                queryParameters = mapOf(
                    "entity" to "podcast",
                    "id" to chunk.joinToString(",")
                )
            ) ?: continue
            response.results.forEach { item ->
                if (item.trackId != 0L && item.feedUrl.isNotBlank()) {
                    result[item.trackId.toString()] = item.feedUrl.trim()
                }
            }
        }
        return result
    }

    /** 单个 id 解析,供从收藏/深链等只有 id 的场景使用。 */
    suspend fun lookupPodcast(itunesId: String): PodcastFeed? {
        val feedUrls = lookupFeedUrls(listOf(itunesId))
        val feedUrl = feedUrls[itunesId] ?: return null
        val response = getJson<ItunesSearchResponse>(
            path = "lookup",
            queryParameters = mapOf("id" to itunesId)
        ) ?: return null
        val item = response.results.firstOrNull() ?: return null
        return PodcastFeed(
            id = feedUrl,
            title = item.collectionName,
            author = item.artistName,
            artworkUrl = item.bestArtworkUrl,
            genre = item.primaryGenreName,
            description = item.description
        )
    }

    private suspend inline fun <reified T> getJson(
        path: String,
        queryParameters: Map<String, String> = emptyMap()
    ): T? {
        return withContext(Dispatchers.IO) {
            val url = "$baseUrl/$path".toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply {
                    queryParameters.forEach { (key, value) ->
                        addQueryParameter(key, value)
                    }
                }
                ?.build()
                ?: throw IOException("Invalid iTunes podcast URL: $path")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkHeaders.USER_AGENT)
                .header(NetworkHeaders.HEADER_SILENT_IO_ERROR, NetworkHeaders.SILENT_IO_ERROR_ON)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("iTunes podcast request failed: ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return@withContext null
                gson.fromJson(raw, T::class.java)
            }
        }
    }
}
