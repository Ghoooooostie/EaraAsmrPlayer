package com.asmr.player.subtitle

internal const val DEEPSEEK_TRANSLATION_CONCURRENCY = 10
/** 自定义 AI 端点（中转/Groq 等）限流很严，并发降到 2，避免 429 雪崩。 */
internal const val CUSTOM_AI_TRANSLATION_CONCURRENCY = 2

/** 自定义端点并发远低于官方 DeepSeek，因为多数中转对 QPS/并发限得很死。 */
internal fun translationConcurrencyFor(isCustomEndpoint: Boolean): Int =
    if (isCustomEndpoint) CUSTOM_AI_TRANSLATION_CONCURRENCY else DEEPSEEK_TRANSLATION_CONCURRENCY

internal fun fullTranslationRequestCount(totalSources: Int): Int = if (totalSources > 0) 1 else 0
