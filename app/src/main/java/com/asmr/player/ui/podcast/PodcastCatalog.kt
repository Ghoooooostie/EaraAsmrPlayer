package com.asmr.player.ui.podcast

import androidx.compose.runtime.Immutable

/** iTunes 榜单支持的国家/地区;code 为 Apple country 参数。 */
@Immutable
data class PodcastCountryOption(
    val code: String,
    val label: String
)

/** Apple 播客分类;id 为榜单 genre 参数,null 表示全部。 */
@Immutable
data class PodcastGenreOption(
    val id: String?,
    val label: String
)

object PodcastCatalog {
    val countries: List<PodcastCountryOption> = listOf(
        PodcastCountryOption("jp", "🇯🇵 日本"),
        PodcastCountryOption("us", "🇺🇸 美国"),
        PodcastCountryOption("cn", "🇨🇳 中国大陆"),
        PodcastCountryOption("kr", "🇰🇷 韩国"),
        PodcastCountryOption("gb", "🇬🇧 英国"),
        PodcastCountryOption("tw", "🇹🇼 台湾"),
        PodcastCountryOption("hk", "🇭🇰 香港"),
        PodcastCountryOption("fr", "🇫🇷 法国"),
        PodcastCountryOption("de", "🇩🇪 德国"),
        PodcastCountryOption("ru", "🇷🇺 俄罗斯"),
        PodcastCountryOption("es", "🇪🇸 西班牙"),
        PodcastCountryOption("it", "🇮🇹 意大利"),
        PodcastCountryOption("br", "🇧🇷 巴西"),
        PodcastCountryOption("in", "🇮🇳 印度"),
        PodcastCountryOption("ca", "🇨🇦 加拿大"),
        PodcastCountryOption("au", "🇦🇺 澳大利亚"),
        PodcastCountryOption("th", "🇹🇭 泰国"),
        PodcastCountryOption("vn", "🇻🇳 越南")
    )

    val genres: List<PodcastGenreOption> = listOf(
        PodcastGenreOption(null, "全部"),
        PodcastGenreOption("1310", "音乐"),
        PodcastGenreOption("1303", "喜剧"),
        PodcastGenreOption("1489", "新闻"),
        PodcastGenreOption("1304", "教育"),
        PodcastGenreOption("1518", "科技"),
        PodcastGenreOption("1301", "艺术"),
        PodcastGenreOption("1321", "商业"),
        PodcastGenreOption("1512", "健康"),
        PodcastGenreOption("1309", "影视"),
        PodcastGenreOption("1324", "社会文化"),
        PodcastGenreOption("1545", "体育"),
        PodcastGenreOption("1488", "真实犯罪"),
        PodcastGenreOption("1487", "历史")
    )

    fun countryLabel(code: String): String =
        countries.firstOrNull { it.code == code }?.label ?: code

    /** 去掉国旗 emoji 的短标签,如 "日本"。 */
    fun countryLabelShort(code: String): String {
        val full = countryLabel(code)
        return full.substringAfter(' ', "").takeIf { it.isNotBlank() } ?: full
    }
}
