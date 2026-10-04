package com.asmr.player.subtitle

import android.util.Log
import com.asmr.player.data.remote.NetworkHeaders
import com.asmr.player.data.settings.DeepSeekTranslationSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DisplayNameTranslationResult(
    val albumTitle: String,
    val trackTitles: Map<Long, String>
)

internal data class DeepSeekChatMessage(
    val role: String,
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<DeepSeekToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null
)

private data class DeepSeekThinking(
    val type: String = "enabled"
)

private data class DeepSeekResponseFormat(
    val type: String = "json_object"
)

private data class DeepSeekChatRequest(
    val model: String = DEEPSEEK_SUBTITLE_MODEL,
    val messages: List<DeepSeekChatMessage>,
    val thinking: DeepSeekThinking? = DeepSeekThinking(),
    @SerializedName("reasoning_effort")
    val reasoningEffort: String? = "high",
    @SerializedName("response_format")
    val responseFormat: DeepSeekResponseFormat? = DeepSeekResponseFormat(),
    val tools: List<DeepSeekToolDefinition>? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int = 16_384,
    val stream: Boolean = false
)

private data class DeepSeekToolDefinition(
    val type: String = "function",
    val function: DeepSeekFunctionDefinition
)

private data class DeepSeekFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

private data class DeepSeekChatResponse(
    val choices: List<DeepSeekChoice> = emptyList(),
    val usage: DeepSeekChatUsage? = null
)

private data class DeepSeekChatUsage(
    @SerializedName("total_tokens")
    val totalTokens: Long = 0L
)

private data class DeepSeekChoice(
    @SerializedName("finish_reason")
    val finishReason: String? = null,
    val message: DeepSeekChatMessage = DeepSeekChatMessage(role = "assistant")
)

internal data class DeepSeekToolCall(
    val id: String = "",
    val type: String = "function",
    val function: DeepSeekToolCallFunction = DeepSeekToolCallFunction()
)

internal data class DeepSeekToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

private data class SubtitleAgentResponse(
    val assistantMessage: DeepSeekChatMessage,
    val finishReason: String?
)

internal class SubtitleTranslationException(
    message: String,
    val retryable: Boolean,
    val retryAfterMs: Long? = null,
    /** 服务端以 413/431 拒绝了请求体：agent 循环应缩小窗口与历史后重试，而不是直接失败。 */
    val payloadTooLarge: Boolean = false,
    /** 服务端以 429 拒绝：并发/速率受限，应遵医嘱退避（优先用 Retry-After）。 */
    val rateLimited: Boolean = false,
    /** 413 响应体里解析出的「本次请求 token 数」（如 Groq 的 Requested 8010）。 */
    val requestedTokens: Int? = null,
    /** 413 响应体里解析出的「该端点/模型允许的最大 token 数」（如 Groq 的 Limit 8000）。 */
    val limitTokens: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)

