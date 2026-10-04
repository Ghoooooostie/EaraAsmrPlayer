package com.asmr.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.asmr.player.data.local.datastore.SettingsDataStore
import com.asmr.player.data.lyrics.EXTRA_ALBUM_WORK_ID
import com.asmr.player.data.lyrics.EXTRA_LYRICS_RELATIVE_PATH_NO_EXT
import com.asmr.player.data.lyrics.LyricsLoader
import com.asmr.player.data.reading.ReadingDictionary
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.SubtitleDisplayMode
import com.asmr.player.util.SubtitleEntry
import com.asmr.player.util.withDisplayMode
import com.asmr.player.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LyricsUiState(
    val title: String = "",
    val contentKey: String = "",
    val isLoading: Boolean = false,
    val lyrics: List<SubtitleEntry> = emptyList(),
    val furigana: FuriganaSpec = FuriganaSpec.NONE
)

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerConnection: PlayerConnection,
    private val lyricsLoader: LyricsLoader,
    private val settingsDataStore: SettingsDataStore,
    private val readingDictionary: ReadingDictionary
) : ViewModel() {
    /** 原始歌词：保留日文原文，供显示模式切换时重新解析。 */
    private val _loadedState = MutableStateFlow(LyricsUiState())

    val uiState: StateFlow<LyricsUiState> = combine(
        _loadedState,
        settingsDataStore.subtitleDisplayMode,
        settingsDataStore.subtitleBilingualOrder,
        settingsDataStore.japaneseFuriganaEnabled
    ) { state, mode, order, furiganaEnabled ->
        state.copy(
            lyrics = state.lyrics.withDisplayMode(mode, order),
            furigana = FuriganaSpec(
                enabled = furiganaEnabled,
                source = if (furiganaEnabled) readingDictionary else null
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    val playback = playerConnection.snapshot

    private suspend fun reloadForItem(item: MediaItem?) {
        val mediaId = item?.mediaId.orEmpty()
        if (mediaId.isBlank()) {
            _loadedState.value = LyricsUiState()
            return
        }
        val mediaKey = lyricsContentKeyForItem(item)
        _loadedState.value = _loadedState.value.copy(isLoading = true)
        val result = lyricsLoader.load(item)
        _loadedState.value = LyricsUiState(
            title = result.title,
            contentKey = mediaKey,
            isLoading = false,
            lyrics = result.lyrics
        )
    }

    fun refreshCurrentLyrics() {
        viewModelScope.launch {
            reloadForItem(playback.value.currentMediaItem)
        }
    }

    init {
        viewModelScope.launch {
            playerConnection.lyricsReloadRequests.collect {
                reloadForItem(playback.value.currentMediaItem)
            }
        }
        viewModelScope.launch {
            settingsDataStore.japaneseFuriganaEnabled
                .distinctUntilChanged()
                .filter { it }
                .collect { readingDictionary.warmUp() }
        }
        viewModelScope.launch {
            var lastMediaKey: String? = null
            playerConnection.snapshot.collect { snap ->
                val item = snap.currentMediaItem
                val mediaId = item?.mediaId.orEmpty()
                val mediaKey = lyricsContentKeyForItem(item)
                if (mediaId.isBlank()) {
                    _loadedState.value = LyricsUiState()
                    return@collect
                }
                if (lastMediaKey == mediaKey) return@collect
                lastMediaKey = mediaKey
                reloadForItem(item)
            }
        }
    }

    private fun lyricsContentKeyForItem(item: MediaItem?): String {
        val mediaId = item?.mediaId.orEmpty()
        val extras = item?.mediaMetadata?.extras
        return listOf(
            mediaId,
            extras?.getString(EXTRA_LYRICS_RELATIVE_PATH_NO_EXT).orEmpty(),
            extras?.getString("rj_code").orEmpty(),
            extras?.getString(EXTRA_ALBUM_WORK_ID).orEmpty()
        ).joinToString("|")
    }
}
