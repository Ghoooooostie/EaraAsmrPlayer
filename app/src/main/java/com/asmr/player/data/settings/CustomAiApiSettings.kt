package com.asmr.player.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CustomAiApiSettings(
    val enabled: Boolean = false,
    val apiUrl: String = "",
    val model: String = "",
    val sendDeepSeekParams: Boolean = false
)

/**
 * 端点预设登记表。每个预设独立保存自己的 url / model / DeepSeek 专属参数 / API Key，
 * 互不影响；[id] 同时用作 DataStore 与密钥存储的分桶标识，切勿改动已发布预设的 id。
 */
data class CustomAiPresetMeta(
    val id: String,
    val label: String,
    val url: String,
    val defaultModel: String
)

val CUSTOM_AI_ENDPOINT_PRESETS: List<CustomAiPresetMeta> = listOf(
    CustomAiPresetMeta(
        id = "Groq",
        label = "Groq",
        url = "https://api.groq.com/openai/v1/chat/completions",
        // 2026-10 实测：Groq 老 Llama 系模型已全部下线，当前可用且支持 tools 的
        // 对话模型为 openai/gpt-oss-120b / gpt-oss-20b / qwen-qwen3.8-27b，选 120b
        defaultModel = "openai/gpt-oss-120b"
    ),
    CustomAiPresetMeta(
        id = "GoogleGemini",
        label = "Google Gemini",
        url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        // gemini-2.5-flash 已对部分账号下线，接口建议使用 gemini-3.8-flash
        defaultModel = "gemini-3.8-flash"
    )
)

fun customAiDefaultPresetId(): String = CUSTOM_AI_ENDPOINT_PRESETS.first().id

private val LoopbackApiHosts = setOf("localhost", "127.0.0.1", "::1")

internal fun normalizeCustomAiApiUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (!url.isHttps && url.host !in LoopbackApiHosts) return null
    if (url.pathSegments.filter(String::isNotBlank).takeLast(2) != listOf("chat", "completions")) return null
    return url.newBuilder()
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}
