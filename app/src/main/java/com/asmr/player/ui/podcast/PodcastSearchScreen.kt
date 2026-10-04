package com.asmr.player.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed class PodcastSearchUiState {
    data object Idle : PodcastSearchUiState()
    data object Loading : PodcastSearchUiState()
    data object Empty : PodcastSearchUiState()
    data class Content(
        val feeds: List<PodcastFeed>,
        val term: String,
        val country: String
    ) : PodcastSearchUiState()

    data class Error(val message: String) : PodcastSearchUiState()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class PodcastSearchViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<PodcastSearchUiState>(PodcastSearchUiState.Idle)
    val uiState: StateFlow<PodcastSearchUiState> = _uiState.asStateFlow()

    val query = MutableStateFlow("")
    var selectedCountry: String = PodcastCatalog.countries.first().code
        private set

    private val pendingQuery = MutableStateFlow("")
    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            pendingQuery
                .debounce(500L)
                .distinctUntilChanged()
                .collect { term ->
                    if (term.isBlank()) return@collect
                    performSearch(term)
                }
        }
    }

    fun updateCountry(country: String) {
        selectedCountry = country
        val term = pendingQuery.value.trim()
        if (term.isNotEmpty()) {
            performSearch(term)
        }
    }

    fun submit(term: String) {
        val normalized = term.trim()
        pendingQuery.value = normalized
        if (normalized.isEmpty()) return
        searchJob?.cancel()
        performSearch(normalized)
    }

    private fun performSearch(term: String) {
        val country = selectedCountry
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = PodcastSearchUiState.Loading
            try {
                val feeds = podcastRepository.searchPodcasts(country = country, term = term)
                    .distinctBy { it.id }
                _uiState.value = if (feeds.isEmpty()) {
                    PodcastSearchUiState.Empty
                } else {
                    PodcastSearchUiState.Content(feeds = feeds, term = term, country = country)
                }
            } catch (t: Throwable) {
                _uiState.value = PodcastSearchUiState.Error(t.message ?: "搜索失败")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSearchScreen(
    windowSizeClass: WindowSizeClass,
    isActive: Boolean,
    isDataActive: Boolean,
    scrollToTopSignal: Long,
    onHorizontalPagerScrollLockChanged: (Boolean) -> Unit,
    onOpenPodcast: (PodcastFeed) -> Unit,
    viewModel: PodcastSearchViewModel
) {
    val colorScheme = AsmrTheme.colorScheme
    val state by viewModel.uiState.collectAsStateWhileActive(isDataActive)
    var queryText by rememberSaveable { mutableStateOf("") }
    var selectedCountry by rememberSaveable { mutableStateOf(PodcastCatalog.countries.first().code) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal == 0L) return@LaunchedEffect
        gridState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .onFocusChanged { focusState ->
                    onHorizontalPagerScrollLockChanged(focusState.isFocused)
                },
            placeholder = { Text("搜索各国播客…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.submit(queryText)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colorScheme.primary,
                unfocusedBorderColor = colorScheme.surfaceVariant,
                focusedTextColor = colorScheme.textPrimary,
                unfocusedTextColor = colorScheme.textPrimary,
                cursorColor = colorScheme.primary
            )
        )
        PodcastChipRow(
            options = PodcastCatalog.countries.map { it.code to it.label },
            selectedKey = selectedCountry,
            onSelect = { key ->
                selectedCountry = key
                viewModel.updateCountry(key)
            }
        )

        when (val value = state) {
            PodcastSearchUiState.Idle -> {
                EaraBrandedEmptyState(
                    sectionTitle = "播客搜索",
                    headline = "输入关键词搜索想听的播客",
                    sectionIcon = Icons.Rounded.Search
                )
            }
            PodcastSearchUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EaraLogoLoadingIndicator()
                }
            }
            PodcastSearchUiState.Empty -> {
                EaraBrandedEmptyState(
                    sectionTitle = "播客搜索",
                    headline = "没有找到相关播客,换个关键词试试",
                    sectionIcon = Icons.Rounded.Search
                )
            }
            is PodcastSearchUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "搜索失败",
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
                        TextButton(onClick = { viewModel.submit(queryText) }) {
                            Text("重试")
                        }
                    }
                }
            }
            is PodcastSearchUiState.Content -> {
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