internal class SubtitleTranslationClient(
    okHttpClient: OkHttpClient,
    private val gson: Gson,
    apiKey: String,
    private val settings: DeepSeekTranslationSettings = DeepSeekTranslationSettings(),
    private val apiUrl: String = DEEPSEEK_CHAT_COMPLETIONS_URL,
    private val model: String = DEEPSEEK_SUBTITLE_MODEL,
    private val sendDeepSeekParams: Boolean = true,
    private val onTokenUsage: (Long) -> Unit = {}
) {
    private val normalizedApiKey = apiKey.trim().also {
        require(it.isNotEmpty()) { "请先在设置中配置 DeepSeek API Key" }
    }
    private val providerLabel = if (apiUrl == DEEPSEEK_CHAT_COMPLETIONS_URL) "DeepSeek" else "自定义 AI"
    private val authorization = "Bearer $normalizedApiKey"
    /** 最近一次实际发出的请求体字节数，用于 413 等体积类失败的诊断提示。 */
    private var lastRequestBodyBytes: Int = 0
    private val callFactory: Call.Factory = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    suspend fun translateSubtitles(
        sources: List<GeneratedSubtitleSource>,
        allowMerging: Boolean,
        confirmedCaptions: List<GeneratedSubtitleCaption> = emptyList(),
        workContext: SubtitleWorkContext? = null,
        scriptContext: SubtitleScriptContext? = null,
        onCaptionsConfirmed: suspend (List<GeneratedSubtitleCaption>) -> Unit
    ): List<GeneratedSubtitleCaption> {
        require(sources.isNotEmpty()) { "完整字幕不能为空" }
        require(sources.map(GeneratedSubtitleSource::index).distinct().size == sources.size) {
            "字幕源索引不能重复"
        }
        val confirmed = confirmedCaptions.toMutableList()
        if (confirmed.isNotEmpty()) {
            validateSubtitleCaptionBatch(
                captions = confirmed,
                expectedRemainingSources = sources,
                allowMerging = allowMerging
            )
        }
        var confirmedSourceCount = confirmed.sumOf { it.sourceIndices.size }
        if (confirmedSourceCount >= sources.size) return confirmed
        var messages = buildSubtitleAgentInitialMessages(
            gson = gson,
            sources = sources,
            allowMerging = allowMerging,
            confirmedCaptions = confirmed,
            workContext = workContext,
            scriptContext = scriptContext
        ).toMutableList()
        val initialMessages = messages.toList()
        var stalledTurnCount = 0
        var payloadLevel = 0
        var readWindowSize = SUBTITLE_READ_WINDOW_STEPS.first()
        var historyKeepBlocks = AGENT_HISTORY_KEEP_STEPS.first()
        while (confirmedSourceCount < sources.size) {
            messages = trimAgentHistory(messages, historyKeepBlocks).toMutableList()
            val response = try {
                requestSubtitleAgentResponse(
                    messages = messages,
                    targetIndices = sources.drop(confirmedSourceCount).map(GeneratedSubtitleSource::index),
                    scriptContext = scriptContext
                )
            } catch (tooLarge: SubtitleTranslationException) {
                if (!tooLarge.payloadTooLarge || payloadLevel >= MAX_PAYLOAD_DOWNGRADES) throw tooLarge
                // 若端点返回了「允许上限 / 本次请求量」，按线性缩放直接跳到合适的窗口，
                // 省去 64→32→16→8 这样一连串注定失败的 413 往返（Groq 免费档尤其慢）。
                if (tooLarge.limitTokens != null && tooLarge.requestedTokens != null && tooLarge.requestedTokens > 0) {
                    val targetWindow = (readWindowSize * tooLarge.limitTokens / tooLarge.requestedTokens).coerceAtLeast(1)
                    val jumped = SUBTITLE_READ_WINDOW_STEPS.indexOfLast { it <= targetWindow }.coerceAtLeast(0)
                    payloadLevel = payloadLevel.coerceAtLeast(jumped)
                } else {
                    payloadLevel += 1
                }
                payloadLevel = payloadLevel.coerceAtMost(MAX_PAYLOAD_DOWNGRADES)
                readWindowSize = SUBTITLE_READ_WINDOW_STEPS.getOrElse(payloadLevel) { SUBTITLE_READ_WINDOW_STEPS.last() }
                historyKeepBlocks = AGENT_HISTORY_KEEP_STEPS.getOrElse(payloadLevel) { AGENT_HISTORY_KEEP_STEPS.last() }
                Log.w(
                    TAG,
                    "字幕翻译请求体过大（413），已降级重试：level=$payloadLevel, " +
                        "readWindow=$readWindowSize, historyBlocks=$historyKeepBlocks, " +
                        "confirmedSourceCount=$confirmedSourceCount/${sources.size}" +
                        if (tooLarge.limitTokens != null && tooLarge.requestedTokens != null) {
                            " (requested=${tooLarge.requestedTokens}, limit=${tooLarge.limitTokens})"
                        } else ""
                )
                messages = initialMessages.toMutableList()
                stalledTurnCount = 0
                continue
            }
            messages += sanitizeAssistantMessageForHistory(response.assistantMessage)
            val toolCalls = response.assistantMessage.toolCalls.orEmpty()
            var madeProgress = false
            if (toolCalls.isEmpty()) {
                stalledTurnCount += 1
                messages += DeepSeekChatMessage(
                    role = "user",
                    content = subtitleAgentContinueMessage(response.finishReason)
                )
            } else {
                toolCalls.forEach { toolCall ->
                    val toolCallId = toolCall.id.trim()
                    if (toolCallId.isEmpty()) {
                        throw SubtitleTranslationException(
                            message = "字幕翻译模型返回的工具调用缺少 id",
                            retryable = true
                        )
                    }
                    val toolResult = when (toolCall.function.name.orEmpty()) {
                        SUBTITLE_READ_TOOL_NAME -> buildSubtitleReadToolResultMessage(
                            gson = gson,
                            toolCallId = toolCallId,
                            sources = sources,
                            confirmedCaptions = confirmed,
                            windowSize = readWindowSize
                        )

                        SUBTITLE_WRITE_TOOL_NAME -> {
                            // 窗口化写入：只允许写出当前读窗口内的字幕，杜绝模型一次写出整条音轨撑爆请求体（Groq 413）。
                            // read 已窗口化，但 write 接受「整段剩余」时模型仍可能把已读到的全部字幕回显进参数，单条请求即超限。
                            val remainingSources = sources.drop(confirmedSourceCount)
                            val writeWindowSources = remainingSources.take(readWindowSize)
                            val parsed = runCatching {
                                parseSubtitleWriteToolArguments(
                                    arguments = toolCall.function.arguments.orEmpty(),
                                    expectedRemainingSources = writeWindowSources,
                                    allowMerging = allowMerging
                                )
                            }
                            parsed.fold(
                                onSuccess = { captions ->
                                    onCaptionsConfirmed(captions)
                                    confirmed += captions
                                    val writtenSourceCount = captions.sumOf { it.sourceIndices.size }
                                    confirmedSourceCount += writtenSourceCount
                                    madeProgress = true
                                    buildSubtitleWriteToolResultMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        writtenCaptionCount = captions.size,
                                        writtenSourceCount = writtenSourceCount,
                                        confirmedSourceCount = confirmedSourceCount,
                                        sources = sources
                                    )
                                },
                                onFailure = { error ->
                                    val expectedCount = writeWindowSources.size
                                    val startIdx = writeWindowSources.firstOrNull()?.index ?: confirmedSourceCount
                                    val hint = "请从 index $startIdx 开始，只提交接下来的最多 $expectedCount 条字幕" +
                                        "（必须是从首个未确认索引起的连续前缀，不要一次性提交全部未译字幕）。"
                                    buildSubtitleToolErrorMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        message = error.message?.takeIf { it.isNotBlank() }?.let { "$it。$hint" } ?: hint
                                    )
                                }
                            )
                        }

                        SCRIPT_LIST_TOOL_NAME -> scriptContext?.let { context ->
                            madeProgress = true
                            buildScriptListToolResultMessage(gson, toolCallId, context)
                        } ?: buildSubtitleToolErrorMessage(
                            gson = gson,
                            toolCallId = toolCallId,
                            message = "台本检索不可用"
                        )

                        SCRIPT_READ_TOOL_NAME -> scriptContext?.let { context ->
                            val parsed = runCatching {
                                parseScriptReadToolArguments(
                                    arguments = toolCall.function.arguments.orEmpty(),
                                    fileCount = context.files.size
                                )
                            }
                            parsed.fold(
                                onSuccess = { args ->
                                    madeProgress = true
                                    buildScriptReadToolResultMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        context = context,
                                        fileIndex = args.fileIndex,
                                        offset = args.offset,
                                        limit = args.limit
                                    )
                                },
                                onFailure = { error ->
                                    buildSubtitleToolErrorMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        message = error.message.orEmpty().ifBlank { "台本读取参数无效" }
                                    )
                                }
                            )
                        } ?: buildSubtitleToolErrorMessage(
                            gson = gson,
                            toolCallId = toolCallId,
                            message = "台本检索不可用"
                        )

                        else -> buildSubtitleToolErrorMessage(
                            gson = gson,
                            toolCallId = toolCallId,
                            message = "未知工具：${toolCall.function.name.orEmpty()}"
                        )
                    }
                    messages += toolResult
                }
                stalledTurnCount = if (madeProgress) 0 else stalledTurnCount + 1
            }
            if (stalledTurnCount >= MAX_SUBTITLE_AGENT_STALLED_TURNS) {
                throw SubtitleTranslationException(
                    message = "字幕翻译 agent 连续多轮没有写入字幕",
                    retryable = true
                )
            }
        }
        return confirmed
    }

    /**
     * 翻译一次本地库作品的显示名（作品标题 + 音轨标题），用于翻译任务进行期间的
     * 显示名覆盖。失败可重试，重试次数由 [maxAttempts] 控制。
     */
    suspend fun translateDisplayNames(
        albumTitle: String,
        circle: String,
        cv: String,
        trackTitles: List<Pair<Long, String>>,
        maxAttempts: Int = 2
    ): DisplayNameTranslationResult {
        require(albumTitle.isNotBlank()) { "作品标题不能为空" }
        require(trackTitles.isNotEmpty()) { "音轨列表不能为空" }
        require(trackTitles.map(Pair<Long, String>::first).distinct().size == trackTitles.size) {
            "音轨 id 不能重复"
        }
        val requestBody = buildDeepSeekTitleTranslationRequest(
            gson = gson,
            albumTitle = albumTitle,
            circle = circle,
            cv = cv,
            trackTitles = trackTitles,
            settings = settings,
            model = model,
            sendDeepSeekParams = sendDeepSeekParams
        )
        val expectedTrackIds = trackTitles.map(Pair<Long, String>::first)
        return retrySubtitleTranslation(maxAttempts = maxAttempts, onAttempt = { _, _ -> }, onRetry = { _, _, _ -> }) {
            executeTranslationRequest(requestBody, emptyList()) { message, finishReason ->
                require(finishReason != "length") { "作品显示名翻译输出达到长度限制" }
                val content = message.content.orEmpty()
                require(content.isNotBlank()) { "作品显示名翻译模型返回了空内容" }
                parseDisplayNameTranslationResponse(content, expectedTrackIds)
            }
        }
    }

    /**
     * 作品级最终润色：在所有音轨翻译完成后，让 agent 通过 read/write 工具逐条
     * 精修中文字幕（语序、人称、称呼、用词、语气、标点）。
     *
     * 非阻塞语义：本方法只负责与模型交互并返回精修结果，由调用方决定是否/何时
     * 落库；异常由调用方按"不阻塞任务完成"的策略处理。
     */
    suspend fun polishSubtitles(
        tracks: List<PolishTrackInput>,
        workContext: SubtitleWorkContext? = null,
        onCaptionsPolished: suspend (List<PolishCaptionResult>) -> Unit
    ): List<PolishCaptionResult> {
        require(tracks.isNotEmpty()) { "润色音轨列表不能为空" }
        val allCaptions = tracks.flatMap(PolishTrackInput::captions)
        require(allCaptions.map(PolishCaptionInput::captionId).distinct().size == allCaptions.size) {
            "润色字幕主键不能重复"
        }
        var messages = buildPolishAgentInitialMessages(
            gson = gson,
            tracks = tracks,
            workContext = workContext
        ).toMutableList()
        val initialMessages = messages.toList()
        val polishedByCaptionId = HashMap<Long, String>()
        // 服务端维护读取游标：read 每次翻页，直至全部条目展示完毕即视为检查完成。
        var nextReadOffset = 0
        var readCompleted = false
        var stalledTurnCount = 0
        var payloadLevel = 0
        var readPageSize = POLISH_READ_PAGE_STEPS.first()
        var historyKeepBlocks = AGENT_HISTORY_KEEP_STEPS.first()
        while (!readCompleted) {
            messages = trimAgentHistory(messages, historyKeepBlocks).toMutableList()
            val response = try {
                requestSubtitleAgentResponse(
                    messages = messages,
                    targetIndices = emptyList(),
                    polishMode = true
                )
            } catch (tooLarge: SubtitleTranslationException) {
                if (!tooLarge.payloadTooLarge || payloadLevel >= MAX_PAYLOAD_DOWNGRADES) throw tooLarge
                payloadLevel += 1
                readPageSize = POLISH_READ_PAGE_STEPS.getOrElse(payloadLevel) { POLISH_READ_PAGE_STEPS.last() }
                historyKeepBlocks = AGENT_HISTORY_KEEP_STEPS.getOrElse(payloadLevel) { AGENT_HISTORY_KEEP_STEPS.last() }
                Log.w(
                    TAG,
                    "字幕润色请求体过大（413），已降级重试：level=$payloadLevel, " +
                        "readPage=$readPageSize, historyBlocks=$historyKeepBlocks, offset=$nextReadOffset"
                )
                messages = initialMessages.toMutableList()
                stalledTurnCount = 0
                continue
            }
            messages += sanitizeAssistantMessageForHistory(response.assistantMessage)
            val toolCalls = response.assistantMessage.toolCalls.orEmpty()
            var madeProgress = false
            if (toolCalls.isEmpty()) {
                stalledTurnCount += 1
                messages += DeepSeekChatMessage(
                    role = "user",
                    content = TranslationPrompts.subtitlePolishContinueMessageGeneric()
                )
            } else {
                toolCalls.forEach { toolCall ->
                    val toolCallId = toolCall.id.trim()
                    if (toolCallId.isEmpty()) {
                        throw SubtitleTranslationException(
                            message = "润色模型返回的工具调用缺少 id",
                            retryable = true
                        )
                    }
                    val toolResult = when (toolCall.function.name.orEmpty()) {
                        POLISH_READ_TOOL_NAME -> {
                            val resultMessage = buildPolishReadToolResultMessage(
                                gson = gson,
                                toolCallId = toolCallId,
                                tracks = tracks,
                                polishedByCaptionId = polishedByCaptionId,
                                offset = nextReadOffset,
                                pageSize = readPageSize
                            )
                            // read 翻页：每次调用展示一页，游标前进；全部展示完则完成。
                            if (nextReadOffset < allCaptions.size) {
                                nextReadOffset = (nextReadOffset + readPageSize)
                                    .coerceAtMost(allCaptions.size)
                                madeProgress = true
                            } else {
                                readCompleted = true
                            }
                            resultMessage
                        }

                        POLISH_WRITE_TOOL_NAME -> {
                            val parsed = runCatching {
                                parsePolishWriteToolArguments(
                                    arguments = toolCall.function.arguments.orEmpty(),
                                    expectedCaptions = allCaptions
                                )
                            }
                            parsed.fold(
                                onSuccess = { results ->
                                    onCaptionsPolished(results)
                                    results.forEach { polishedByCaptionId[it.captionId] = it.chinese }
                                    madeProgress = true
                                    buildPolishWriteToolResultMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        results = results,
                                        allCaptions = allCaptions,
                                        polishedByCaptionId = polishedByCaptionId
                                    )
                                },
                                onFailure = { error ->
                                    buildSubtitleToolErrorMessage(
                                        gson = gson,
                                        toolCallId = toolCallId,
                                        message = error.message.orEmpty().ifBlank { "润色参数无效" }
                                    )
                                }
                            )
                        }

                        else -> buildSubtitleToolErrorMessage(
                            gson = gson,
                            toolCallId = toolCallId,
                            message = "未知工具：${toolCall.function.name.orEmpty()}"
                        )
                    }
                    messages += toolResult
                }
                stalledTurnCount = if (madeProgress) 0 else stalledTurnCount + 1
            }
            if (stalledTurnCount >= MAX_POLISH_AGENT_STALLED_TURNS) {
                throw SubtitleTranslationException(
                    message = "润色 agent 连续多轮没有写入字幕",
                    retryable = true
                )
            }
        }
        return allCaptions.map { caption ->
            PolishCaptionResult(
                captionId = caption.captionId,
                chinese = polishedByCaptionId[caption.captionId] ?: caption.chinese
            )
        }
    }

    private suspend fun requestSubtitleAgentResponse(
        messages: List<DeepSeekChatMessage>,
        targetIndices: List<Int>,
        polishMode: Boolean = false,
        scriptContext: SubtitleScriptContext? = null
    ): SubtitleAgentResponse {
        val requestBody = if (polishMode) {
            buildPolishAgentRequest(
                gson = gson,
                messages = messages,
                settings = settings,
                model = model,
                sendDeepSeekParams = sendDeepSeekParams
            )
        } else {
            buildDeepSeekSubtitleAgentRequest(
                gson = gson,
                messages = messages,
                settings = settings,
                scriptContext = scriptContext,
                model = model,
                sendDeepSeekParams = sendDeepSeekParams
            )
        }
        return executeTranslationRequest(
            requestBody,
            targetIndices
        ) { message, finishReason ->
            SubtitleAgentResponse(
                assistantMessage = message.copy(role = "assistant", content = message.content.orEmpty()),
                finishReason = finishReason
            )
        }
    }

    private suspend fun <T> executeTranslationRequest(
        requestBody: String,
        targetIndices: List<Int>,
        parseMessage: (DeepSeekChatMessage, finishReason: String?) -> T
    ): T = withContext(Dispatchers.IO) {
        val requestBodyBytes = requestBody.toByteArray(Charsets.UTF_8).size
        lastRequestBodyBytes = requestBodyBytes
        if (requestBodyBytes > REQUEST_BODY_LOG_THRESHOLD_BYTES) {
            Log.w(
                TAG,
                "$providerLabel 翻译请求体较大：${requestBodyBytes / 1024} KB, model=$model, url=$apiUrl"
            )
        }
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", authorization)
            .header(NetworkHeaders.HEADER_SILENT_IO_ERROR, NetworkHeaders.SILENT_IO_ERROR_ON)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = callFactory.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        val response = try {
            executeCancellable(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw SubtitleTranslationException(
                message = SubtitleFailureMessages.network(error, providerLabel),
                retryable = true,
                cause = error
            )
        }
        try {
            response.use {
                val raw = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    Log.w(
                        TAG,
                        "翻译请求失败 status=${it.code} requestBytes=$requestBodyBytes " +
                            "model=$model url=$apiUrl resp=${raw.take(300)}"
                    )
                    val failure = SubtitleFailureMessages.deepSeekHttp(
                        statusCode = it.code,
                        serviceMessage = parseDeepSeekErrorMessage(raw),
                        providerLabel = providerLabel,
                        requestBytes = requestBodyBytes
                    )
                    val requestedTokens = if (failure.payloadTooLarge) parseTokenNumber(raw, "Requested") else null
                    val limitTokens = if (failure.payloadTooLarge) parseTokenNumber(raw, "Limit") else null
                    throw SubtitleTranslationException(
                        message = failure.message,
                        retryable = failure.retryable,
                        retryAfterMs = parseRetryAfterMillis(it.header("Retry-After")),
                        payloadTooLarge = failure.payloadTooLarge,
                        rateLimited = it.code == 429,
                        requestedTokens = requestedTokens,
                        limitTokens = limitTokens
                    )
                }
                val deepSeekResponse = runCatching {
                    gson.fromJson(raw, DeepSeekChatResponse::class.java)
                }.getOrNull()
                deepSeekResponse?.usage?.totalTokens?.takeIf { totalTokens -> totalTokens > 0L }?.let { totalTokens ->
                    runCatching { onTokenUsage(totalTokens) }
                        .onFailure { error -> Log.w(TAG, "记录 DeepSeek token 用量失败", error) }
                }
                val choice = deepSeekResponse?.choices?.firstOrNull()
                val message = choice?.message ?: DeepSeekChatMessage(role = "assistant")
                val content = message.content.orEmpty()
                val reasoningContent = message.reasoningContent.orEmpty()
                val toolArgumentLength = message.toolCalls.orEmpty()
                    .sumOf { it.function.arguments.orEmpty().length }
                try {
                    parseMessage(message, choice?.finishReason)
                } catch (error: IllegalArgumentException) {
                    val reason = error.message?.takeIf { it.isNotBlank() } ?: "未知格式错误"
                    logRejectedTranslation(
                        reason = reason,
                        targetIndices = targetIndices,
                        rawLength = raw.length,
                        finishReason = choice?.finishReason,
                        contentLength = content.length,
                        reasoningLength = reasoningContent.length,
                        toolArgumentLength = toolArgumentLength,
                        error = error
                    )
                    throw SubtitleTranslationException(
                        message = "字幕翻译模型返回格式错误：$reason",
                        retryable = true,
                        cause = error
                    )
                }
            }
        } finally {
            cancellationHandle.dispose()
        }
    }

    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    if (call.isCanceled()) {
                        continuation.resumeWithException(CancellationException("字幕翻译请求已取消", e))
                    } else {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "SubtitleTranslation"

        private fun logRejectedTranslation(
            reason: String,
            targetIndices: List<Int>,
            rawLength: Int,
            finishReason: String?,
            contentLength: Int,
            reasoningLength: Int,
            toolArgumentLength: Int,
            error: Throwable? = null
        ) {
            Log.w(
                TAG,
                "DeepSeek subtitle translation rejected: reason=$reason, " +
                    "targets=${targetIndices.summarizeIndices()}, rawLength=$rawLength, finishReason=$finishReason, " +
                    "contentLength=$contentLength, reasoningLength=$reasoningLength, " +
                    "toolArgumentLength=$toolArgumentLength",
                error
            )
        }

        private fun List<Int>.summarizeIndices(): String = when {
            isEmpty() -> "[]"
            size == 1 -> "[${single()}]"
            else -> "[${first()}..${last()}] ($size)"
        }
    }
}

