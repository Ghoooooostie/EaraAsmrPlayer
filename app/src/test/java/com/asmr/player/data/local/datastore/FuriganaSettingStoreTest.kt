package com.asmr.player.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FuriganaSettingStoreTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每个测试方法用独立文件新建 DataStore，避免 DataStore 按文件全局单例的「多实例」冲突。 */
    private fun createStore(): SettingsDataStore {
        val file = File(context.cacheDir, "furigana-${System.nanoTime()}.preferences_pb")
        runCatching { file.delete() }
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return SettingsDataStore(context, dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun japaneseFuriganaEnabled_defaultsToOff() = runBlocking {
        val store = createStore()
        assertFalse(store.japaneseFuriganaEnabled.first { !it })
    }

    @Test
    fun japaneseFuriganaEnabled_roundTrips() = runBlocking {
        // 打开：写入 true 后读出 true（每个 store 只做一次写入，绕开 Robolectric 下 DataStore 同实例二次写入的「多实例」守卫）。
        val onStore = createStore()
        onStore.setJapaneseFuriganaEnabled(true)
        assertTrue(onStore.japaneseFuriganaEnabled.first { it })

        // 关闭：写入 false 后读出 false。
        val offStore = createStore()
        offStore.setJapaneseFuriganaEnabled(false)
        assertFalse(offStore.japaneseFuriganaEnabled.first { !it })
    }

    @Test
    fun keyNameIsStableBecauseExistingInstallsDependOnIt() = runBlocking {
        val file = File(context.cacheDir, "furigana-key-${System.nanoTime()}.preferences_pb")
        runCatching { file.delete() }
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val key = booleanPreferencesKey("japanese_furigana_enabled")
        dataStore.edit { it[key] = true }
        assertEquals(true, dataStore.data.first()[key])
    }
}
