package com.asmr.player.data.remote.otomekoe

import com.asmr.player.data.remote.NetworkHeaders
import com.asmr.player.util.DlsiteWorkNo
import com.asmr.player.util.OtomeKoeMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 单部作品（一个帖子）。音频恒为单条流，[audioStreamUrls] 按优先级排列（m3u8 在前）。 */
data class OtomeKoePost(
    val postId: String,
    val postUrl: String,
    val rjCode: String,
    val title: String,
    val circle: String,
    val releaseDate: String,
    val cv: String,
    val tags: List<String>,
    val coverUrl: String,
    val audioStreamUrls: List<String>
)

data class OtomeKoeSearchResult(
    val keyword: String,
    val page: Int,
    val items: List<OtomeKoePost>,
    val canGoNext: Boolean
)

/** 站内搜索被 Cloudflare 挑战拦截（纯 HTTP 客户端无法通过）时抛出。 */
class OtomeKoeBlockedException(message: String) : IOException(message)

@Singleton
class OtomeKoeClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    // RJ -> 缓存。命中结果长期缓存；未收录做短 TTL 负缓存，避免反复探测。
    private val probeCache = ConcurrentHashMap<String, CachedProbe>()

    suspend fun search(keyword: String, page: Int = 1): OtomeKoeSearchResult {
        val normalizedKeyword = keyword.trim()
        require(normalizedKeyword.isNotBlank()) { "otomekoe keyword is blank" }
        val safePage = page.coerceAtLeast(1)
        val document = fetchDocument(buildSearchUrl(normalizedKeyword, safePage))
        val items = parseOtomeKoeSearchDocument(document)
        // WordPress 站点搜索每页数量固定；不足一页即最后一页。
        val canGoNext = items.size >= SEARCH_PAGE_MIN_ITEMS && safePage < MAX_PAGE
        return OtomeKoeSearchResult(
            keyword = normalizedKeyword,
            page = safePage,
            items = items,
            canGoNext = canGoNext
        )
    }

    suspend fun getPost(postId: String): OtomeKoePost? {
        val normalized = postId.trim().trim('/').takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return null
        val url = "${OtomeKoeMedia.SITE_BASE_URL}$normalized/"
        val document = fetchDocument(url)
        return parseOtomeKoePostDocument(document, url)
    }

    /**
     * 用 RJ 码探测在线音频流是否可用。命中返回 m3u8 地址，未收录返回 null。
     * 探测仅请求小体积的播放列表，携带站点 Referer 即可访问，无 Cloudflare 挑战。
     */
    suspend fun probeStreamUrl(rj: String): String? {
        val normalized = OtomeKoeMedia.normalizeRj(rj)
        if (normalized.isBlank()) return null
        probeCache[normalized]?.let { cached ->
            val fresh = cached.streamUrl != null ||
                System.currentTimeMillis() - cached.cachedAtMs < NEGATIVE_CACHE_TTL_MS
            if (fresh) return cached.streamUrl
        }
        val streamUrl = OtomeKoeMedia.streamUrlFor(normalized)
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", NetworkHeaders.USER_AGENT)
            .header("Referer", NetworkHeaders.REFERER_OTOMEKOE)
            .header("Cache-Control", "no-cache")
            .build()
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) streamUrl else null
                }
            }.getOrNull()
        }
        probeCache[normalized] = CachedProbe(resolved, System.currentTimeMillis())
        return resolved
    }

    private data class CachedProbe(val streamUrl: String?, val cachedAtMs: Long)

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        runInterruptible {
            val response = Jsoup.connect(url)
                .userAgent(NetworkHeaders.USER_AGENT)
                .referrer(NetworkHeaders.REFERER_OTOMEKOE)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .timeout(REQUEST_TIMEOUT_MS)
                .execute()
            val document = response.parse()
            if (isChallengeStatus(response.statusCode()) || isCloudflareChallengeDocument(document)) {
                throw OtomeKoeBlockedException(
                    "OtomeKoe 触发了 Cloudflare 人机验证，暂时无法访问（可稍后重试或更换网络）"
                )
            }
            if (response.statusCode() >= 400) {
                throw IOException("OtomeKoe 响应异常：HTTP ${response.statusCode()}")
            }
            document
        }
    }

    private fun isChallengeStatus(status: Int): Boolean = status == 403 || status == 503

    companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000
        private const val SEARCH_PAGE_MIN_ITEMS = 8
        private const val MAX_PAGE = 200
        private const val NEGATIVE_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        /**
         * 站内搜索必须走 WordPress 路径式链接。带查询串的入口（`/?s=`、`/?p=`、`/?cat=`）会被
         * Cloudflare 规则直接判为机器人并返回 403 + `cf-mitigated: challenge`，纯 HTTP 客户端无解。
         */
        internal fun buildSearchUrl(keyword: String, page: Int): String {
            // 查询词里的 `/` 会截断路径段，先换成空格再做百分号编码。
            val encodedKeyword = java.net.URLEncoder
                .encode(keyword.replace('/', ' '), "UTF-8")
                .replace("+", "%20")
            return buildString {
                append(OtomeKoeMedia.SITE_BASE_URL.trimEnd('/'))
                append("/search/").append(encodedKeyword).append('/')
                if (page > 1) append("page/").append(page).append('/')
            }
        }

        /**
         * 帖子列表条目解析（首页/搜索/翻页共用同一结构）。
         * 条目形如：
         * <li class="site-archive-post post-10942 ...">
         *   <h2 class="entry-title"><a href="https://otomekoe.moe/10942/">标题</a></h2>
         *   <img class="lazy" data-src="https://pic.weeabo0.xyz/RJ..._img_main.jpg">
         *   <p><strong>[230616][社团] 标题 [RJ01049217]</strong></p>
         *   <p>CV: ...</p>
         *   <p class="post-tags"><strong>Tags:</strong> <a rel="tag">..</a></p>
         * </li>
         */
        internal fun parseOtomeKoeSearchDocument(document: Document): List<OtomeKoePost> {
            return document.select("li.site-archive-post").mapNotNull { item ->
                parseOtomeKoeListItem(item)
            }
        }

        internal fun parseOtomeKoeListItem(item: Element): OtomeKoePost? {
            val titleLink = item.selectFirst("h2.entry-title a, h2 a") ?: return null
            val postUrl = titleLink.absUrl("href").ifBlank { titleLink.attr("href") }
            val postId = item.className()
                .split(' ')
                .firstOrNull { it.startsWith("post-") }
                ?.removePrefix("post-")
                ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                ?: postUrl.trimEnd('/').substringAfterLast('/').takeIf { it.all(Char::isDigit) }
                ?: return null
            val infoLine = item.select("p strong")
                .map { it.text().trim() }
                .firstOrNull { it.contains("RJ", ignoreCase = true) && it.startsWith("[") }
                .orEmpty()
            val info = parseInfoLine(infoLine)
            val rjCode = info.rjCode.ifBlank {
                DlsiteWorkNo.extractWorkNo("${titleLink.text()} $infoLine", minimumDigits = 6).orEmpty()
            }
            if (rjCode.isBlank()) return null
            val cv = item.select("p")
                .map { it.text().trim() }
                .firstOrNull { it.startsWith("CV:", ignoreCase = true) }
                ?.removePrefix("CV:")
                ?.trim()
                .orEmpty()
            val coverImg = item.selectFirst("img[data-src]") ?: item.selectFirst("img")
            val coverUrl = coverImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: coverImg?.attr("abs:src").orEmpty()
            val tags = item.select("p.post-tags a[rel=tag], .post-tags a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
            val releaseDate = item.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
            return OtomeKoePost(
                postId = postId,
                postUrl = postUrl,
                rjCode = rjCode,
                title = titleLink.text().trim().ifBlank { info.title },
                circle = info.circle,
                releaseDate = releaseDate,
                cv = cv,
                tags = tags,
                coverUrl = coverUrl.ifBlank { OtomeKoeMedia.coverUrlFor(rjCode) },
                audioStreamUrls = emptyList()
            )
        }

        /** 帖子详情页解析：补齐 CV/标签/音频流地址。 */
        internal fun parseOtomeKoePostDocument(document: Document, postUrl: String): OtomeKoePost? {
            val postId = postUrl.trimEnd('/').substringAfterLast('/').takeIf { it.all(Char::isDigit) }
                ?: return null
            val title = document.selectFirst("h1.entry-title, h1")
                ?.text()
                ?.trim()
                .orEmpty()
            val bodyText = document.body().text()
            val rjCode = DlsiteWorkNo.extractWorkNo(bodyText, minimumDigits = 6).orEmpty()
                .ifBlank { DlsiteWorkNo.extractWorkNo(title, minimumDigits = 6).orEmpty() }
            if (rjCode.isBlank()) return null

            val strongLine = document.select("p strong")
                .map { it.text().trim() }
                .firstOrNull { it.contains(rjCode, ignoreCase = true) }
                .orEmpty()
            val info = parseInfoLine(strongLine)
            val cv = document.select("p")
                .map { it.text().trim() }
                .firstOrNull { it.startsWith("CV:", ignoreCase = true) }
                ?.removePrefix("CV:")
                ?.trim()
                .orEmpty()
            val tags = document.select("p.post-tags a[rel=tag], .post-tags a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val audioStreamUrls = parseAudioStreamUrls(document)
            val coverImg = document.selectFirst("img[data-src*=weeabo0], img[src*=weeabo0]")
            val coverUrl = coverImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: coverImg?.attr("src").orEmpty()
            return OtomeKoePost(
                postId = postId,
                postUrl = postUrl,
                rjCode = rjCode,
                title = title.ifBlank { info.title },
                circle = info.circle,
                releaseDate = document.selectFirst("time[datetime]")?.attr("datetime").orEmpty(),
                cv = cv,
                tags = tags,
                coverUrl = coverUrl.ifBlank { OtomeKoeMedia.coverUrlFor(rjCode) },
                audioStreamUrls = audioStreamUrls
            )
        }

        internal fun parseAudioStreamUrls(document: Document): List<String> {
            val urls = linkedSetOf<String>()
            document.select("audio source[src]").forEach { source ->
                val src = source.attr("src").takeIf { it.isNotBlank() }
                    ?: source.attr("abs:src").orEmpty()
                if (src.isNotBlank()) urls += src
            }
            if (urls.isEmpty()) {
                REGEX_MEDIA_URLS.findAll(document.outerHtml()).forEach { match ->
                    urls += match.value
                }
            }
            // m3u8 播放优先，其余（CDN mp4 备源）按出现顺序跟随。
            return urls.sortedByDescending { it.contains(".m3u8", ignoreCase = true) }
        }

        internal fun isCloudflareChallengeDocument(document: Document): Boolean {
            val title = document.title().lowercase()
            return title.contains("just a moment") ||
                title.contains("请稍候") ||
                title.contains("請稍候") ||
                document.selectFirst("#challenge-form, #cf-challenge, iframe[src*=challenges]") != null
        }

        /** 解析 `[230616][社团] 标题 [RJ01049217]` 形式的信息行。 */
        internal fun parseInfoLine(line: String): ParsedInfoLine {
            val trimmed = line.trim()
            val rj = Regex("\\[(RJ|BJ|VJ)\\d+\\]", RegexOption.IGNORE_CASE)
                .findAll(trimmed)
                .lastOrNull()
                ?.value
                ?.trim('[', ']')
                .orEmpty()
            var work = trimmed
            if (rj.isNotBlank()) {
                work = work.replace(Regex("\\[(RJ|BJ|VJ)\\d+\\]\\s*$", RegexOption.IGNORE_CASE), "").trim()
            }
            work = work.replace(Regex("^\\[\\d{6}]\\s*"), "").trim()
            var circle = ""
            if (work.startsWith("[")) {
                val end = work.indexOf(']')
                if (end > 0) {
                    circle = work.substring(1, end).trim()
                    work = work.substring(end + 1).trim()
                }
            }
            return ParsedInfoLine(title = work, circle = circle, rjCode = rj)
        }

        private val REGEX_MEDIA_URLS = Regex(
            "https?://[A-Za-z0-9.\\-]+(?:bxcdn\\.net|bkcdn\\.net|weeab0o\\.xyz|weeabo0\\.xyz)" +
                "/[^\"'\\s<>\\\\]+?\\.(?:m3u8|mp4)"
        )

        data class ParsedInfoLine(
            val title: String,
            val circle: String,
            val rjCode: String
        )
    }
}
