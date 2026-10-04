package com.asmr.player.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmr.player.data.repository.PodcastRepository
import com.asmr.player.domain.model.PodcastFeed
import com.asmr.player.ui.common.LocalBottomOverlayPadding
import com.asmr.player.ui.common.collectAsStateWhileActive
import com.asmr.player.ui.common.EaraBrandedEmptyState
import com.asmr.player.ui.common.EaraLogoLoadingIndicator
import com.asmr.player.ui.theme.AsmrTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PodcastDiscoverUiState {
    data object Loading : PodcastDiscoverUiState()
    data object Empty : PodcastDiscoverUiState()
    data class Content(
        val feeds: List<PodcastFeed>,
        val country: String,
        val genreId: String?
    ) : PodcastDiscoverUiState()

    data class Error(val message: String) : PodcastDiscoverUiState()
}

@HiltViewModel
class PodcastDiscoverViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<PodcastDiscoverUiState>(PodcastDiscoverUiState.Loading)
    val uiState: StateFlow<PodcastDiscoverUiState> = _uiState.asStateFlow()

    private var loadedKey: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(country: String, genreId: String?) {
        val key = "$country|${genreId.orEmpty()}"
        val current = _uiState.value
        if (key == loadedKey && current is PodcastDiscoverUiState.Content) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = if (current is PodcastDiscoverUiState.Content) current else PodcastDiscoverUiState.Loading
            try {
                val feeds = podcastRepository.topPodcasts(country = country, genreId = genreId)
                    .distinctBy { it.id }
                loadedKey = key
                _uiState.value = if (feeds.isEmpty()) {
                    PodcastDiscoverUiState.Empty
                } else {
                    PodcastDiscoverUiState.Content(feeds = feeds, country = country, genreId = genreId)
                }
            } catch (t: Throwable) {
                loadedKey = null
                _uiState.value = PodcastDiscoverUiState.Error(t.message ?: "榜单加载失败")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDiscoverScreen(
    windowSizeClass: WindowSizeClass,
    isActive: Boolean,
    isDataActive: Boolean,
    scrollToTopSignal: Long,
    onOpenPodcast: (PodcastFeed) -> Unit,
    viewModel: PodcastDiscoverViewModel
) {
    val colorScheme = AsmrTheme.colorScheme
    val state by viewModel.uiState.collectAsStateWhileActive(isDataActive)
    var selectedCountry by rememberSaveable { mutableStateOf(PodcastCatalog.countries.first().code) }
    var selectedGenreId by rememberSaveable { mutableStateOf<String?>(PodcastCatalog.genres.first().id) }
    val gridState = rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(selectedCountry, selectedGenreId, isActive) {
        if (!isActive) return@LaunchedEffect
        viewModel.load(country = selectedCountry, genreId = selectedGenreId)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal == 0L) return@LaunchedEffect
        gridState.animateScrollToItem(0)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PodcastChipRow(
            options = PodcastCatalog.countries.map { it.code to it.label },
            selectedKey = selectedCountry,
            onSelect = { selectedCountry = it }
        )
        PodcastChipRow(
            options = PodcastCatalog.genres.map { (it.id ?: "all") to it.label },
            selectedKey = selectedGenreId ?: "all",
            onSelect = { key ->
                selectedGenreId = if (key == "all") null else key
            }
        )

        when (val value = state) {
            PodcastDiscoverUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EaraLogoLoadingIndicator()
                }
            }
            PodcastDiscoverUiState.Empty -> {
                EaraBrandedEmptyState(
                    sectionTitle = "播客发现",
                    headline = "这个分类暂时没有榜单内容",
                    sectionIcon = Icons.Rounded.Explore
                )
            }
            is PodcastDiscoverUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "榜单加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.textPrimary
                        )
                        Text(
                            text = value.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        TextButton(onClick = {
                            viewModel.load(country = selectedCountry, genreId = selectedGenreId)
                        }) {
                            Text("重试")
                        }
                    }
                }
            }
            is PodcastDiscoverUiState.Content -> {
                val columns = if (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact) 3 else 5
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalBottomOverlayPadding.current + 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = value.feeds, key = { it.id }) { feed ->
                        PodcastFeedCard(
                            feed = feed,
                            onClick = { onOpenPodcast(feed) }
                        )
                    }
                }
            }
        }
    }
}
