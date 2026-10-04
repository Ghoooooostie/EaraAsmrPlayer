package com.asmr.player.data.remote.podcast

import com.asmr.player.data.remote.NetworkHeaders
import com.asmr.player.domain.model.PodcastEpisode
import com.asmr.player.domain.model.PodcastFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

internal object PodcastRssDateSupport {
    private val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss"
    )

    fun parse(pubDate: String): Long {
        val normalized = pubDate.trim()
        if (normalized.isEmpty()) return 0L
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = format.parse(normalized) ?: continue
                return parsed.time
            } catch (_: Exception) {
                // 尝试下一种格式
            }
        }
        return 0L
    }
}

internal object PodcastDurationSupport {
    /** itunes:duration 可能是秒数(3600)或 HH:MM:SS / MM:SS。 */
    fun parseMs(raw: String): Long {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return 0L
        val seconds = normalized.toLongOrNull()
        if (seconds != null) return seconds * 1000L
        val parts = normalized.split(":").map { it.trim().toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            else -> 0L
        }
    }
}

/**
 * 播客 RSS 解析:抓取 feedUrl 并解析频道信息与剧集列表(enclosure 音频)。
 */
@Singleton
class PodcastFeedParser @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    data class ParsedFeed(
        val channel: PodcastFeed,
        val episodes: List<PodcastEpisode>
    )

    suspend fun fetchAndParse(feedUrl: String): ParsedFeed {
        return withContext(Dispatchers.IO) {
            val url = feedUrl.trim().toHttpUrlOrNull() ?: throw IOException("Invalid podcast feed URL")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkHeaders.USER_AGENT)
                .header(NetworkHeaders.HEADER_SILENT_IO_ERROR, NetworkHeaders.SILENT_IO_ERROR_ON)
                .get()
                .build()

            val xml = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Podcast feed request failed: ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            parse(feedUrl, xml)
        }
    }

    fun parse(feedUrl: String, xml: String): ParsedFeed {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val channel = document.selectFirst("channel")
            ?: throw IOException("Podcast feed has no channel element")

        val channelTitle = channel.selectFirst("title")?.text().orEmpty().trim()
        val channelAuthor = (
            channel.selectFirst("itunes|author")?.text()
                ?: channel.selectFirst("author")?.text()
            ).orEmpty().trim()
        val channelDescription = (
            channel.selectFirst("description")?.text()
                ?: channel.selectFirst("itunes|summary")?.text()
            ).orEmpty().trim()
        val channelArtwork = (
            channel.selectFirst("itunes|image")?.attr("href")
                ?: channel.selectFirst("image > url")?.text()
            ).orEmpty().trim()

        // 同一音频地址可能出现多次,去重避免详情页列表以 audioUrl 为 key 时重复。
        val episodes = channel.select("item")
            .mapNotNull { item -> parseEpisode(feedUrl, channelTitle, channelArtwork, item) }
            .distinctBy { it.audioUrl }
        return ParsedFeed(
            channel = PodcastFeed(
                id = feedUrl.trim(),
                title = channelTitle,
                author = channelAuthor,
                artworkUrl = channelArtwork,
                description = channelDescription
            ),
            episodes = episodes
        )
    }

    private fun parseEpisode(
        podcastId: String,
        podcastTitle: String,
        fallbackArtwork: String,
        item: Element
    ): PodcastEpisode? {
        val title = item.selectFirst("title")?.text().orEmpty().trim()
        val audioUrl = item.selectFirst("enclosure")?.attr("url").orEmpty().trim()
            .ifBlank { item.select("media|content[type^=audio]").firstOrNull()?.attr("url").orEmpty().trim() }
        if (title.isEmpty() || audioUrl.isEmpty()) return null
        val episodeArtwork = item.selectFirst("itunes|image")?.attr("href").orEmpty().trim()
        return PodcastEpisode(
            podcastId = podcastId,
            podcastTitle = podcastTitle,
            title = title,
            audioUrl = audioUrl,
            pubDateMs = PodcastRssDateSupport.parse(item.selectFirst("pubDate")?.text().orEmpty()),
            durationMs = PodcastDurationSupport.parseMs(item.selectFirst("itunes|duration")?.text().orEmpty()),
            description = item.selectFirst("itunes|summary")?.text().orEmpty().trim()
                .ifBlank { item.selectFirst("description")?.text().orEmpty().trim() },
            artworkUrl = episodeArtwork.ifBlank { fallbackArtwork }
        )
    }
}
