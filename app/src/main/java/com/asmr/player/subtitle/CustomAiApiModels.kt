package com.asmr.player.subtitle

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed interface CustomAiModelsOutcome {
    data class Loaded(val models: List<String>) : CustomAiModelsOutcome
    data class Failed(val message: String) : CustomAiModelsOutcome
}

/** 从已保存的 …/chat/completions 端点推导同 base 下的 GET /models 地址。 */
internal fun customAiModelsUrl(chatCompletionsUrl: String): String? {
    val trimmed = chatCompletionsUrl.trim().trimEnd('/')
    if (!trimmed.endsWith("/chat/completions")) return null
    val base = trimmed.removeSuffix("/chat/completions")
    return "$base/models".toHttpUrlOrNull()?.toString()
}

internal fun parseCustomAiModelIds(raw: String): List<String> {
    val data = runCatching {
        JsonParser.parseString(raw).asJsonObject.getAsJsonArray("data")
    }.getOrNull() ?: return emptyList()
    fun stringField(obj: com.google.gson.JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
    return data.mapNotNull { element ->
        runCatching {
            val obj = element.asJsonObject
            stringField(obj, "id")?.takeIf(String::isNotEmpty)
                ?: stringField(obj, "name")?.removePrefix("models/")?.takeIf(String::isNotEmpty)
        }.getOrNull()
    }.distinct().sorted()
}

internal suspend fun fetchCustomAiApiModels(
    okHttpClient: OkHttpClient,
    modelsUrl: String,
    apiKey: String
): CustomAiModelsOutcome = withContext(Dispatchers.IO) {
    val client = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url(modelsUrl)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .get()
        .build()
    try {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = parseDeepSeekErrorMessage(raw)
                val reason = when (response.code) {
                    401, 403 -> "API Key 无效或无权限（HTTP ${response.code}）"
                    404 -> "模型列表端点不存在（HTTP 404）"
                    else -> "服务返回 HTTP ${response.code}"
                }
                return@use CustomAiModelsOutcome.Failed(
                    if (detail != null) "$reason：$detail" else "$reason：${raw.trim().replace(Regex("\\s+"), " ").take(200)}"
                )
            }
            val models = parseCustomAiModelIds(raw)
            if (models.isEmpty()) {
                CustomAiModelsOutcome.Failed("连接成功但未解析到模型列表，请检查服务返回格式")
            } else {
                CustomAiModelsOutcome.Loaded(models)
            }
        }
    } catch (error: IOException) {
        CustomAiModelsOutcome.Failed(SubtitleFailureMessages.network(error, "自定义 AI"))
    }
}
