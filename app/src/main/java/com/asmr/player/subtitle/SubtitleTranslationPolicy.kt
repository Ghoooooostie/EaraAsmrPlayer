package com.asmr.player.subtitle

internal const val DEEPSEEK_TRANSLATION_CONCURRENCY = 10
/**
 * 自定义 AI 端点并发。
 *
 * 原为 2（为 Groq/中转的严限流兜底），实测过慢：一条 460 条字幕的音轨要几十轮请求，
 * 而硅基流动这类国内服务商 domestically 限流宽松得多。
 * 提到 4：即便触发限速也只是退避重试（有 429 退避与窗口降级兜底），不会丢进度。
 */
internal const val CUSTOM_AI_TRANSLATION_CONCURRENCY = 4

/** 自定义端点并发仍低于官方 DeepSeek：部分中转对 QPS/并发限得很死。 */
internal fun translationConcurrencyFor(isCustomEndpoint: Boolean): Int =
    if (isCustomEndpoint) CUSTOM_AI_TRANSLATION_CONCURRENCY else DEEPSEEK_TRANSLATION_CONCURRENCY

internal fun fullTranslationRequestCount(totalSources: Int): Int = if (totalSources > 0) 1 else 0
