package com.asmr.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.asmr.player.cache.AppCacheLimits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var proxyPasswordStorage: InMemoryProxyPasswordStorage

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        proxyPasswordStorage = InMemoryProxyPasswordStorage()
        repository = SettingsRepository(dataStore, proxyPasswordStorage)
    }

    @Test
    fun loadPlaybackRuntimeSettings_returnsDefaultsWhenUnset() = runBlocking {
        assertEquals(PlaybackRuntimeSettings(), repository.loadPlaybackRuntimeSettings())
    }

    @Test
    fun loadPlaybackRuntimeSettings_returnsStoredValues() = runBlocking {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.PAUSE_ON_OUTPUT_DISCONNECT] = false
            prefs[SettingsKeys.RESUME_ON_OUTPUT_CONNECT] = true
            prefs[SettingsKeys.PAUSE_ON_OTHER_AUDIO] = false
            prefs[SettingsKeys.PLAY_FADE_IN_MS] = 1200
            prefs[SettingsKeys.PAUSE_FADE_OUT_MS] = 900
            prefs[SettingsKeys.SFW_HIDE_SYSTEM_CONTROLS] = true
            prefs[SettingsKeys.FLOATING_LYRICS_ENABLED] = true
        }

        assertEquals(
            PlaybackRuntimeSettings(
                pauseOnOutputDisconnect = false,
                resumeOnOutputConnect = true,
                pauseOnOtherAudio = false,
                playFadeInMs = 1200,
                pauseFadeOutMs = 900,
                sfwHideSystemControls = true,
                floatingLyricsEnabled = true
            ),
            repository.loadPlaybackRuntimeSettings()
        )
    }

    @Test
    fun setAsmrOneSite_persistsBackupSelection() = runBlocking {
        repository.setAsmrOneSite(-1)

        assertEquals(-1, repository.asmrOneSite.first())
    }

    @Test
    fun setAppVolumePercent_storesClampedPercent() = runBlocking {
        repository.setAppVolumePercent(47)

        assertEquals(48, repository.appVolumePercentValue())
    }

    @Test
    fun setAppVolumePercent_overwritesStoredValue() = runBlocking {
        repository.setAppVolumePercent(72)
        repository.setAppVolumePercent(32)

        assertEquals(32, repository.appVolumePercentValue())
    }

    @Test
    fun syncAppVolumePercentFromSystem_marksNextMatchingValueAsSystemSync() = runBlocking {
        repository.setAppVolumePercent(72)

        repository.syncAppVolumePercentFromSystem(32)

        assertEquals(32, repository.appVolumePercentValue())
        assertEquals(true, repository.consumePendingSystemVolumeSync(32))
        assertEquals(false, repository.consumePendingSystemVolumeSync(32))
    }

    @Test
    fun setAppVolumePercent_clearsPendingSystemSync() = runBlocking {
        repository.syncAppVolumePercentFromSystem(32)

        repository.setAppVolumePercent(48)

        assertEquals(false, repository.consumePendingSystemVolumeSync(32))
    }

    @Test
    fun appCacheMaxSizeMb_defaultsTo150() = runBlocking {
        assertEquals(AppCacheLimits.DefaultSizeMb, repository.appCacheMaxSizeMb.first())
    }

    @Test
    fun setAppCacheMaxSizeMb_clampsToSupportedRange() = runBlocking {
        repository.setAppCacheMaxSizeMb(1)
        assertEquals(AppCacheLimits.MinSizeMb, repository.appCacheMaxSizeMb.first())

        repository.setAppCacheMaxSizeMb(2_000)
        assertEquals(AppCacheLimits.MaxSizeMb, repository.appCacheMaxSizeMb.first())
    }

    @Test
    fun clearSleepTimer_skipsDataStoreWriteWhenAlreadyCleared() = runBlocking {
        val inMemoryDataStore = dataStore as InMemoryPreferencesDataStore

        repository.clearSleepTimer()

        assertEquals(0, inMemoryDataStore.updateCount)

        repository.setSleepTimerEndAtMs(1_000L)
        repository.clearSleepTimer()

        assertEquals(0L, repository.sleepTimerEndAtMs.first())
        assertEquals(2, inMemoryDataStore.updateCount)
    }

    @Test
    fun deepSeekTranslationSettings_defaultToThinkingDisabled() = runBlocking {
        val defaults = repository.loadDeepSeekTranslationSettings()
        assertFalse(defaults.thinkingEnabled)
        assertEquals(DeepSeekTranslationSettings(), defaults)
        assertEquals(
            listOf("low", "high", "max"),
            DeepSeekReasoningEffort.entries.map { it.wireValue }
        )
    }

    @Test
    fun deepSeekTranslationSettings_persistToggleAndMaxEffort() = runBlocking {
        repository.setDeepSeekThinkingEnabled(false)
        repository.setDeepSeekReasoningEffort(DeepSeekReasoningEffort.MAX)

        assertEquals(
            DeepSeekTranslationSettings(
                thinkingEnabled = false,
                reasoningEffort = DeepSeekReasoningEffort.MAX
            ),
            repository.deepSeekTranslationSettings.first()
        )
    }

    @Test
    fun deepSeekTranslationSettings_persistLowEffort() = runBlocking {
        repository.setDeepSeekReasoningEffort(DeepSeekReasoningEffort.LOW)

        assertEquals(
            DeepSeekReasoningEffort.LOW,
            repository.loadDeepSeekTranslationSettings().reasoningEffort
        )
    }

    @Test
    fun deepSeekTranslationSettings_fallBackToHighForUnknownEffort() = runBlocking {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.DEEPSEEK_REASONING_EFFORT] = "unknown"
        }

        assertEquals(
            DeepSeekReasoningEffort.HIGH,
            repository.loadDeepSeekTranslationSettings().reasoningEffort
        )
    }

    @Test
    fun equalizerSettings_includeSceneEffectDefaultsAndStoredValues() = runBlocking {
        val defaults = repository.equalizerSettings.first()
        assertFalse(defaults.sceneEffectEnabled)
        assertEquals(SceneEffectPresets.DefaultPresetId, defaults.sceneEffectPresetId)
        assertEquals(SceneEffectPresets.DefaultAmount, defaults.sceneEffectAmount)
        assertEquals(true, defaults.sceneEffectExpanded)

        repository.updateEqualizerSettings(
            defaults.copy(
                sceneEffectEnabled = true,
                sceneEffectPresetId = "tunnel",
                sceneEffectAmount = 73,
                sceneEffectExpanded = false
            )
        )

        val stored = repository.equalizerSettings.first()
        assertEquals(true, stored.sceneEffectEnabled)
        assertEquals("tunnel", stored.sceneEffectPresetId)
        assertEquals(73, stored.sceneEffectAmount)
        assertEquals(false, stored.sceneEffectExpanded)
    }

    @Test
    fun searchBlockedKeywords_defaultToEmptyList() = runBlocking {
        assertEquals(emptyList<String>(), repository.searchBlockedKeywords.first())
    }

    @Test
    fun addSearchBlockedKeyword_trimsIgnoresBlankAndDeduplicatesIgnoringCase() = runBlocking {
        repository.addSearchBlockedKeyword("  言语侵犯  ")
        repository.addSearchBlockedKeyword("")
        repository.addSearchBlockedKeyword("言语侵犯")
        repository.addSearchBlockedKeyword("VOICE")
        repository.addSearchBlockedKeyword("voice")

        assertEquals(listOf("言语侵犯", "VOICE"), repository.searchBlockedKeywords.first())
    }

    @Test
    fun removeSearchBlockedKeyword_removesIgnoringCase() = runBlocking {
        repository.addSearchBlockedKeyword("言语侵犯")
        repository.addSearchBlockedKeyword("VOICE")

        repository.removeSearchBlockedKeyword("voice")

        assertEquals(listOf("言语侵犯"), repository.searchBlockedKeywords.first())
    }

    @Test
    fun networkRouteSettings_defaultToSystemNetwork() = runBlocking {
        assertEquals(NetworkRouteSettings(), repository.networkRouteSettings.first())
    }

    @Test
    fun advancedProxy_persistsNormalizedManualRoute() = runBlocking {
        assertEquals(
            true,
            repository.setAdvancedProxy(
                mode = AppProxyMode.SOCKS5,
                host = " [::1] ",
                port = 1080,
                authenticationEnabled = false,
                username = "",
                password = ""
            )
        )

        val settings = repository.networkRouteSettings.first()
        assertEquals(AppProxyMode.SOCKS5, settings.proxyMode)
        assertEquals("::1", settings.proxyHost)
        assertEquals(1080, settings.proxyPort)
    }

    @Test
    fun advancedProxy_encryptsCredentialsAndRetainsSavedPasswordWhenBlank() = runBlocking {
        assertEquals(
            true,
            repository.setAdvancedProxy(
                mode = AppProxyMode.HTTP,
                host = "proxy.example.com",
                port = 8080,
                authenticationEnabled = true,
                username = " user ",
                password = "secret password"
            )
        )

        val first = repository.networkRouteSettings.first()
        assertTrue(first.proxyAuthenticationEnabled)
        assertEquals("user", first.proxyUsername)
        assertTrue(first.proxyPasswordConfigured)
        assertEquals("secret password", proxyPasswordStorage.read())

        assertEquals(
            true,
            repository.setAdvancedProxy(
                mode = AppProxyMode.HTTP,
                host = "proxy.example.com",
                port = 8080,
                authenticationEnabled = true,
                username = "user",
                password = ""
            )
        )
        assertEquals("secret password", proxyPasswordStorage.read())
        assertEquals(2L, repository.networkRouteSettings.first().proxyCredentialVersion)
    }

    @Test
    fun advancedProxy_disablingAuthenticationClearsSavedPassword() = runBlocking {
        repository.setAdvancedProxy(
            AppProxyMode.HTTP,
            "proxy.example.com",
            8080,
            true,
            "user",
            "secret"
        )

        repository.setAdvancedProxy(
            AppProxyMode.HTTP,
            "proxy.example.com",
            8080,
            false,
            "",
            ""
        )

        assertFalse(repository.networkRouteSettings.first().proxyAuthenticationEnabled)
        assertEquals("", proxyPasswordStorage.read())
    }

    @Test
    fun dnsServer_persistsNormalizedAddressAndCanReturnToSystem() = runBlocking {
        assertEquals(true, repository.setCustomDnsServer(" 223.005.5.5 "))
        assertEquals("223.5.5.5", repository.networkRouteSettings.first().customDnsServer)

        repository.useSystemDns()
        assertEquals("", repository.networkRouteSettings.first().customDnsServer)
    }

    @Test
    fun customAiApiSettings_returnsDefaultsWhenUnset() = runBlocking {
        // 未保存任何值时回退到默认预设（Groq）的内置端点与模型：
        // 「选了预设就能直接用」，设置页输入框也因此能显示内置值而不是空白。
        val preset = CUSTOM_AI_ENDPOINT_PRESETS.first { it.id == customAiDefaultPresetId() }
        val expected = CustomAiApiSettings(apiUrl = preset.url, model = preset.defaultModel)
        assertEquals(expected, repository.loadCustomAiApiSettings())
        assertEquals(expected, repository.customAiApiSettings.first())
    }

    @Test
    fun customAiApiSettings_selectedNonDefaultPresetFallsBackToItsOwnPresetDefaults() = runBlocking {
        repository.selectCustomAiPreset("SiliconFlow")
        val preset = CUSTOM_AI_ENDPOINT_PRESETS.first { it.id == "SiliconFlow" }
        assertEquals(
            CustomAiApiSettings(apiUrl = preset.url, model = preset.defaultModel),
            repository.loadCustomAiApiSettings()
        )
    }

    @Test
    fun customAiApiSettings_settersRoundTrip() = runBlocking {
        repository.setCustomAiApiEnabled(true)
        repository.setCustomAiApiUrl("https://example.com/v1/chat/completions")
        repository.setCustomAiApiModel("gpt-4o-mini")
        repository.setCustomAiSendDeepSeekParams(true)

        assertEquals(
            CustomAiApiSettings(
                enabled = true,
                apiUrl = "https://example.com/v1/chat/completions",
                model = "gpt-4o-mini",
                sendDeepSeekParams = true
            ),
            repository.loadCustomAiApiSettings()
        )
    }

    @Test
    fun customAiApiSettings_settersTrimAndOverwrite() = runBlocking {
        repository.setCustomAiApiUrl("  https://a.com/chat/completions ")
        repository.setCustomAiApiUrl("https://b.com/chat/completions")
        repository.setCustomAiApiModel("  qwen-max ")
        repository.setCustomAiApiEnabled(false)

        val settings = repository.loadCustomAiApiSettings()
        assertEquals("https://b.com/chat/completions", settings.apiUrl)
        assertEquals("qwen-max", settings.model)
        assertFalse(settings.enabled)
    }

    private suspend fun SettingsRepository.appVolumePercentValue(): Int {
        return appVolumePercent.first()
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val updateMutex = Mutex()
        var updateCount: Int = 0
            private set

        override val data: StateFlow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = updateMutex.withLock {
            updateCount += 1
            transform(state.value).also { state.value = it }
        }
    }
}
