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
        id = "SiliconFlow",
        label = "硅基流动",
        // OpenAI 兼容端点，国内直连/人民币充值，无需代理。
        // 注意：模型需在控制台「模型市场」勾选支持 Function Calling（翻译 agent 依赖 tools）。
        // 2026-10 官方示例的模型 ID 带 `Pro/` 前缀（如 Pro/deepseek-ai/DeepSeek-R1）；
        // 若不确定当前可用 ID，点设置里的「刷新模型列表」从 /v1/models 拉取后点选即可。
        url = "https://api.siliconflow.cn/v1/chat/completions",
        defaultModel = "Pro/deepseek-ai/DeepSeek-V3.2"
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

/**
 * 清洗 API Key 输入：
 * - 去掉首尾空白以及**所有**空白字符（含粘贴时带进来的换行/空格/制表符）；
 * - 去掉复制时可能带上的引号（中英文）与 `Bearer ` 前缀。
 *
 * 真实事故：用户从网页控制台复制 Key 时粘进了换行，OkHttp 构造 `Authorization` 头时抛
 * `IllegalArgumentException: Unexpected char 0x0a ... in Authorization value`，
 * 而该异常发生在协程里未捕获 → 整个 App 闪退；带引号则会被服务端判为 Token 无效（401）。
 * Key 在入库、拼 header 前都要过这里。
 */
internal fun sanitizeApiKeyInput(raw: String): String {
    val withoutPrefix = raw.trim()
        .removePrefix("Bearer ")
        .removePrefix("bearer ")
        .removePrefix("BEARER ")
    return withoutPrefix.filterNot { ch ->
        ch.isWhitespace() || ch == '"' || ch == '\'' || ch == '“' || ch == '”' || ch == '‘' || ch == '’'
    }
}

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