internal fun parseDeepSeekErrorMessage(raw: String): String? {
    val jsonMessage = runCatching {
        JsonParser.parseString(raw).asJsonObject
            .getAsJsonObject("error")
            ?.get("message")
            ?.asString
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(300)
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()
    if (jsonMessage != null) return jsonMessage
    return raw.trim()
        .replace(Regex("\\s+"), " ")
        .take(300)
        .takeIf(String::isNotEmpty)
}

/**
 * 从 413 响应体里尽力解析形如 `Limit 8000` / `Requested 8010` 的数字。
 * Groq 等端点在请求超限时会在 error.message 中给出「允许上限」与「本次请求量」，
 * 据此可以一步跳到合适的窗口，省去逐级试探的失败往返。解析失败返回 null。
 */
private fun parseTokenNumber(raw: String, label: String): Int? {
    val pattern = Regex("""$label\s*[:=(]?\s*(\d[\d,]*)\b""")
    return pattern.find(raw)?.groupValues?.getOrNull(1)
        ?.replace(",", "")
        ?.toIntOrNull()
}

internal fun parseRetryAfterMillis(
    value: String?,
    nowMs: Long = System.currentTimeMillis()
): Long? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    normalized.toLongOrNull()?.let { seconds ->
        return seconds.coerceAtLeast(0L).let { safeSeconds ->
            if (safeSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else safeSeconds * 1_000L
        }
    }
    val retryAt = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
        }.parse(normalized)?.time
    }.getOrNull() ?: return null
    return (retryAt - nowMs).coerceAtLeast(0L)
}

