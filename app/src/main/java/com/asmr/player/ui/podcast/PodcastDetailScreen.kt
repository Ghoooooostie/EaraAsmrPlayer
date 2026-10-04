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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.asmr.player.data.local.db.dao.AlbumDao
import com.asmr.player.data.local.db.dao.TrackDao
import com.asmr.player.data.local.db.entities.titleForDisplay
import com.asmr.player.data.remote.download.DownloadBatchRequest
import com.asmr.player.data.remote.download.DownloadManager
import com.asmr.player.data.remote.download.EnqueueDownloadBatchResult
import com.asmr.player.data.remote.download.RelativeDownloadItem
import com.asmr.player.data.repository.PodcastRepository
import com.asmr.player.data.repository.PodcastSubscriptionRepository
import com.asmr.player.data.repository.PlaylistRepository
import com.asmr.player.domain.model.PodcastEpisode
import com.asmr.player.domain.model.PodcastFeed
import com.asmr.player.playback.MediaItemFactory
import com.asmr.player.ui.common.EaraLogoLoadingIndicator
import com.asmr.player.ui.common.LocalBottomOverlayPadding
import com.asmr.player.ui.theme.AsmrTheme
import com.asmr.player.util.MessageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastDetailEpisodeUi(
    val episode: PodcastEpisode,
    val isDownloaded: Boolean
)

data class PodcastDetailReadyState(
    val feed: PodcastFeed,
    val episodes: List<PodcastDetailEpisodeUi>,
    val isSubscribed: Boolean,
    val favoritedMediaIds: Set<String>
) {
    fun isFavorited(episode: PodcastEpisode): Boolean = episode.audioUrl in favoritedMediaIds
}

