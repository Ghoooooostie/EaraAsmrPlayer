package com.asmr.player.ui.settings

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmr.player.BuildConfig
import com.asmr.player.cache.AppCacheManager
import com.asmr.player.cache.AppCacheState
import com.asmr.player.data.local.datastore.SettingsDataStore
import com.asmr.player.data.remote.NetworkHeaders
import com.asmr.player.data.remote.download.DownloadDestination
import com.asmr.player.data.remote.download.DownloadDestinationStore
import com.asmr.player.data.remote.download.DownloadDirectoryChangeResult
import com.asmr.player.data.remote.download.DownloadDirectoryCoordinator
import com.asmr.player.data.remote.update.GitHubUpdateClient
import com.asmr.player.data.remote.update.UpdateRelease
import com.asmr.player.data.settings.CoverPreviewMode
import com.asmr.player.data.settings.CustomAiApiSettings
import com.asmr.player.data.settings.DeepSeekReasoningEffort
import com.asmr.player.data.settings.DeepSeekTranslationSettings
import com.asmr.player.data.settings.AppProxyMode
import com.asmr.player.data.settings.FloatingLyricsSettings
import com.asmr.player.data.settings.LyricsPageSettings
import com.asmr.player.data.settings.NetworkRouteSettings
import com.asmr.player.data.settings.NowPlayingLyricsSettings
import com.asmr.player.data.settings.SettingsRepository
import com.asmr.player.data.settings.normalizeCustomAiApiUrl
import com.asmr.player.subtitle.SubtitleModelDownloadSource
import com.asmr.player.subtitle.SubtitleModelRepository
import com.asmr.player.subtitle.SubtitleModelState
import com.asmr.player.subtitle.CustomAiApiKeyStore
import com.asmr.player.subtitle.DeepSeekApiKeyStore
import com.asmr.player.subtitle.DeepSeekAccountRepository
import com.asmr.player.subtitle.TranslationApiTestConfig
import com.asmr.player.subtitle.CustomAiModelsOutcome
import com.asmr.player.subtitle.customAiModelsUrl
import com.asmr.player.subtitle.fetchCustomAiApiModels
import com.asmr.player.subtitle.TranslationApiTestOutcome
import com.asmr.player.subtitle.resolveTranslationApiTestConfig
import com.asmr.player.subtitle.runTranslationApiTest
import com.asmr.player.util.MessageManager
import com.asmr.player.util.SubtitleBilingualOrder
import com.asmr.player.util.SubtitleDisplayMode
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class UpdateCheckSource {
    Manual,
    Automatic
}

private const val UPDATE_APK_PREFIX = "eara-"
private const val UPDATE_APK_SUFFIX = ".apk"

