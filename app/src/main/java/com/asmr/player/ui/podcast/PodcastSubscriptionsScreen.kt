package com.asmr.player.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmr.player.data.local.db.dao.AlbumDao
import com.asmr.player.data.local.db.entities.AlbumEntity
import com.asmr.player.data.local.db.entities.PodcastSubscriptionEntity
import com.asmr.player.data.repository.PodcastSubscriptionRepository
import com.asmr.player.domain.model.PodcastFeed
import com.asmr.player.ui.common.LocalBottomOverlayPadding
import com.asmr.player.ui.common.EaraBrandedEmptyState
import com.asmr.player.ui.common.collectAsStateWhileActive
import com.asmr.player.ui.theme.AsmrTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PodcastSubscriptionsUiState(
    val downloadedAlbums: List<AlbumEntity> = emptyList(),
    val subscriptions: List<PodcastSubscriptionEntity> = emptyList()
) {
    val isEmpty: Boolean get() = downloadedAlbums.isEmpty() && subscriptions.isEmpty()
}

@HiltViewModel
class PodcastSubscriptionsViewModel @Inject constructor(
    subscriptionRepository: PodcastSubscriptionRepository,
    albumDao: AlbumDao
) : ViewModel() {
    val uiState: StateFlow<PodcastSubscriptionsUiState> = combine(
        albumDao.observeDownloadedPodcastAlbums(),
        subscriptionRepository.observeAll()
    ) { albums, subscriptions ->
        PodcastSubscriptionsUiState(
            downloadedAlbums = albums,
            subscriptions = subscriptions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSubscriptionsUiState())

    fun toFeed(subscription: PodcastSubscriptionEntity): PodcastFeed {
        return PodcastFeed(
            id = subscription.feedUrl,
            title = subscription.title,
            author = subscription.author,
            artworkUrl = subscription.artworkUrl,
            genre = subscription.genre,
            country = subscription.country
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSubscriptionsScreen(
    windowSizeClass: WindowSizeClass,
    isActive: Boolean,
    isDataActive: Boolean,
    scrollToTopSignal: Long,
    onOpenPodcast: (PodcastFeed) -> Unit,
    onOpenDownloadedAlbum: (Long) -> Unit,
    viewModel: PodcastSubscriptionsViewModel
) {
    val colorScheme = AsmrTheme.colorScheme
    val state by viewModel.uiState.collectAsStateWhileActive(isDataActive)

    if (state.isEmpty) {
        EaraBrandedEmptyState(
            sectionTitle = "我的订阅",
            headline = "还没有订阅的播客\n去发现页找到喜欢的节目并订阅吧",
            sectionIcon = Icons.Rounded.Subscriptions
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = LocalBottomOverlayPadding.current + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.downloadedAlbums.isNotEmpty()) {
            item(key = "header:downloaded") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.height(18.dp).width(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已下载",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.textPrimary
                    )
                }
            }
            items(items = state.downloadedAlbums, key = { "downloaded:${it.id}" }) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.28f))
                        .clickable(role = Role.Button) { onOpenDownloadedAlbum(album.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PodcastCoverThumb(
                        artworkUrl = album.coverUrl,
                        contentDescription = album.title
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "已下载到本地",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (state.subscriptions.isNotEmpty()) {
            item(key = "header:subscriptions") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Subscriptions,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.height(18.dp).width(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "我的订阅",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.textPrimary
                    )
                }
            }
            items(items = state.subscriptions, key = { "sub:${it.feedUrl}" }) { subscription ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.28f))
                        .clickable(role = Role.Button) { onOpenPodcast(viewModel.toFeed(subscription)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PodcastCoverThumb(
                        artworkUrl = subscription.artworkUrl,
                        contentDescription = subscription.title
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = subscription.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subscription.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
