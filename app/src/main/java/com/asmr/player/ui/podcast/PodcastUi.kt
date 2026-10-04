package com.asmr.player.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmr.player.domain.model.PodcastFeed
import com.asmr.player.ui.common.AsmrAsyncImage
import com.asmr.player.ui.theme.AsmrTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PodcastCoverShape = RoundedCornerShape(12.dp)
private val PodcastChipShape = RoundedCornerShape(18.dp)

internal fun podcastPubDateLabel(pubDateMs: Long): String {
    if (pubDateMs <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(pubDateMs))
}

internal fun podcastDurationLabel(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分钟"
        minutes > 0 -> "${minutes}分钟"
        else -> "${totalSeconds}秒"
    }
}

/** 国家/分类横向 chip 行,选中的使用 primary 高亮。 */
@Composable
internal fun PodcastChipRow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
) {
    val colorScheme = AsmrTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = key == selectedKey
            Box(
                modifier = Modifier
                    .clip(PodcastChipShape)
                    .background(
                        if (selected) colorScheme.primary
                        else colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable(role = Role.Button) { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colorScheme.onPrimary else colorScheme.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** 榜单/搜索结果网格卡片:封面 + 标题 + 作者。 */
@Composable
internal fun PodcastFeedCard(
    feed: PodcastFeed,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = AsmrTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(PodcastCoverShape)
                .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            AsmrAsyncImage(
                model = feed.artworkUrl,
                contentDescription = feed.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                placeholderCornerRadius = 12
            )
        }
        Text(
            text = feed.title,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = feed.author,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = colorScheme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 封面小尺寸版本,用于订阅/已下载列表行。 */
@Composable
internal fun PodcastCoverThumb(
    artworkUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = AsmrTheme.colorScheme
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(PodcastCoverShape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        AsmrAsyncImage(
            model = artworkUrl,
            contentDescription = contentDescription,
            modifier = Modifier.size(56.dp),
            placeholderCornerRadius = 12
        )
    }
}