internal const val DEEPSEEK_SUBTITLE_MODEL = "deepseek-flash"
internal const val SUBTITLE_READ_TOOL_NAME = "read_subtitle_translation_state"
internal const val SUBTITLE_WRITE_TOOL_NAME = "write_timed_chinese_subtitles"
internal const val POLISH_READ_TOOL_NAME = "read_subtitle_polish_state"
internal const val POLISH_WRITE_TOOL_NAME = "write_polished_chinese_subtitles"
internal const val SCRIPT_LIST_TOOL_NAME = "list_work_script_files"
internal const val SCRIPT_READ_TOOL_NAME = "read_work_script_file"
internal const val DEEPSEEK_CHAT_COMPLETIONS_URL = "https://api.deepseek.com/chat/completions"
private const val MAX_SUBTITLE_AGENT_STALLED_TURNS = 4
private const val MAX_POLISH_AGENT_STALLED_TURNS = 4
// 每轮 read 工具只回传从已确认位置开始的下一窗字幕，避免请求体随进度无限膨胀
// （Groq 等服务对单次请求体积有上限，整份重发会触发 HTTP 413）。
private const val SUBTITLE_READ_WINDOW_SIZE = 64
// agent 对话历史最多保留的「assistant↔tool」轮次块数；超出的旧轮次在每轮请求前被裁剪，
// 把请求体积钉死，避免长音轨多轮后历史累积触发 HTTP 413。
private const val AGENT_HISTORY_KEEP_BLOCKS = 4
private const val POLISH_READ_PAGE_SIZE = 40
// 不同端点对单次请求体上限差异极大（部分 OpenAI 兼容网关/中转站只有几十 KB）。
// 收到 413 时按下面阶梯逐级缩小窗口与历史深度，并丢弃已累积的历史重开会话，
// 用更小的请求体继续翻译，而不是直接判定任务失败。
// 窗口阶梯一路降到 1：Groq 的 gpt-oss-20b 等模型单请求输入上限只有几千 token，
// 大到 8 条仍会 413，必须能继续缩到 4/2/1 才能塞进限制。
private val SUBTITLE_READ_WINDOW_STEPS = listOf(64, 32, 16, 8, 4, 2, 1)
// 历史块下限保持 1（不能到 0）：否则刚读回的字幕会被裁掉，模型陷入反复 read 死循环。
private val AGENT_HISTORY_KEEP_STEPS = listOf(4, 2, 1, 1, 1, 1, 1)
private val POLISH_READ_PAGE_STEPS = listOf(POLISH_READ_PAGE_SIZE, 20, 10)
// 与上面两个阶梯的长度（7）一致，保证 readWindow 能一路降到 1。
internal const val MAX_PAYLOAD_DOWNGRADES = 6
// 请求体超过该阈值时打一条日志，便于在 logcat 里核对端点上限。
private const val REQUEST_BODY_LOG_THRESHOLD_BYTES = 48 * 1024
private const val SCRIPT_READ_DEFAULT_LIMIT = 4_000
private const val SCRIPT_READ_MAX_LIMIT = 8_000

/**
 * 字幕翻译 agent 可用的作品级静态元数据。
 *
 * 各音轨并行翻译时彼此独立、完成顺序不定，因此这里只注入翻译开始前就已确定的
 * 静态信息（作品标题、当前音轨标题、声优、社团），不依赖其他音轨的翻译进度，
 * 用于让人名、称呼、术语在跨轨时保持一致。
 */
internal data class SubtitleWorkContext(
    val workTitleJapanese: String = "",
    val workTitleChinese: String = "",
    val trackTitleJapanese: String = "",
    val trackTitleChinese: String = "",
    val circle: String = "",
    val cv: String = ""
)

/**
 * 润色 agent 的输入：一个音轨的日文原文 + 当前中文字幕条目。
 * [captionId] 是 subtitles 表主键，用于把精修结果精确写回。
 */
internal data class PolishTrackInput(
    val trackIndex: Int,
    val trackTitleJapanese: String = "",
    val trackTitleChinese: String = "",
    val captions: List<PolishCaptionInput>
)

internal data class PolishCaptionInput(
    val captionId: Long,
    val sourceIndex: Int,
    val japanese: String,
    val chinese: String
)

/** 润色结果：按字幕主键返回精修后的中文文本。 */
internal data class PolishCaptionResult(
    val captionId: Long,
    val chinese: String
)

internal fun buildSubtitleWorkContextSection(context: SubtitleWorkContext?): String {
    val c = context ?: return "作品上下文：无（未提供）。请从日文原文推断人名、称呼，并在整轨内保持一致。"
    return buildString {
        append("作品上下文（用于统一人名、称呼与术语，必须严格遵守）：")
        c.workTitleJapanese.takeIf(String::isNotBlank)?.let { append("\n- 作品标题（日文）：$it") }
        c.workTitleChinese.takeIf(String::isNotBlank)?.let { append("\n- 作品标题（中文）：$it") }
        c.trackTitleJapanese.takeIf(String::isNotBlank)?.let { append("\n- 当前音轨标题（日文）：$it") }
        c.trackTitleChinese.takeIf(String::isNotBlank)?.let { append("\n- 当前音轨标题（中文）：$it") }
        c.circle.takeIf(String::isNotBlank)?.let { append("\n- 社团：$it") }
        c.cv.takeIf(String::isNotBlank)?.let { append("\n- 声优：$it") }
        append("\n- 人名、身份、称呼必须与本上下文一致；同一角色在整轨及跨轨使用同一中文译名，禁止另造或混用。")
    }.trim()
}

