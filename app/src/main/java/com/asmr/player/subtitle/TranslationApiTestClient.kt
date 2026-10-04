package com.asmr.player.subtitle

import com.asmr.player.data.settings.CustomAiApiSettings
import com.asmr.player.data.settings.normalizeCustomAiApiUrl
import com.asmr.player.data.settings.sanitizeApiKeyInput
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal sealed interface TranslationApiTestOutcome {
    data object Success : TranslationApiTestOutcome
    data class Failure(val message: String) : TranslationApiTestOutcome
}

internal sealed interface TranslationApiTestConfig {
    data class Endpoint(val apiUrl: String, val apiKey: String, val model: String) : TranslationApiTestConfig
    data class NotReady(val message: String) : TranslationApiTestConfig
}

internal fun resolveTranslationApiTestConfig(
    customSettings: CustomAiApiSettings,
    customApiKey: String,
    deepSeekApiKey: String,
    deepSeekApiUrl: String = DEEPSEEK_CHAT_COMPLETIONS_URL,
    deepSeekModel: String = DEEPSEEK_SUBTITLE_MODEL
): TranslationApiTestConfig {
    if (customSettings.enabled) {
        if (customApiKey.isBlank()) {
            return TranslationApiTestConfig.NotReady("请先在设置中保存自定义 AI API Key")
        }
        val apiUrl = normalizeCustomAiApiUrl(customSettings.apiUrl)
            ?: return TranslationApiTestConfig.NotReady(
                "自定义 AI 端点无效：需为以 /chat/completions 结尾的完整 https 地址"
            )
        val model = customSettings.model.trim()
        if (model.isEmpty()) {
            return TranslationApiTestConfig.NotReady("自定义 AI 模型名未配置，请先保存端点")
        }
        return TranslationApiTestConfig.Endpoint(apiUrl = apiUrl, apiKey = customApiKey.trim(), model = model)
    }
    if (deepSeekApiKey.isBlank()) {
        return TranslationApiTestConfig.NotReady("请先在设置中保存 DeepSeek API Key")
    }
    return TranslationApiTestConfig.Endpoint(
        apiUrl = deepSeekApiUrl,
        apiKey = deepSeekApiKey.trim(),
        model = deepSeekModel
    )
}

internal fun buildTranslationApiTestRequestJson(gson: Gson, model: String): String = gson.toJson(
    mapOf(
        "model" to model,
        "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
        "max_tokens" to 16,
        "stream" to false
    )
)

internal fun classifyTranslationApiTestResponse(
    statusCode: Int,
    rawBody: String
): TranslationApiTestOutcome {
    if (statusCode == 200) {
        val hasChoices = runCatching {
            JsonParser.parseString(rawBody).asJsonObject.getAsJsonArray("choices").size() > 0
        }.getOrDefault(false)
        return if (hasChoices) {
            TranslationApiTestOutcome.Success
        } else {
            TranslationApiTestOutcome.Failure("响应格式异常：服务返回 200，但没有可用的 choices")
        }
    }
    val serviceMessage = parseDeepSeekErrorMessage(rawBody)
    val reason = when (statusCode) {
        401, 403 -> buildString {
            append("API Key 无效或无权限（HTTP $statusCode）")
            // 服务端只说 "Token is invalid" 时，用户很难判断是自己复制错了还是填错了平台。
            if (serviceMessage?.contains("token", ignoreCase = true) == true ||
                serviceMessage?.contains("key", ignoreCase = true) == true
            ) {
                append("：请确认复制的是该服务商控制台里的 API 密钥（通常以 sk- 开头），")
                append("不要用账号密码、其他平台的 Key，也不要带引号或换行")
            }
        }
        404 -> "端点地址或模型名不存在（HTTP 404），请检查 URL 与模型名"
        429 -> "触发速率限制（HTTP 429），请稍后重试"
        else -> "服务返回 HTTP $statusCode"
    }
    return TranslationApiTestOutcome.Failure(
        if (serviceMessage != null) "$reason：$serviceMessage" else reason
    )
}

internal suspend fun runTranslationApiTest(
    okHttpClient: OkHttpClient,
    gson: Gson,
    apiUrl: String,
    apiKey: String,
    model: String
): TranslationApiTestOutcome = withContext(Dispatchers.IO) {
    val client = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    val sanitizedKey = sanitizeApiKeyInput(apiKey)
    if (sanitizedKey.isEmpty()) {
        return@withContext TranslationApiTestOutcome.Failure("API Key 为空，请先填写并保存")
    }
    try {
        // 请求构造也放进 try：header 校验失败等 IllegalArgumentException 同样不能把 App 打崩。
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer $sanitizedKey")
            .post(buildTranslationApiTestRequestJson(gson, model).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            classifyTranslationApiTestResponse(response.code, response.body?.string().orEmpty())
        }
    } catch (error: IOException) {
        TranslationApiTestOutcome.Failure(
            "网络请求失败：${error.message ?: error.javaClass.simpleName}"
        )
    } catch (error: IllegalArgumentException) {
        TranslationApiTestOutcome.Failure("请求参数非法：${error.message ?: "地址或 API Key 含非法字符"}")
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
