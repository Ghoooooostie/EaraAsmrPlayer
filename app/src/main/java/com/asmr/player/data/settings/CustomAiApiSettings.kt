package com.asmr.player.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CustomAiApiSettings(
    val enabled: Boolean = false,
    val apiUrl: String = "",
    val model: String = "",
    val sendDeepSeekParams: Boolean = false
)

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