internal fun buildDeepSeekTitleTranslationRequest(
    gson: Gson,
    albumTitle: String,
    circle: String,
    cv: String,
    trackTitles: List<Pair<Long, String>>,
    settings: DeepSeekTranslationSettings = DeepSeekTranslationSettings(),
    model: String = DEEPSEEK_SUBTITLE_MODEL,
    sendDeepSeekParams: Boolean = true
): String {
    require(albumTitle.isNotBlank())
    require(trackTitles.isNotEmpty())
    val userPayload = buildMap<String, Any> {
        put("work_title", albumTitle)
        put("circle", circle)
        put("cv", cv)
        put("tracks", trackTitles.mapIndexed { index, (trackId, title) ->
            mapOf("track_id" to trackId, "index" to index, "title" to title)
        })
    }
    return gson.toJson(
        DeepSeekChatRequest(
            model = model,
            messages = listOf(
                DeepSeekChatMessage(role = "system", content = displayNameTranslationSystemPrompt()),
                DeepSeekChatMessage(role = "user", content = gson.toJson(userPayload))
            ),
            thinking = DeepSeekThinking(type = if (settings.thinkingEnabled) "enabled" else "disabled")
                .takeIf { sendDeepSeekParams },
            reasoningEffort = settings.reasoningEffort.wireValue.takeIf {
                settings.thinkingEnabled && sendDeepSeekParams
            },
            responseFormat = DeepSeekResponseFormat().takeIf { sendDeepSeekParams }
        )
    )
}

internal fun parseDisplayNameTranslationResponse(
    content: String,
    expectedTrackIds: List<Long>
): DisplayNameTranslationResult {
    require(expectedTrackIds.isNotEmpty())
    val root = parseTranslationContentJsonObject(content)
    val workTitle = runCatching { root.get("work_title").asString.trim() }
        .getOrElse { throw IllegalArgumentException("缺少 work_title", it) }
    require(workTitle.isNotEmpty()) { "作品标题不能为空" }
    val tracks = runCatching { root.getAsJsonArray("tracks") }
        .getOrElse { throw IllegalArgumentException("缺少 tracks 数组", it) }
        ?: throw IllegalArgumentException("缺少 tracks 数组")
    val expectedSet = expectedTrackIds.toSet()
    val parsed = LinkedHashMap<Long, String>(tracks.size())
    tracks.forEach { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("音轨翻译项不是对象")
        val trackId = runCatching { item.get("track_id").asLong }
            .getOrElse { throw IllegalArgumentException("音轨翻译项缺少 track_id", it) }
        val title = runCatching { item.get("title").asString.trim() }
            .getOrElse { throw IllegalArgumentException("音轨翻译项缺少 title", it) }
        if (trackId !in expectedSet) return@forEach
        require(title.isNotEmpty()) { "音轨标题不能为空：track_id=$trackId" }
        require(parsed.put(trackId, title) == null) { "音轨翻译索引重复：track_id=$trackId" }
    }
    val missing = expectedTrackIds.filterNot { it in parsed }
    require(missing.isEmpty()) { "音轨翻译索引不匹配：缺少 ${missing.joinToString(",")}" }
    return DisplayNameTranslationResult(
        albumTitle = workTitle,
        trackTitles = expectedTrackIds.associateWith { parsed.getValue(it) }
    )
}

private fun displayNameTranslationSystemPrompt(): String =
    TranslationPrompts.displayNameSystemPrompt()

internal fun buildDeepSeekSubtitleTranslationRequest(
    gson: Gson,
    sources: List<GeneratedSubtitleSource>,
    allowMerging: Boolean,
    confirmedCaptions: List<GeneratedSubtitleCaption> = emptyList(),
    workContext: SubtitleWorkContext? = null,
    scriptContext: SubtitleScriptContext? = null,
    settings: DeepSeekTranslationSettings = DeepSeekTranslationSettings()
): String {
    val messages = buildSubtitleAgentInitialMessages(
        gson = gson,
        sources = sources,
        allowMerging = allowMerging,
        confirmedCaptions = confirmedCaptions,
        workContext = workContext,
        scriptContext = scriptContext
    )
    return buildDeepSeekSubtitleAgentRequest(gson, messages, settings, scriptContext)
}

internal fun buildSubtitleAgentInitialMessages(
    gson: Gson,
    sources: List<GeneratedSubtitleSource>,
    allowMerging: Boolean,
    confirmedCaptions: List<GeneratedSubtitleCaption> = emptyList(),
    workContext: SubtitleWorkContext? = null,
    scriptContext: SubtitleScriptContext? = null
): List<DeepSeekChatMessage> {
    require(sources.isNotEmpty())
    val confirmedSourceCount = confirmedCaptions.sumOf { it.sourceIndices.size }
    require(confirmedSourceCount < sources.size)
    val targetIndices = sources.map(GeneratedSubtitleSource::index)
    val userPayload = mapOf(
        "task" to "translate_current_audio_track_subtitles",
        "source_count" to sources.size,
        "completed_source_count" to confirmedSourceCount,
        "message" to TranslationPrompts.subtitleInitialUserMessage()
    )
    return listOf(
        DeepSeekChatMessage(
            role = "system",
            content = subtitleToolTranslationSystemPrompt(targetIndices, allowMerging, workContext, scriptContext)
        ),
        DeepSeekChatMessage(
            role = "user",
            content = gson.toJson(userPayload)
        )
    )
}

internal fun buildDeepSeekSubtitleAgentRequest(
    gson: Gson,
    messages: List<DeepSeekChatMessage>,
    settings: DeepSeekTranslationSettings = DeepSeekTranslationSettings(),
    scriptContext: SubtitleScriptContext? = null,
    model: String = DEEPSEEK_SUBTITLE_MODEL,
    sendDeepSeekParams: Boolean = true
): String {
    require(messages.isNotEmpty())
    return gson.toJson(
        DeepSeekChatRequest(
            model = model,
            messages = messages,
            thinking = DeepSeekThinking(
                type = if (settings.thinkingEnabled) "enabled" else "disabled"
            ).takeIf { sendDeepSeekParams },
            reasoningEffort = settings.reasoningEffort.wireValue.takeIf {
                settings.thinkingEnabled && sendDeepSeekParams
            },
            responseFormat = null,
            tools = subtitleTranslationTools(scriptContext)
        )
    )
}

internal fun buildPolishAgentInitialMessages(
    gson: Gson,
    tracks: List<PolishTrackInput>,
    workContext: SubtitleWorkContext? = null
): List<DeepSeekChatMessage> {
    require(tracks.isNotEmpty())
    val trackCount = tracks.size
    val captionCount = tracks.sumOf { it.captions.size }
    val systemPrompt = TranslationPrompts.subtitlePolishSystemPromptTemplate()
        .replace("{{TRACK_COUNT}}", trackCount.toString())
        .replace("{{CAPTION_COUNT}}", captionCount.toString())
        .replace("{{WORK_CONTEXT}}", buildSubtitleWorkContextSection(workContext))
        .replace("{{STYLE_GUIDE}}", TranslationPrompts.subtitleStyleGuide())
        .replace("{{REFERENCE_TABLE}}", TranslationPrompts.subtitleReferenceTable())
        .replace("{{READ_TOOL_NAME}}", POLISH_READ_TOOL_NAME)
        .replace("{{WRITE_TOOL_NAME}}", POLISH_WRITE_TOOL_NAME)
        .trim()
    val userPayload = mapOf(
        "task" to "polish_translated_chinese_subtitles",
        "track_count" to trackCount,
        "caption_count" to captionCount,
        "message" to TranslationPrompts.subtitlePolishInitialUserMessage()
    )
    return listOf(
        DeepSeekChatMessage(role = "system", content = systemPrompt),
        DeepSeekChatMessage(role = "user", content = gson.toJson(userPayload))
    )
}