internal data class DeepSeekApiKeyUiState(
    val configured: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val saveVersion: Long = 0L
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Checking(val source: UpdateCheckSource = UpdateCheckSource.Manual) : AppUpdateState
    data class UpToDate(
        val latestVersionName: String,
        val source: UpdateCheckSource = UpdateCheckSource.Manual
    ) : AppUpdateState
    data class UpdateAvailable(
        val release: UpdateRelease,
        val source: UpdateCheckSource = UpdateCheckSource.Manual
    ) : AppUpdateState
    data class Downloading(
        val release: UpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val source: UpdateCheckSource = UpdateCheckSource.Manual
    ) : AppUpdateState
    data class ReadyToInstall(
        val release: UpdateRelease,
        val apkPath: String,
        val source: UpdateCheckSource = UpdateCheckSource.Manual
    ) : AppUpdateState
    data class Failed(
        val message: String,
        val source: UpdateCheckSource = UpdateCheckSource.Manual
    ) : AppUpdateState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val settingsDataStore: SettingsDataStore,
    private val appCacheManager: AppCacheManager,
    private val okHttpClient: OkHttpClient,
    private val deepSeekAccountRepository: DeepSeekAccountRepository,
    private val downloadDestinationStore: DownloadDestinationStore,
    private val downloadDirectoryCoordinator: DownloadDirectoryCoordinator,
    private val messageManager: MessageManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val subtitleModelRepository = SubtitleModelRepository.get(context)
    private val deepSeekApiKeyStore = DeepSeekApiKeyStore.get(context)
    private val customAiApiKeyStore = CustomAiApiKeyStore.get(context)

    val downloadDestination: StateFlow<DownloadDestination> = downloadDestinationStore.destination
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            downloadDestinationStore.defaultDestination(),
        )

    fun requestDownloadDirectoryChange(onAllowed: () -> Unit) {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { downloadDirectoryCoordinator.hasUnfinishedDownloads() }) {
                messageManager.showError("请先完成或删除未完成任务")
            } else {
                onAllowed()
            }
        }
    }

    fun changeDownloadDirectory(destination: DownloadDestination, onChanged: () -> Unit = {}) {
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                downloadDirectoryCoordinator.changeDestination(destination)
            }) {
                DownloadDirectoryChangeResult.Changed -> {
                    messageManager.showInfo("下载目录已切换，正在扫描目标目录")
                    onChanged()
                }
                DownloadDirectoryChangeResult.Unchanged -> messageManager.showInfo("当前已使用该下载目录")
                DownloadDirectoryChangeResult.BlockedByUnfinishedTasks -> {
                    messageManager.showError("请先完成或删除未完成任务")
                }
                DownloadDirectoryChangeResult.DirectoryUnavailable -> {
                    messageManager.showError("下载目录不可用，请重新选择或重置为默认目录")
                }
                is DownloadDirectoryChangeResult.Failed -> {
                    messageManager.showError("切换下载目录失败")
                }
            }
        }
    }

    val floatingLyricsEnabled: StateFlow<Boolean> = settingsRepository.floatingLyricsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val floatingLyricsSettings: StateFlow<FloatingLyricsSettings> = settingsRepository.floatingLyricsSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FloatingLyricsSettings())

    val subtitleDisplayMode: StateFlow<SubtitleDisplayMode> = settingsDataStore.subtitleDisplayMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleDisplayMode.CHINESE)

    val subtitleBilingualOrder: StateFlow<SubtitleBilingualOrder> = settingsDataStore.subtitleBilingualOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleBilingualOrder.JAPANESE_FIRST)

    val lyricsPageSettings: StateFlow<LyricsPageSettings> = settingsDataStore.lyricsPageSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsPageSettings())

    val nowPlayingLyricsSettings: StateFlow<NowPlayingLyricsSettings> = settingsDataStore.nowPlayingLyricsSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingLyricsSettings())

    val dynamicPlayerHueEnabled: StateFlow<Boolean> = settingsDataStore.dynamicPlayerHueEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<String> = settingsDataStore.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val staticHueArgb: StateFlow<Int?> = settingsDataStore.staticHueArgb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val staticHueArgbLight: StateFlow<Int?> = settingsDataStore.staticHueArgbLight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val staticHueArgbDark: StateFlow<Int?> = settingsDataStore.staticHueArgbDark
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val coverBackgroundEnabled: StateFlow<Boolean> = settingsDataStore.coverBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val coverBackgroundClarity: StateFlow<Float> = settingsDataStore.coverBackgroundClarity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.35f)

    val coverPreviewMode: StateFlow<CoverPreviewMode> = settingsDataStore.coverPreviewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoverPreviewMode.Disabled)

    val autoUpdateCheckEnabled: StateFlow<Boolean> = settingsDataStore.autoUpdateCheckEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val pauseOnOutputDisconnect: StateFlow<Boolean> = settingsRepository.pauseOnOutputDisconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val resumeOnOutputConnect: StateFlow<Boolean> = settingsRepository.resumeOnOutputConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pauseOnOtherAudio: StateFlow<Boolean> = settingsRepository.pauseOnOtherAudio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val playFadeInMs: StateFlow<Int> = settingsRepository.playFadeInMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 500)

    val pauseFadeOutMs: StateFlow<Int> = settingsRepository.pauseFadeOutMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 500)

    val sfwHideSystemControls: StateFlow<Boolean> = settingsRepository.sfwHideSystemControls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showMiniPlayerBar: StateFlow<Boolean> = settingsRepository.showMiniPlayerBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val searchBlockedKeywords: StateFlow<List<String>> = settingsRepository.searchBlockedKeywords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val networkRouteSettings: StateFlow<NetworkRouteSettings> = settingsRepository.networkRouteSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkRouteSettings())

    internal val deepSeekTranslationSettings: StateFlow<DeepSeekTranslationSettings> =
        settingsRepository.deepSeekTranslationSettings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DeepSeekTranslationSettings()
        )

    val appCacheState: StateFlow<AppCacheState> = appCacheManager.state
    internal val subtitleModelState: StateFlow<SubtitleModelState> = subtitleModelRepository.state
    private val _deepSeekApiKeyState = MutableStateFlow(DeepSeekApiKeyUiState())
    internal val deepSeekApiKeyState = _deepSeekApiKeyState.asStateFlow()
    internal val deepSeekAccountState = deepSeekAccountRepository.state

    internal val customAiApiSettings: StateFlow<CustomAiApiSettings> =
        settingsRepository.customAiApiSettings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CustomAiApiSettings()
        )

    private val _customAiApiEndpointState = MutableStateFlow(DeepSeekApiKeyUiState())
    internal val customAiApiEndpointState = _customAiApiEndpointState.asStateFlow()

    private val _customAiApiKeyState = MutableStateFlow(DeepSeekApiKeyUiState())
    internal val customAiApiKeyState = _customAiApiKeyState.asStateFlow()

    internal data class CustomAiApiModelsUiState(
        val loading: Boolean = false,
        val models: List<String> = emptyList(),
        val error: String? = null
    )

    private val _customAiApiModelsState = MutableStateFlow(CustomAiApiModelsUiState())
    internal val customAiApiModelsState = _customAiApiModelsState.asStateFlow()

    internal data class TranslationApiTestUiState(
        val running: Boolean = false,
        val success: Boolean = false,
        val resultMessage: String? = null
    )

    private val _translationApiTestState = MutableStateFlow(TranslationApiTestUiState())
    internal val translationApiTestState = _translationApiTestState.asStateFlow()

    private val updateClient = GitHubUpdateClient(okHttpClient)
    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState = _updateState.asStateFlow()
    private var updateJob: Job? = null
    private var automaticCheckStarted = false
    private var settingsDataPrepared = false

    fun prepareSettingsData() {
        if (settingsDataPrepared) return
        settingsDataPrepared = true
        viewModelScope.launch(Dispatchers.IO) {
            val apiKey = deepSeekApiKeyStore.read()
            val configured = apiKey.isNotBlank()
            _deepSeekApiKeyState.value = _deepSeekApiKeyState.value.copy(configured = configured)
            if (configured) {
                deepSeekAccountRepository.bindApiKey(apiKey)
                deepSeekAccountRepository.refreshBalance(apiKey)
            }
            val customAiConfigured = customAiApiKeyStore.read().isNotBlank()
            _customAiApiKeyState.value = _customAiApiKeyState.value.copy(configured = customAiConfigured)
        }
    }

    fun setFloatingLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFloatingLyricsEnabled(enabled) }
    }

    fun updateFloatingLyricsSettings(settings: FloatingLyricsSettings) {
        viewModelScope.launch { settingsRepository.updateFloatingLyricsSettings(settings) }
    }

    fun setSubtitleDisplayMode(mode: SubtitleDisplayMode) {
        viewModelScope.launch { settingsDataStore.setSubtitleDisplayMode(mode) }
    }

    fun setSubtitleBilingualOrder(order: SubtitleBilingualOrder) {
        viewModelScope.launch { settingsDataStore.setSubtitleBilingualOrder(order) }
    }

    fun updateLyricsPageSettings(settings: LyricsPageSettings) {
        viewModelScope.launch { settingsDataStore.setLyricsPageSettings(settings) }
    }

    fun updateNowPlayingLyricsSettings(settings: NowPlayingLyricsSettings) {
        viewModelScope.launch { settingsDataStore.setNowPlayingLyricsSettings(settings) }
    }

    fun setDynamicPlayerHueEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDynamicPlayerHueEnabled(enabled) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsDataStore.setTheme(mode) }
    }

    fun setStaticHueArgb(argb: Int?) {
        viewModelScope.launch { settingsDataStore.setStaticHueArgb(argb) }
    }

    fun setCoverBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setCoverBackgroundEnabled(enabled) }
    }

    fun setCoverBackgroundClarity(clarity: Float) {
        viewModelScope.launch { settingsDataStore.setCoverBackgroundClarity(clarity) }
    }

    fun setCoverPreviewMode(mode: CoverPreviewMode) {
        viewModelScope.launch { settingsDataStore.setCoverPreviewMode(mode) }
    }

    fun setPauseOnOutputDisconnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPauseOnOutputDisconnect(enabled) }
    }

    fun openSystemAudioEffects() {
        if (!openSystemAudioEffectsSettings(context)) {
            messageManager.showError("无法打开系统音效设置")
        }
    }

    fun setResumeOnOutputConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setResumeOnOutputConnect(enabled) }
    }

    fun setPauseOnOtherAudio(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPauseOnOtherAudio(enabled) }
    }

    fun setPlayFadeInMs(durationMs: Int) {
        viewModelScope.launch { settingsRepository.setPlayFadeInMs(durationMs) }
    }

    fun setPauseFadeOutMs(durationMs: Int) {
        viewModelScope.launch { settingsRepository.setPauseFadeOutMs(durationMs) }
    }

    fun setSfwHideSystemControls(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSfwHideSystemControls(enabled) }
    }

    fun setShowMiniPlayerBar(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowMiniPlayerBar(enabled) }
    }

    fun addSearchBlockedKeyword(keyword: String) {
        viewModelScope.launch { settingsRepository.addSearchBlockedKeyword(keyword) }
    }

    fun removeSearchBlockedKeyword(keyword: String) {
        viewModelScope.launch { settingsRepository.removeSearchBlockedKeyword(keyword) }
    }

    fun useSystemProxy() {
        viewModelScope.launch { settingsRepository.useSystemProxy() }
    }

    fun setAdvancedProxy(
        mode: AppProxyMode,
        host: String,
        port: Int,
        authenticationEnabled: Boolean,
        username: String,
        password: String
    ) {
        viewModelScope.launch {
            settingsRepository.setAdvancedProxy(
                mode = mode,
                host = host,
                port = port,
                authenticationEnabled = authenticationEnabled,
                username = username,
                password = password
            )
        }
    }

    fun useSystemDns() {
        viewModelScope.launch { settingsRepository.useSystemDns() }
    }

    fun setCustomDnsServer(address: String) {
        viewModelScope.launch { settingsRepository.setCustomDnsServer(address) }
    }

    fun setAppCacheMaxSizeMb(sizeMb: Int) {
        viewModelScope.launch { settingsRepository.setAppCacheMaxSizeMb(sizeMb) }
    }

    fun refreshAppCacheSize() {
        appCacheManager.refreshSize()
    }

    fun clearAppCache() {
        appCacheManager.clearCache()
    }

    internal fun downloadSubtitleModel(
        modelId: String,
        source: SubtitleModelDownloadSource
    ) {
        runCatching { subtitleModelRepository.enqueueDownload(modelId, source) }
            .onFailure { error ->
                subtitleModelRepository.updateFailure(
                    modelId,
                    source,
                    error.message?.takeIf { it.isNotBlank() } ?: "无法开始模型下载"
                )
            }
    }

    fun cancelSubtitleModelDownload() {
        viewModelScope.launch { subtitleModelRepository.cancelDownload() }
    }

    fun selectSubtitleModel(modelId: String) {
        subtitleModelRepository.selectModel(modelId)
    }

    fun deleteSubtitleModel(modelId: String) {
        viewModelScope.launch { subtitleModelRepository.deleteModel(modelId) }
    }

    fun clearSubtitleModelFailure(modelId: String) {
        subtitleModelRepository.clearFailure(modelId)
    }

    internal fun saveDeepSeekApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _deepSeekApiKeyState.value = _deepSeekApiKeyState.value.copy(
                saving = true,
                errorMessage = null
            )
            val saved = runCatching { deepSeekApiKeyStore.save(normalized) }.isSuccess
            if (saved) {
                deepSeekAccountRepository.bindApiKey(normalized)
                val current = _deepSeekApiKeyState.value
                _deepSeekApiKeyState.value = current.copy(
                    configured = true,
                    saving = false,
                    errorMessage = null,
                    saveVersion = current.saveVersion + 1L
                )
                deepSeekAccountRepository.refreshBalance(normalized)
            } else {
                _deepSeekApiKeyState.value = _deepSeekApiKeyState.value.copy(
                    saving = false,
                    errorMessage = "API Key 保存失败"
                )
            }
        }
    }

    internal fun setDeepSeekThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeepSeekThinkingEnabled(enabled) }
    }

    internal fun setDeepSeekReasoningEffort(effort: DeepSeekReasoningEffort) {
        viewModelScope.launch { settingsRepository.setDeepSeekReasoningEffort(effort) }
    }

    internal fun setDeepSeekFinalPolishEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeepSeekFinalPolishEnabled(enabled) }
    }

    internal fun setCustomAiApiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCustomAiApiEnabled(enabled) }
    }

    internal fun setCustomAiSendDeepSeekParams(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCustomAiSendDeepSeekParams(enabled) }
    }

    internal fun saveCustomAiApiEndpoint(urlInput: String, modelInput: String) {
        val normalizedUrl = normalizeCustomAiApiUrl(urlInput)
        if (normalizedUrl == null) {
            _customAiApiEndpointState.value = _customAiApiEndpointState.value.copy(
                errorMessage = "端点需为以 /chat/completions 结尾的完整地址（远程服务需 https，本地可用 http）"
            )
            return
        }
        val model = modelInput.trim()
        if (model.isEmpty()) {
            _customAiApiEndpointState.value = _customAiApiEndpointState.value.copy(
                errorMessage = "模型名不能为空"
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _customAiApiEndpointState.value = _customAiApiEndpointState.value.copy(
                saving = true,
                errorMessage = null
            )
            val saved = runCatching {
                settingsRepository.setCustomAiApiUrl(normalizedUrl)
                settingsRepository.setCustomAiApiModel(model)
            }.isSuccess
            if (saved) {
                val current = _customAiApiEndpointState.value
                _customAiApiEndpointState.value = current.copy(
                    configured = true,
                    saving = false,
                    errorMessage = null,
                    saveVersion = current.saveVersion + 1L
                )
            } else {
                _customAiApiEndpointState.value = _customAiApiEndpointState.value.copy(
                    saving = false,
                    errorMessage = "端点配置保存失败"
                )
            }
        }
    }

    internal fun saveCustomAiApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _customAiApiKeyState.value = _customAiApiKeyState.value.copy(
                saving = true,
                errorMessage = null
            )
            val saved = runCatching { customAiApiKeyStore.save(normalized) }.isSuccess
            if (saved) {
                val current = _customAiApiKeyState.value
                _customAiApiKeyState.value = current.copy(
                    configured = normalized.isNotEmpty(),
                    saving = false,
                    errorMessage = null,
                    saveVersion = current.saveVersion + 1L
                )
            } else {
                _customAiApiKeyState.value = _customAiApiKeyState.value.copy(
                    saving = false,
                    errorMessage = "API Key 保存失败"
                )
            }
        }
    }

    internal fun refreshCustomAiApiModels() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_customAiApiModelsState.value.loading) return@launch
            _customAiApiModelsState.value = CustomAiApiModelsUiState(loading = true)
            val settings = settingsRepository.loadCustomAiApiSettings()
            val modelsUrl = customAiModelsUrl(settings.apiUrl)
            val apiKey = customAiApiKeyStore.read().trim()
            when {
                modelsUrl == null -> _customAiApiModelsState.value = CustomAiApiModelsUiState(
                    error = "请先保存有效的端点地址（需以 /chat/completions 结尾）"
                )
                apiKey.isEmpty() -> _customAiApiModelsState.value = CustomAiApiModelsUiState(
                    error = "请先保存自定义 AI API Key"
                )
                else -> when (val outcome = fetchCustomAiApiModels(okHttpClient, modelsUrl, apiKey)) {
                    is CustomAiModelsOutcome.Loaded -> _customAiApiModelsState.value =
                        CustomAiApiModelsUiState(models = outcome.models)
                    is CustomAiModelsOutcome.Failed -> _customAiApiModelsState.value =
                        CustomAiApiModelsUiState(error = outcome.message)
                }
            }
        }
    }

    internal fun testTranslationApi() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_translationApiTestState.value.running) return@launch
            _translationApiTestState.value = TranslationApiTestUiState(running = true)
            val config = resolveTranslationApiTestConfig(
                customSettings = settingsRepository.loadCustomAiApiSettings(),
                customApiKey = customAiApiKeyStore.read(),
                deepSeekApiKey = deepSeekApiKeyStore.read()
            )
            val outcome = when (config) {
                is TranslationApiTestConfig.NotReady ->
                    TranslationApiTestOutcome.Failure(config.message)
                is TranslationApiTestConfig.Endpoint -> runTranslationApiTest(
                    okHttpClient = okHttpClient,
                    gson = Gson(),
                    apiUrl = config.apiUrl,
                    apiKey = config.apiKey,
                    model = config.model
                )
            }
            _translationApiTestState.value = when (outcome) {
                TranslationApiTestOutcome.Success -> TranslationApiTestUiState(
                    success = true,
                    resultMessage = "连通正常，模型返回有效响应"
                )
                is TranslationApiTestOutcome.Failure -> TranslationApiTestUiState(
                    resultMessage = outcome.message
                )
            }
        }
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoUpdateCheckEnabled(enabled) }
    }

    fun disableAutoUpdateCheck() {
        setAutoUpdateCheckEnabled(false)
        val cur = _updateState.value
        if (cur is AppUpdateState.UpdateAvailable && cur.source == UpdateCheckSource.Automatic) {
            _updateState.value = AppUpdateState.Idle
        }
    }

    fun checkUpdate() {
        startUpdateCheck(UpdateCheckSource.Manual)
    }

    fun checkUpdateAutomatically() {
        if (automaticCheckStarted) return
        automaticCheckStarted = true
        val cur = _updateState.value
        if (cur is AppUpdateState.Checking || cur is AppUpdateState.Downloading) return
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            if (!settingsDataStore.autoUpdateCheckEnabled.first()) return@launch
            performUpdateCheck(UpdateCheckSource.Automatic)
        }
    }

    private fun startUpdateCheck(source: UpdateCheckSource) {
        val cur = _updateState.value
        if (cur is AppUpdateState.Checking || cur is AppUpdateState.Downloading) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            performUpdateCheck(source)
        }
    }

    private suspend fun performUpdateCheck(source: UpdateCheckSource) {
        _updateState.value = AppUpdateState.Checking(source)
        try {
            val release =
                updateClient.fetchLatestRelease(
                    owner = BuildConfig.UPDATE_REPO_OWNER,
                    repo = BuildConfig.UPDATE_REPO_NAME
            )
            val currentVersion = BuildConfig.VERSION_NAME
            val newer = updateClient.isNewerThanCurrent(release.versionName, currentVersion)
            _updateState.value = if (newer) {
                AppUpdateState.UpdateAvailable(release, source)
            } else {
                AppUpdateState.UpToDate(latestVersionName = release.versionName, source = source)
            }
        } catch (e: Exception) {
            val msg = e.message?.trim().orEmpty().ifBlank { "检查更新失败" }
            _updateState.value = AppUpdateState.Failed(msg, source)
        }
    }

    fun downloadLatestApk() {
        val state = _updateState.value
        val available = state as? AppUpdateState.UpdateAvailable ?: return
        val release = available.release
        val source = available.source
        updateJob?.cancel()
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = AppUpdateState.Downloading(release, 0L, 0L, source)
            var targetFile: File? = null
            var touchedTargetFile = false
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val safeTag = release.tagName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "latest" }
                val file = File(dir, "$UPDATE_APK_PREFIX$safeTag$UPDATE_APK_SUFFIX")
                targetFile = file
                cleanupStaleUpdateApks(dir, file)
                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Eara-Android")
                    .header(NetworkHeaders.HEADER_SILENT_IO_ERROR, NetworkHeaders.SILENT_IO_ERROR_ON)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("下载失败：${resp.code} ${resp.message}")
                    }
                    val body = resp.body ?: throw IllegalStateException("下载失败：空响应体")
                    val total = body.contentLength().coerceAtLeast(0L)
                    val input = body.byteStream()
                    touchedTargetFile = true
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastEmit = 0L
                        while (true) {
                            read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            downloaded += read.toLong()
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit >= 200L) {
                                _updateState.value = AppUpdateState.Downloading(
                                    release = release,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    source = source
                                )
                                lastEmit = now
                            }
                        }
                        out.flush()
                        _updateState.value = AppUpdateState.Downloading(
                            release = release,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            source = source
                        )
                    }
                }

                val ok = withContext(Dispatchers.IO) { file.exists() && file.length() > 0L }
                if (!ok) throw IllegalStateException("下载文件无效")
                _updateState.value = AppUpdateState.ReadyToInstall(release, apkPath = file.absolutePath, source = source)
            } catch (e: Exception) {
                if (touchedTargetFile) {
                    runCatching { targetFile?.takeIf { it.exists() }?.delete() }
                }
                val msg = e.message?.trim().orEmpty().ifBlank { "下载失败" }
                _updateState.value = AppUpdateState.Failed(msg, source)
            }
        }
    }

    private fun cleanupStaleUpdateApks(dir: File, keepFile: File) {
        dir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(UPDATE_APK_PREFIX) &&
                file.name.endsWith(UPDATE_APK_SUFFIX) &&
                file.absolutePath != keepFile.absolutePath
        }?.forEach { staleFile ->
            runCatching { staleFile.delete() }
        }
    }

    fun resetUpdateState() {
        val cur = _updateState.value
        if (cur is AppUpdateState.Checking || cur is AppUpdateState.Downloading) return
        _updateState.value = AppUpdateState.Idle
    }
}
