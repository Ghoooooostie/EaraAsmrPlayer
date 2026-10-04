package com.asmr.player.subtitle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.asmr.player.data.settings.customAiDefaultPresetId
import com.asmr.player.data.settings.sanitizeApiKeyInput
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 自定义 AI API Key 存储。按预设分桶独立保存：每个端点预设各自持有一份密钥，
 * 切换预设互不影响。默认预设首次读取时回退到旧版共用密钥，兼容已有数据。
 */
internal class CustomAiApiKeyStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun read(presetId: String = customAiDefaultPresetId()): String = synchronized(lock) {
        val encrypted = preferences.getString(encryptedValueKey(presetId), null)
        if (encrypted != null) {
            val iv = preferences.getString(ivKey(presetId), null) ?: return@synchronized ""
            // 读取时也清洗：修复历史上粘贴时带进换行的脏 Key，避免拼 header 时崩。
            return@synchronized decrypt(encrypted, iv)?.let(::sanitizeApiKeyInput).orEmpty()
        }
        // 兼容旧版：默认预设首次读取回退到旧的共用密钥。
        if (presetId == customAiDefaultPresetId()) {
            val legacy = preferences.getString(KEY_ENCRYPTED_VALUE, null)
            val legacyIv = preferences.getString(KEY_INITIALIZATION_VECTOR, null)
            if (legacy != null && legacyIv != null) {
                return@synchronized decrypt(legacy, legacyIv)?.let(::sanitizeApiKeyInput).orEmpty()
            }
        }
        ""
    }

    fun save(presetId: String = customAiDefaultPresetId(), apiKey: String) = synchronized(lock) {
        val normalized = sanitizeApiKeyInput(apiKey)
        if (normalized.isEmpty()) {
            clearStoredValue(presetId)
            return@synchronized
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(encryptedValueKey(presetId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(presetId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun isConfigured(presetId: String = customAiDefaultPresetId()): Boolean = read(presetId).isNotBlank()

    private fun decrypt(encrypted: String, iv: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrElse {
        clearStoredValue(customAiDefaultPresetId())
        null
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun clearStoredValue(presetId: String) {
        preferences.edit()
            .remove(encryptedValueKey(presetId))
            .remove(ivKey(presetId))
            .apply()
    }

    private fun encryptedValueKey(presetId: String) = "encrypted_value_$presetId"
    private fun ivKey(presetId: String) = "initialization_vector_$presetId"

    companion object {
        private const val PREFERENCES_NAME = "custom_ai_api_key_preferences"
        private const val KEY_ENCRYPTED_VALUE = "encrypted_value"
        private const val KEY_INITIALIZATION_VECTOR = "initialization_vector"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "eara_custom_ai_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        @Volatile
        private var instance: CustomAiApiKeyStore? = null

        fun get(context: Context): CustomAiApiKeyStore {
            return instance ?: synchronized(this) {
                instance ?: CustomAiApiKeyStore(context).also { instance = it }
            }
        }
    }
}