internal fun buildPolishAgentRequest(
    gson: Gson,
    messages: List<DeepSeekChatMessage>,
    settings: DeepSeekTranslationSettings = DeepSeekTranslationSettings(),
    model: String = DEEPSEEK_SUBTITLE_MODEL,
    sendDeepSeekParams: Boolean = true
): String {
    require(messages.isNotEmpty())
    return gson.toJson(
        DeepSeekChatRequest(
            model = model,
            messages = messages,
            thinking = DeepSeekThinking(
                type = if (settings.thinkingEnabled) "enabled" else "disabled"
            ).takeIf { sendDeepSeekParams },
            reasoningEffort = settings.reasoningEffort.wireValue.takeIf {
                settings.thinkingEnabled && sendDeepSeekParams
            },
            responseFormat = null,
            tools = subtitlePolishTools()
        )
    )
}

/**
 * 润色 read 工具：按服务端维护的 offset 分页返回字幕条目；已精修的条目附带最新中文。
 */
internal fun buildPolishReadToolResultMessage(
    gson: Gson,
    toolCallId: String,
    tracks: List<PolishTrackInput>,
    polishedByCaptionId: Map<Long, String>,
    offset: Int,
    pageSize: Int = POLISH_READ_PAGE_SIZE
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    require(pageSize > 0)
    val allCaptions = tracks.flatMap(PolishTrackInput::captions)
    val trackSummaries = tracks.mapIndexed { index, track ->
        val polishedCount = track.captions.count { it.captionId in polishedByCaptionId }
        mapOf(
            "track_index" to index,
            "track_title_japanese" to track.trackTitleJapanese,
            "track_title_chinese" to track.trackTitleChinese,
            "caption_count" to track.captions.size,
            "polished_count" to polishedCount
        )
    }
    val page = allCaptions.drop(offset).take(pageSize)
    val result = buildMap<String, Any> {
        put("tracks", trackSummaries)
        put("offset", offset)
        put("page_size", pageSize)
        put(
            "subtitles",
            page.map { caption ->
                mapOf(
                    "caption_id" to caption.captionId,
                    "track_index" to tracks.indexOfFirst { track -> track.captions.any { it.captionId == caption.captionId } },
                    "japanese" to caption.japanese,
                    "chinese" to (polishedByCaptionId[caption.captionId] ?: caption.chinese)
                )
            }
        )
        put("completed", offset + page.size >= allCaptions.size)
    }
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

internal fun buildPolishWriteToolResultMessage(
    gson: Gson,
    toolCallId: String,
    results: List<PolishCaptionResult>,
    allCaptions: List<PolishCaptionInput>,
    polishedByCaptionId: Map<Long, String>
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    val remaining = allCaptions.count { it.captionId !in polishedByCaptionId }
    val result = buildMap<String, Any> {
        put("status", "written")
        put("written_count", results.size)
        put("remaining_unpolished_count", remaining)
        put("completed", remaining == 0)
    }
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

/** 解析润色 write 工具参数：只接受 caption_id + chinese，不允许改结构。 */
internal fun parsePolishWriteToolArguments(
    arguments: String,
    expectedCaptions: List<PolishCaptionInput>
): List<PolishCaptionResult> {
    require(expectedCaptions.isNotEmpty()) { "没有可润色的字幕" }
    val expectedById = expectedCaptions.associateBy(PolishCaptionInput::captionId)
    val root = runCatching {
        JsonParser.parseString(arguments.trim()).asJsonObject
    }.getOrElse { error ->
        throw IllegalArgumentException("润色工具调用参数不是有效 JSON 对象", error)
    }
    val captions = runCatching { root.getAsJsonArray("captions") }
        .getOrElse { throw IllegalArgumentException("captions 不是数组", it) }
        ?: throw IllegalArgumentException("缺少 captions 数组")
    val parsed = captions.map { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("润色条目不是对象")
        val captionId = runCatching { item.get("caption_id").asLong }
            .getOrElse { throw IllegalArgumentException("润色条目缺少 caption_id", it) }
        require(captionId in expectedById) { "润色条目引用了未知 caption_id：$captionId" }
        val chinese = runCatching { item.get("chinese").asString.trim() }
            .getOrElse { throw IllegalArgumentException("润色条目缺少 chinese", it) }
        require(chinese.isNotEmpty()) { "润色 chinese 不能为空" }
        PolishCaptionResult(captionId = captionId, chinese = chinese)
    }
    require(parsed.map(PolishCaptionResult::captionId).distinct().size == parsed.size) {
        "润色条目 caption_id 不能重复"
    }
    return parsed
}

private fun subtitlePolishTools(): List<DeepSeekToolDefinition> = listOf(
    DeepSeekToolDefinition(
        function = DeepSeekFunctionDefinition(
            name = POLISH_READ_TOOL_NAME,
            description = TranslationPrompts.subtitlePolishToolReadDescription(),
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "additionalProperties" to false
            )
        )
    ),
    DeepSeekToolDefinition(
        function = DeepSeekFunctionDefinition(
            name = POLISH_WRITE_TOOL_NAME,
            description = TranslationPrompts.subtitlePolishToolWriteDescription(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "captions" to mapOf(
                        "type" to "array",
                        "minItems" to 1,
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "caption_id" to mapOf("type" to "integer"),
                                "chinese" to mapOf("type" to "string", "minLength" to 1)
                            ),
                            "required" to listOf("caption_id", "chinese"),
                            "additionalProperties" to false
                        )
                    )
                ),
                "required" to listOf("captions"),
                "additionalProperties" to false
            )
        )
    )
)