sealed class PodcastDetailUiState {
    data object Loading : PodcastDetailUiState()
    data class Error(val message: String) : PodcastDetailUiState()
    data class Ready(val value: PodcastDetailReadyState) : PodcastDetailUiState()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val subscriptionRepository: PodcastSubscriptionRepository,
    private val playlistRepository: PlaylistRepository,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val downloadManager: DownloadManager,
    private val messageManager: MessageManager
) : ViewModel() {

    private val feedFlow = MutableStateFlow(PodcastNavPayload.peek())
    private val episodesFlow = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    private val episodesLoaded = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)

    private val workId: String
        get() = PodcastFeed.workIdFor(feedFlow.value?.id.orEmpty())

    val uiState: StateFlow<PodcastDetailUiState> = combine(
        feedFlow,
        episodesFlow,
        episodesLoaded,
        loadError,
        observeSubscribed(),
        observeFavoriteMediaIds(),
        observeDownloadedTitlePaths()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val feed = values[0] as PodcastFeed?
        val episodes = values[1] as List<PodcastEpisode>
        val loaded = values[2] as Boolean
        val error = values[3] as String?
        val subscribed = values[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val favoriteIds = values[5] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val localPathByTitle = values[6] as Map<String, String>
        when {
            feed == null -> PodcastDetailUiState.Error("缺少播客信息,请返回后重新打开")
            error != null -> PodcastDetailUiState.Error(error)
            !loaded -> PodcastDetailUiState.Loading
            else -> PodcastDetailUiState.Ready(
                PodcastDetailReadyState(
                    feed = feed,
                    episodes = episodes.map { episode ->
                        val localPath = localPathByTitle[normalizeEpisodeKey(episode.title)]
                        PodcastDetailEpisodeUi(
                            episode = if (localPath.isNullOrBlank()) episode else episode.copy(localPath = localPath),
                            isDownloaded = !localPath.isNullOrBlank()
                        )
                    },
                    isSubscribed = subscribed,
                    favoritedMediaIds = favoriteIds
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastDetailUiState.Loading)

    init {
        loadEpisodes()
    }

    fun retry() = loadEpisodes()

    private fun loadEpisodes() {
        val feed = feedFlow.value ?: return
        viewModelScope.launch {
            try {
                val episodes = podcastRepository.episodes(feed.id)
                episodesFlow.value = episodes
                loadError.value = null
            } catch (t: Throwable) {
                loadError.value = t.message ?: "剧集加载失败"
            } finally {
                episodesLoaded.value = true
            }
        }
    }

    fun toggleSubscribe() {
        val feed = feedFlow.value ?: return
        viewModelScope.launch {
            runCatching { subscriptionRepository.toggle(feed) }
                .onFailure { messageManager.showError("订阅操作失败") }
        }
    }

    fun toggleEpisodeFavorite(episode: PodcastEpisode) {
        val feed = feedFlow.value ?: return
        viewModelScope.launch {
            try {
                val favoritesId = playlistRepository.getOrCreateFavoritesPlaylistId()
                val favorited = playlistRepository.isItemInPlaylist(favoritesId, episode.audioUrl)
                if (favorited) {
                    playlistRepository.removeItemFromPlaylist(favoritesId, episode.audioUrl)
                    messageManager.showInfo("已取消收藏该单集")
                } else {
                    playlistRepository.addItemsToPlaylist(
                        favoritesId,
                        listOf(MediaItemFactory.fromPodcastEpisode(feed, episode))
                    )
                    messageManager.showInfo("已收藏该单集")
                }
            } catch (t: Throwable) {
                messageManager.showError("收藏操作失败")
            }
        }
    }

    fun downloadAllEpisodes() {
        val feed = feedFlow.value ?: return
        val pending = (episodesFlow.value).filter { it.localPath.isBlank() }
        if (pending.isEmpty()) {
            messageManager.showInfo("全部剧集都已下载")
            return
        }
        enqueueEpisodeDownload(feed, pending)
    }

    fun downloadEpisode(episode: PodcastEpisode) {
        val feed = feedFlow.value ?: return
        if (episode.localPath.isNotBlank()) return
        enqueueEpisodeDownload(feed, listOf(episode))
    }

    private fun enqueueEpisodeDownload(feed: PodcastFeed, episodes: List<PodcastEpisode>) {
        if (episodes.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val folderName = safeFolderName(feed.title)
            val items = mutableListOf<RelativeDownloadItem>()
            val coverUrl = feed.artworkUrl.trim()
            if (coverUrl.startsWith("http", ignoreCase = true)) {
                val ext = coverUrl.substringBefore('?').substringAfterLast('.', "")
                    .takeIf { it.length in 2..5 } ?: "jpg"
                items += RelativeDownloadItem(url = coverUrl, relativePath = "cover.$ext")
            }
            episodes.forEachIndexed { index, episode ->
                val ext = episode.audioUrl.substringBefore('?').substringAfterLast('.', "")
                    .takeIf { it.length in 2..6 } ?: "mp3"
                val fileName = "${(index + 1).toString().padStart(2, '0')}_${safeFileName(episode.title)}.$ext"
                items += RelativeDownloadItem(url = episode.audioUrl, relativePath = fileName)
            }
            val result = downloadManager.enqueueBatch(
                DownloadBatchRequest(
                    albumDirectoryName = folderName,
                    logicalTaskKey = "podcast:$folderName",
                    items = items,
                    taskSubtitle = feed.title,
                    albumTitle = feed.title,
                    albumCircle = feed.author,
                    albumCoverUrl = feed.artworkUrl,
                    albumWorkId = workId,
                    albumRjCode = ""
                )
            )
            when (result) {
                is EnqueueDownloadBatchResult.Accepted ->
                    messageManager.showInfo("正在加入下载队列（${result.itemCount}项）")
                EnqueueDownloadBatchResult.DirectoryUnavailable ->
                    messageManager.showError("下载目录不可用，请重新选择或重置为默认目录")
                EnqueueDownloadBatchResult.TaskBlocked ->
                    messageManager.showInfo("相同作品已有下载任务正在处理")
            }
        }
    }

    private fun observeSubscribed() = feedFlow.flatMapLatest { feed ->
        if (feed == null) flowOf(false) else subscriptionRepository.observeIsSubscribed(feed.id)
    }

    private fun observeFavoriteMediaIds() = kotlinx.coroutines.flow.flow {
        emit(playlistRepository.getOrCreateFavoritesPlaylistId())
    }.flatMapLatest { favoritesId ->
        playlistRepository.observePlaylistItems(favoritesId)
    }.map { items -> items.map { it.mediaId }.toSet() }

    private fun observeDownloadedTitlePaths() = albumDao.observeDownloadedPodcastAlbums()
        .flatMapLatest { albums ->
            val album = albums.firstOrNull { it.workId == workId }
            if (album == null) {
                flowOf(emptyMap())
            } else {
                kotlinx.coroutines.flow.flow {
                    val tracks = trackDao.getTracksForAlbumOnce(album.id)
                    emit(tracks.associate { normalizeEpisodeKey(it.titleForDisplay) to it.path })
                }
            }
        }

    private fun normalizeEpisodeKey(title: String): String {
        return title.trim().lowercase().replace(Regex("\\s+"), "")
    }

    private fun safeFolderName(input: String): String {
        return input.trim().ifEmpty { "podcast" }.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun safeFileName(input: String): String {
        return input.trim().ifEmpty { "episode" }.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onPlayEpisodes: (PodcastFeed, List<PodcastEpisode>, Int) -> Unit,
    onAddToPlaylist: (PodcastEpisode) -> Unit,
    viewModel: PodcastDetailViewModel
) {
    val colorScheme = AsmrTheme.colorScheme
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = colorScheme.textPrimary
                )
            }
            Text(
                text = "播客详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.textPrimary
            )
        }

        when (val value = state) {
            PodcastDetailUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EaraLogoLoadingIndicator()
                }
            }
            is PodcastDetailUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = value.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.textSecondary,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        androidx.compose.material3.TextButton(onClick = viewModel::retry) {
                            Text("重试")
                        }
                    }
                }
            }
            is PodcastDetailUiState.Ready -> {
                val ready = value.value
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = LocalBottomOverlayPadding.current + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "header") {
                        PodcastDetailHeader(
                            feed = ready.feed,
                            isSubscribed = ready.isSubscribed,
                            onToggleSubscribe = viewModel::toggleSubscribe,
                            onDownloadAll = viewModel::downloadAllEpisodes
                        )
                    }
                    item(key = "episodes-header") {
                        Text(
                            text = "剧集（${ready.episodes.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.textPrimary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    itemsIndexed(items = ready.episodes, key = { _, item -> item.episode.audioUrl }) { index, item ->
                        PodcastEpisodeRow(
                            item = item,
                            favorited = ready.isFavorited(item.episode),
                            onPlay = { onPlayEpisodes(ready.feed, ready.episodes.map { it.episode }, index) },
                            onToggleFavorite = { viewModel.toggleEpisodeFavorite(item.episode) },
                            onAddToPlaylist = { onAddToPlaylist(item.episode) },
                            onDownload = { viewModel.downloadEpisode(item.episode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastDetailHeader(
    feed: PodcastFeed,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onDownloadAll: () -> Unit
) {
    val colorScheme = AsmrTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PodcastCoverThumb(
                artworkUrl = feed.artworkUrl,
                contentDescription = feed.title,
                modifier = Modifier.size(112.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = feed.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = feed.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (feed.genre.isNotBlank()) {
                    Text(
                        text = feed.genre,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.textTertiary,
                        maxLines = 1
                    )
                }
            }
        }
        if (feed.description.isNotBlank()) {
            Text(
                text = feed.description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isSubscribed,
                onClick = onToggleSubscribe,
                label = { Text(if (isSubscribed) "已订阅" else "订阅") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colorScheme.primary,
                    selectedLabelColor = colorScheme.onPrimary
                )
            )
            FilterChip(
                selected = false,
                onClick = onDownloadAll,
                label = { Text("下载全部") },
                leadingIcon = {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            )
        }
    }
}

@Composable
private fun PodcastEpisodeRow(
    item: PodcastDetailEpisodeUi,
    favorited: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit
) {
    val colorScheme = AsmrTheme.colorScheme
    val episode = item.episode
    val meta = listOfNotNull(
        podcastPubDateLabel(episode.pubDateMs),
        podcastDurationLabel(episode.durationMs),
        if (item.isDownloaded) "已下载" else null
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .clickable(role = Role.Button, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (episode.description.isNotBlank()) {
            Text(
                text = episode.description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "播放",
                tint = colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(role = Role.Button, onClick = onPlay)
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                imageVector = if (favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorited) "取消收藏" else "收藏",
                tint = if (favorited) colorScheme.primary else colorScheme.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(role = Role.Button, onClick = onToggleFavorite)
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Rounded.PlaylistAdd,
                contentDescription = "加入列表",
                tint = colorScheme.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(role = Role.Button, onClick = onAddToPlaylist)
            )
            Spacer(Modifier.width(14.dp))
            if (!item.isDownloaded) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "下载",
                    tint = colorScheme.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(role = Role.Button, onClick = onDownload)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = "已下载",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