internal fun buildSubtitleReadToolResultMessage(
    gson: Gson,
    toolCallId: String,
    sources: List<GeneratedSubtitleSource>,
    confirmedCaptions: List<GeneratedSubtitleCaption>,
    windowSize: Int = SUBTITLE_READ_WINDOW_SIZE
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    require(windowSize > 0)
    val confirmedSourceCount = confirmedCaptions.sumOf { it.sourceIndices.size }
    require(confirmedSourceCount in 0..sources.size)
    val nextSource = sources.getOrNull(confirmedSourceCount)
    // 窗口化：只回传从已确认位置起的下一窗日文字幕，已完成字幕也只回传最近一窗，
    // 把单轮请求体积钉死，避免循环累积导致 Groq 等服务返回 HTTP 413。
    // windowSize 由调用方在收到 413 时逐级下调（见 SUBTITLE_READ_WINDOW_STEPS）。
    val windowStart = confirmedSourceCount
    val windowEnd = (confirmedSourceCount + windowSize).coerceAtMost(sources.size)
    val result = buildMap<String, Any> {
        put(
            "japanese_subtitles",
            sources.subList(windowStart, windowEnd).map { source ->
                mapOf(
                    "index" to source.index,
                    "start_ms" to source.startMs,
                    "end_ms" to source.endMs,
                    "japanese" to source.text
                )
            }
        )
        put(
            "completed_chinese_subtitles",
            confirmedCaptions.takeLast(windowSize).map { caption ->
                mapOf(
                    "source_indices" to caption.sourceIndices,
                    "start_ms" to caption.startMs,
                    "end_ms" to caption.endMs,
                    "japanese" to caption.correctedJapanese,
                    "chinese" to caption.chineseText
                )
            }
        )
        put("completed_source_count", confirmedSourceCount)
        put("remaining_source_count", sources.size - confirmedSourceCount)
        put("completed", nextSource == null)
        nextSource?.let {
            put("next_untranslated_index", it.index)
            put("next_action", TranslationPrompts.subtitleProgressInstruction())
        }
    }
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

/**
 * 裁剪 agent 对话历史，避免长音轨多轮翻译后请求体无限膨胀触发 Groq 等服务的 HTTP 413。
 * 始终保留 system + 初始 user，仅保留末尾 [keepBlocks] 个「assistant↔tool」轮次块，其余旧消息丢弃。
 * 每个块从一条 assistant 消息起、到其后续 tool/user 消息止，保证 tool_call 与 tool_result 始终成对，
 * 不破坏 OpenAI 消息格式；read 工具仍按已确认位置回传当前窗口，故裁剪不影响进度推进。
 */
private fun trimAgentHistory(
    messages: List<DeepSeekChatMessage>,
    keepBlocks: Int = AGENT_HISTORY_KEEP_BLOCKS
): List<DeepSeekChatMessage> {
    if (messages.size <= 2) return messages
    val head = messages.take(2) // system + 初始 user
    val tail = messages.drop(2)
    val blocks = mutableListOf<MutableList<DeepSeekChatMessage>>()
    var cur = mutableListOf<DeepSeekChatMessage>()
    for (m in tail) {
        if (m.role == "assistant" && cur.isNotEmpty()) {
            blocks += cur
            cur = mutableListOf()
        }
        cur += m
    }
    if (cur.isNotEmpty()) blocks += cur
    if (blocks.size <= keepBlocks) return messages
    return head + blocks.takeLast(keepBlocks).flatten()
}

/**
 * 把模型本轮返回的 assistant 消息压缩后再回传，避免把模型一次写出的大量字幕原样塞回
 * 下一轮请求体（Groq 等服务对单次请求体积有上限，整段回传会触发 HTTP 413）。
 * - 翻译/润色的 write 工具调用参数只保留条数摘要（字幕已落进本地 confirmed 列表，模型无需回看全文）；
 * - 一并清掉 reasoning_content（思维链无需回传，且可能很长）。
 * tool_call id 保持不变，以与对应的 tool result 配对，不破坏 OpenAI 消息格式。
 * 模型每轮重新 read 当前窗口，故裁剪其历史输出不影响进度推进。
 */
private fun sanitizeAssistantMessageForHistory(message: DeepSeekChatMessage): DeepSeekChatMessage {
    val sanitizedCalls = message.toolCalls?.map { call ->
        when (call.function.name) {
            SUBTITLE_WRITE_TOOL_NAME -> {
                val written = runCatching {
                    JsonParser.parseString(call.function.arguments.orEmpty()).asJsonObject
                        .getAsJsonArray("captions")?.size() ?: 0
                }.getOrDefault(0)
                call.copy(function = call.function.copy(arguments = """{"captions_written":$written}"""))
            }

            POLISH_WRITE_TOOL_NAME -> {
                val written = runCatching {
                    JsonParser.parseString(call.function.arguments.orEmpty()).asJsonObject
                        .getAsJsonArray("captions")?.size() ?: 0
                }.getOrDefault(0)
                call.copy(function = call.function.copy(arguments = """{"captions_written":$written}"""))
            }

            else -> call
        }
    }
    // content 一并截断：模型未走工具调用、而把整段文本塞进 content 时，同样会被回传撑爆请求体。
    val cappedContent = message.content?.let { content ->
        if (content.length > MAX_ASSISTANT_ECHO_CHARS) {
            content.take(MAX_ASSISTANT_ECHO_CHARS) + "…[已截断]"
        } else {
            content
        }
    }
    return message.copy(toolCalls = sanitizedCalls, content = cappedContent, reasoningContent = null)
}

private const val MAX_ASSISTANT_ECHO_CHARS = 2000

internal fun buildSubtitleWriteToolResultMessage(
    gson: Gson,
    toolCallId: String,
    writtenCaptionCount: Int,
    writtenSourceCount: Int,
    confirmedSourceCount: Int,
    sources: List<GeneratedSubtitleSource>
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    require(writtenCaptionCount > 0)
    require(writtenSourceCount > 0)
    require(confirmedSourceCount in writtenSourceCount..sources.size)
    val nextSource = sources.getOrNull(confirmedSourceCount)
    val result = buildMap<String, Any> {
        put("status", "written")
        put("written_caption_count", writtenCaptionCount)
        put("written_source_count", writtenSourceCount)
        put("completed_source_count", confirmedSourceCount)
        put("remaining_source_count", sources.size - confirmedSourceCount)
        put("completed", nextSource == null)
        nextSource?.let {
            put("next_untranslated_index", it.index)
            put("next_action", TranslationPrompts.subtitleProgressInstruction())
        }
    }
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

internal fun buildSubtitleToolErrorMessage(
    gson: Gson,
    toolCallId: String,
    message: String
): DeepSeekChatMessage = DeepSeekChatMessage(
    role = "tool",
    content = gson.toJson(
        mapOf(
            "status" to "error",
            "message" to message,
            "instruction" to TranslationPrompts.subtitleToolErrorInstruction()
        )
    ),
    toolCallId = toolCallId
)

internal data class ScriptReadToolArgs(
    val fileIndex: Int,
    val offset: Int,
    val limit: Int
)

internal fun parseScriptReadToolArguments(arguments: String, fileCount: Int): ScriptReadToolArgs {
    require(fileCount > 0) { "作品目录没有台本文件" }
    val root = runCatching {
        JsonParser.parseString(arguments.trim()).asJsonObject
    }.getOrElse { error ->
        throw IllegalArgumentException("台本读取工具参数不是有效 JSON 对象", error)
    }
    val fileIndex = runCatching { root.get("file_index").asInt }
        .getOrElse { error -> throw IllegalArgumentException("台本读取工具缺少 file_index", error) }
    require(fileIndex in 0 until fileCount) { "file_index 超出范围：$fileIndex" }
    val offset = runCatching { root.get("offset").asInt }.getOrDefault(0).coerceAtLeast(0)
    val limit = runCatching { root.get("limit").asInt }
        .getOrDefault(SCRIPT_READ_DEFAULT_LIMIT)
        .coerceIn(1, SCRIPT_READ_MAX_LIMIT)
    return ScriptReadToolArgs(fileIndex = fileIndex, offset = offset, limit = limit)
}

internal fun buildScriptListToolResultMessage(
    gson: Gson,
    toolCallId: String,
    context: SubtitleScriptContext
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    val result = mapOf(
        "files" to context.files.map { file ->
            mapOf("index" to file.index, "name" to file.name)
        },
        "total_files" to context.files.size
    )
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

internal suspend fun buildScriptReadToolResultMessage(
    gson: Gson,
    toolCallId: String,
    context: SubtitleScriptContext,
    fileIndex: Int,
    offset: Int,
    limit: Int
): DeepSeekChatMessage {
    require(toolCallId.isNotBlank())
    val file = context.files.getOrNull(fileIndex)
    if (file == null) {
        return buildSubtitleToolErrorMessage(gson, toolCallId, "未知台本文件索引：$fileIndex")
    }
    val read = context.reader.read(fileIndex, offset, limit)
    if (read == null) {
        return buildSubtitleToolErrorMessage(gson, toolCallId, "无法读取台本文件：${file.name}")
    }
    val result = mapOf(
        "file_index" to read.fileIndex,
        "name" to read.name,
        "offset" to read.offset,
        "total_chars" to read.totalChars,
        "content" to read.content,
        "truncated" to read.truncated,
        "completed" to (read.offset + read.content.length >= read.totalChars)
    )
    return DeepSeekChatMessage(
        role = "tool",
        content = gson.toJson(result),
        toolCallId = toolCallId
    )
}

private fun subtitleTranslationTools(scriptContext: SubtitleScriptContext?): List<DeepSeekToolDefinition> {
    val tools = mutableListOf(
        DeepSeekToolDefinition(
            function = DeepSeekFunctionDefinition(
                name = SUBTITLE_READ_TOOL_NAME,
                description = TranslationPrompts.subtitleToolReadDescription(),
                parameters = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "additionalProperties" to false
                )
            )
        ),
        DeepSeekToolDefinition(
            function = DeepSeekFunctionDefinition(
                name = SUBTITLE_WRITE_TOOL_NAME,
                description = TranslationPrompts.subtitleToolWriteDescription(),
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "captions" to mapOf(
                            "type" to "array",
                            "minItems" to 1,
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "source_indices" to mapOf(
                                        "type" to "array",
                                        "minItems" to 1,
                                        "items" to mapOf("type" to "integer")
                                    ),
                                    "start_ms" to mapOf(
                                        "type" to "integer",
                                        "minimum" to 0
                                    ),
                                    "end_ms" to mapOf(
                                        "type" to "integer",
                                        "minimum" to 0
                                    ),
                                    "japanese" to mapOf(
                                        "type" to "string",
                                        "minLength" to 1
                                    ),
                                    "chinese" to mapOf(
                                        "type" to "string",
                                        "minLength" to 1,
                                        "description" to TranslationPrompts.subtitleToolChineseFieldDescription()
                                    )
                                ),
                                "required" to listOf(
                                    "source_indices",
                                    "start_ms",
                                    "end_ms",
                                    "japanese",
                                    "chinese"
                                ),
                                "additionalProperties" to false
                            )
                        )
                    ),
                    "required" to listOf("captions"),
                    "additionalProperties" to false
                )
            )
        )
    )
    if (scriptContext != null && scriptContext.files.isNotEmpty()) {
        tools += scriptTranslationTools()
    }
    return tools
}

private fun scriptTranslationTools(): List<DeepSeekToolDefinition> = listOf(
    DeepSeekToolDefinition(
        function = DeepSeekFunctionDefinition(
            name = SCRIPT_LIST_TOOL_NAME,
            description = TranslationPrompts.subtitleScriptListToolDescription(),
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "additionalProperties" to false
            )
        )
    ),
    DeepSeekToolDefinition(
        function = DeepSeekFunctionDefinition(
            name = SCRIPT_READ_TOOL_NAME,
            description = TranslationPrompts.subtitleScriptReadToolDescription(),
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_index" to mapOf("type" to "integer", "minimum" to 0),
                    "offset" to mapOf("type" to "integer", "minimum" to 0),
                    "limit" to mapOf("type" to "integer", "minimum" to 1)
                ),
                "required" to listOf("file_index"),
                "additionalProperties" to false
            )
        )
    )
)

private fun subtitleAgentContinueMessage(finishReason: String?): String =
    if (finishReason == "length") {
        TranslationPrompts.subtitleContinueMessageLengthLimited()
    } else {
        TranslationPrompts.subtitleContinueMessageGeneric()
    }

internal suspend fun <T> retrySubtitleTranslation(
    maxAttempts: Int = 4,
    onAttempt: suspend (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    onRetry: suspend (nextAttempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> },
    delayProvider: suspend (Long) -> Unit = { delay(it) },
    operation: suspend (previousError: SubtitleTranslationException?) -> T
): T {
    require(maxAttempts > 0)
    var lastError: SubtitleTranslationException? = null
    for (attempt in 1..maxAttempts) {
        onAttempt(attempt, maxAttempts)
        try {
            return operation(lastError)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SubtitleTranslationException) {
            lastError = error
            if (!error.retryable || attempt == maxAttempts) throw error
            val exponentialDelay = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1))
            onRetry(attempt + 1, maxAttempts, error.message.orEmpty().ifBlank { "未知原因" })
            delayProvider(maxOf(exponentialDelay, error.retryAfterMs ?: 0L))
        }
    }
    throw checkNotNull(lastError)
}

internal fun parseSubtitleWriteToolArguments(
    arguments: String,
    expectedRemainingSources: List<GeneratedSubtitleSource>,
    allowMerging: Boolean
): List<GeneratedSubtitleCaption> {
    require(expectedRemainingSources.isNotEmpty()) { "全部字幕已完成，无需继续写入" }
    val root = runCatching {
        JsonParser.parseString(arguments.trim()).asJsonObject
    }.getOrElse { error ->
        val reason = if (arguments.trimStart().startsWith('{') && !arguments.trimEnd().endsWith('}')) {
            "字幕工具调用参数被截断，未形成完整 JSON 对象"
        } else {
            "字幕工具调用参数不是有效 JSON 对象"
        }
        throw IllegalArgumentException(reason, error)
    }
    val captions = runCatching { root.getAsJsonArray("captions") }
        .getOrElse { throw IllegalArgumentException("captions 不是数组", it) }
        ?: throw IllegalArgumentException("缺少 captions 数组")
    val parsed = captions.map { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("字幕组不是对象")
        val sourceIndices = runCatching {
            item.getAsJsonArray("source_indices").map { it.asInt }
        }.getOrElse { throw IllegalArgumentException("字幕组缺少 source_indices", it) }
        require(sourceIndices.isNotEmpty()) { "字幕组的 source_indices 不能为空" }
        val startMs = runCatching { item.get("start_ms").asLong }
            .getOrElse { throw IllegalArgumentException("字幕组缺少 start_ms", it) }
        val endMs = runCatching { item.get("end_ms").asLong }
            .getOrElse { throw IllegalArgumentException("字幕组缺少 end_ms", it) }
        val correctedJapanese = runCatching { item.get("japanese").asString.trim() }
            .getOrElse { throw IllegalArgumentException("字幕组缺少 japanese", it) }
        val chineseText = runCatching { item.get("chinese").asString.trim() }
            .getOrElse { throw IllegalArgumentException("字幕组缺少 chinese", it) }
        GeneratedSubtitleCaption(
            sourceIndices = sourceIndices,
            startMs = startMs,
            endMs = endMs,
            correctedJapanese = correctedJapanese,
            chineseText = chineseText
        )
    }
    return validateSubtitleCaptionBatch(
        captions = parsed,
        expectedRemainingSources = expectedRemainingSources,
        allowMerging = allowMerging
    )
}

private fun parseTranslationContentJsonObject(content: String) = content.trim()
    .removePrefix("```json")
    .removePrefix("```JSON")
    .removePrefix("```")
    .removeSuffix("```")
    .trim()
    .let { trimmed ->
        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        require(objectStart >= 0 && objectEnd > objectStart) { "响应正文中没有 JSON 对象" }
        runCatching {
            JsonParser.parseString(trimmed.substring(objectStart, objectEnd + 1)).asJsonObject
        }.getOrElse { throw IllegalArgumentException("无法解析 JSON", it) }
    }

internal fun subtitleToolTranslationSystemPrompt(
    targetIndices: List<Int>,
    allowMerging: Boolean,
    workContext: SubtitleWorkContext? = null,
    scriptContext: SubtitleScriptContext? = null
): String {
    require(targetIndices.isNotEmpty())
    require(targetIndices.distinct().size == targetIndices.size)
    val segmentationRules = if (allowMerging) {
        TranslationPrompts.subtitleSegmentationRulesMerge()
    } else {
        TranslationPrompts.subtitleSegmentationRulesNoMerge()
    }
    val scriptToolsSection = if (scriptContext != null && scriptContext.files.isNotEmpty()) {
        subtitleScriptToolsSection()
    } else {
        ""
    }
    return TranslationPrompts.subtitleAgentSystemPromptTemplate()
        .replace("{{SOURCE_COUNT}}", targetIndices.size.toString())
        .replace("{{WORK_CONTEXT}}", buildSubtitleWorkContextSection(workContext))
        .replace("{{READ_TOOL_NAME}}", SUBTITLE_READ_TOOL_NAME)
        .replace("{{WRITE_TOOL_NAME}}", SUBTITLE_WRITE_TOOL_NAME)
        .replace("{{SCRIPT_TOOLS}}", scriptToolsSection)
        .replace("{{STYLE_GUIDE}}", TranslationPrompts.subtitleStyleGuide())
        .replace("{{REFERENCE_TABLE}}", TranslationPrompts.subtitleReferenceTable())
        .replace("{{SEGMENTATION_RULES}}", segmentationRules)
        .trim()
}

internal fun subtitleScriptToolsSection(): String =
    TranslationPrompts.subtitleScriptToolsSection()
        .replace("{{SCRIPT_LIST_TOOL_NAME}}", SCRIPT_LIST_TOOL_NAME)
        .replace("{{SCRIPT_READ_TOOL_NAME}}", SCRIPT_READ_TOOL_NAME)

private const val BASE_RETRY_DELAY_MS = 1_000L
