package com.asmr.player.subtitle

import com.asmr.player.data.settings.DeepSeekTranslationSettings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实字幕（设备 DB 导出的 437 条日文源，见 test/resources/groq_sim_sources.json）
 * 加仿真 Groq 端点，模拟完整翻译 agent 路程：
 *
 * 1. gpt-oss-20b 当前档位（TPM 800 / TPD 20000）——复现线上 413 失败，验证快速失败与可行动提示；
 * 2. 每日配额耗尽（429 TPD）——验证不再无意义退避重试；
 * 3. 健康档位（如切换 gpt-oss-120b）——验证 agent 路程能全程走完。
 *
 * 仿真端点的行为对齐 Groq 实测报文（错误文案取自设备 logcat 的原文格式）：
 * - 413: Request too large for model `m` ... on tokens per minute (TPM): Limit 800
 * - 429: Rate limit reached for model `m` ... on tokens per day (TPD): Limit 20000
 */
private val simGson = Gson()

/** 仿真模型：驱动真实 agent 循环。 */
private class SimFakeModel {

    fun respond(requestBody: JsonObject): JsonObject {
        val messages = requestBody.getAsJsonArray("messages")
        var lastTool: JsonObject? = null
        for (i in messages.size() - 1 downTo 0) {
            val m = messages.get(i).asJsonObject
            if (m.get("role").asString == "tool") {
                lastTool = m
                break
            }
        }
        val content = lastTool?.get("content")?.asString.orEmpty()
        return when {
            content.contains("japanese_subtitles") -> toolCallResponse(writeCall(JsonParser.parseString(content).asJsonObject))
            content.contains("\"written\"") -> {
                val remaining = JsonParser.parseString(content).asJsonObject.get("remaining_source_count").asInt
                if (remaining > 0) toolCallResponse(readCall()) else finalResponse()
            }
            else -> toolCallResponse(readCall())
        }
    }

    private fun readCall(): JsonObject {
        val call = JsonObject()
        call.addProperty("id", "call_read_${counter++}")
        call.addProperty("type", "function")
        call.add(
            "function",
            JsonObject().apply {
                addProperty("name", SUBTITLE_READ_TOOL_NAME)
                addProperty("arguments", "{}")
            }
        )
        return call
    }

    private fun writeCall(readResult: JsonObject): JsonObject {
        val captions = JsonArray()
        readResult.getAsJsonArray("japanese_subtitles").forEach { element ->
            val src = element.asJsonObject
            captions.add(
                JsonObject().apply {
                    add("source_indices", JsonArray().apply { add(src.get("index").asInt) })
                    addProperty("start_ms", src.get("start_ms").asLong)
                    addProperty("end_ms", src.get("end_ms").asLong)
                    addProperty("japanese", src.get("japanese").asString)
                    addProperty("chinese", "[译]" + src.get("japanese").asString)
                }
            )
        }
        val args = JsonObject().apply { add("captions", captions) }.toString()
        val call = JsonObject()
        call.addProperty("id", "call_write_${counter++}")
        call.addProperty("type", "function")
        call.add(
            "function",
            JsonObject().apply {
                addProperty("name", SUBTITLE_WRITE_TOOL_NAME)
                addProperty("arguments", args)
            }
        )
        return call
    }

    private fun toolCallResponse(call: JsonObject): JsonObject {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", "")
            add("tool_calls", JsonArray().apply { add(call) })
        }
        return simChatResponse(message, "tool_calls")
    }

    private fun finalResponse(): JsonObject {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", "全部完成")
        }
        return simChatResponse(message, "stop")
    }

    private var counter = 0
}

private fun simChatResponse(message: JsonObject, finishReason: String): JsonObject {
    val choice = JsonObject().apply {
        add("message", message)
        addProperty("finish_reason", finishReason)
    }
    return JsonObject().apply {
        add("choices", JsonArray().apply { add(choice) })
        add("usage", JsonObject().apply { addProperty("total_tokens", 100) })
    }
}

/** 仿真 Groq 限速行为 + 伪模型。token 估算按实测校准：初始请求真实 8010 tokens ↔ 约 50KB body → bytes/6。 */
private class SimFakeGroqEndpoint(
    private val tpmLimit: Int,
    private val tpdLimit: Long,
    private val fakeModel: SimFakeModel = SimFakeModel()
) : Interceptor {

    val turnLog = mutableListOf<String>()
    var lastRaw413: String? = null
        private set
    var tpdUsed = 0L
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        val estTokens = body.toByteArray(Charsets.UTF_8).size / 6
        val turn = turnLog.size + 1

        fun respond(code: Int, payload: String): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Rejected")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()

        // TPM：单请求输入 token 超过每分钟限额 → 413（真实文案格式）
        if (estTokens > tpmLimit) {
            turnLog += "turn#$turn 413-TPM estTokens=$estTokens > limit=$tpmLimit requestKB=${body.length / 1024}"
            val payload =
                """{"error":{"message":"Request too large for model `openai/gpt-oss-20b` in organization `org_sim` """ +
                    """"service tier `on_demand` on tokens per minute (TPM): Limit $tpmLimit. """ +
                    """"Please try again in 2.5m.","type":"request_too_large"}}"""
            lastRaw413 = payload
            return respond(413, payload)
        }
        // TPD：当日累计 token 超过每日限额 → 429（真实文案格式）
        if (tpdUsed + estTokens > tpdLimit) {
            turnLog += "turn#$turn 429-TPD used=$tpdUsed + $estTokens > limit=$tpdLimit"
            return respond(
                429,
                """{"error":{"message":"Rate limit reached for model `openai/gpt-oss-20b` """ +
                    """in organization `org_sim` service tier `on_demand` on tokens per day (TPD): """ +
                    """Limit $tpdLimit. Please try again in 9h55m21s.","type":"rate_limit_exceeded"}}"""
            )
        }
        tpdUsed += estTokens
        turnLog += "turn#$turn 200 estTokens=$estTokens requestKB=${body.length / 1024} tpdUsed=$tpdUsed"
        return respond(200, simGson.toJson(fakeModel.respond(JsonParser.parseString(body).asJsonObject)))
    }
}

class SubtitleGroqSimulationTest {

    private fun loadSources(): List<GeneratedSubtitleSource> {
        val stream = javaClass.getResourceAsStream("/groq_sim_sources.json")
        assertNotNull("缺少 fixture groq_sim_sources.json", stream)
        val array = JsonParser.parseString(stream!!.bufferedReader(Charsets.UTF_8).readText()).asJsonArray
        return array.map { item ->
            GeneratedSubtitleSource(
                index = item.asJsonObject.get("sourceIndex").asInt,
                startMs = item.asJsonObject.get("startMs").asLong,
                endMs = item.asJsonObject.get("endMs").asLong,
                text = item.asJsonObject.get("text").asString
            )
        }
    }

    private fun newClient(endpoint: SimFakeGroqEndpoint, model: String = "openai/gpt-oss-20b"): SubtitleTranslationClient =
        SubtitleTranslationClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(endpoint).build(),
            gson = simGson,
            apiKey = "sim-key",
            settings = DeepSeekTranslationSettings(),
            apiUrl = "https://api.groq.com/openai/v1/chat/completions",
            model = model,
            sendDeepSeekParams = false
        )

    private fun printJourney(tag: String, endpoint: SimFakeGroqEndpoint) {
        println("======== $tag ========")
        endpoint.turnLog.forEach(::println)
        println("total turns=${endpoint.turnLog.size}, tpdUsed=${endpoint.tpdUsed}")
    }

    // ---------------- 场景 1：当前档位 TPM=800 ----------------

    @Test
    fun `gpt-oss-20b 当前档位 TPM800 单轮请求都塞不进去应快速失败并给出可行动提示`() = runBlocking {
        val endpoint = SimFakeGroqEndpoint(tpmLimit = 800, tpdLimit = 20_000)
        val client = newClient(endpoint)
        val error = runCatching {
            client.translateSubtitles(loadSources(), allowMerging = true) {}
        }.exceptionOrNull() as? SubtitleTranslationException

        assertNotNull("应抛出 SubtitleTranslationException", error)
        error!!
        printJourney("场景1: gpt-oss-20b TPM=800", endpoint)
        println("raw413=${endpoint.lastRaw413}")
        println("parsed=${parseRateLimitError(endpoint.lastRaw413.orEmpty(), 413)}")
        println("最终错误：${error.message}")
        assertFalse("TPM 物理塞不下不应标记为可重试", error.retryable)
        assertTrue(error.message.orEmpty().contains("TPM 800"))
        assertTrue(error.message.orEmpty().contains("openai/gpt-oss-120b"))
        // 修复后：第一次 413-TPM 跳到最小窗口重试一次，仍 413 即最终失败（共 2 轮请求），
        // 而不是 6 轮降级 × 4 次外层重试地白烧配额
        assertEquals(2, endpoint.turnLog.size)
    }

    // ---------------- 场景 2：每日配额耗尽 ----------------

    @Test
    fun `每日配额 TPD 耗尽应立即失败并说明重置时间`() = runBlocking {
        val endpoint = SimFakeGroqEndpoint(tpmLimit = 12_000, tpdLimit = 2_000)
        val client = newClient(endpoint)
        val error = runCatching {
            client.translateSubtitles(loadSources(), allowMerging = true) {}
        }.exceptionOrNull() as? SubtitleTranslationException

        assertNotNull(error)
        error!!
        assertFalse("每日配额耗尽不应标记为可重试", error.retryable)
        assertTrue(error.message.orEmpty().contains("每日 token 配额已用尽"))
        assertEquals(1, endpoint.turnLog.size)
        printJourney("场景2: TPD=2000 耗尽", endpoint)
        println("最终错误：${error.message}")
    }

    // ---------------- 场景 3：健康档位全程走完 ----------------

    @Test
    fun `健康档位如 gpt-oss-120b 应完整走完 437 条字幕的 agent 路程`() = runBlocking {
        val endpoint = SimFakeGroqEndpoint(tpmLimit = 12_000, tpdLimit = 50_000_000)
        val client = newClient(endpoint, model = "openai/gpt-oss-120b")
        val confirmed = mutableListOf<GeneratedSubtitleCaption>()
        val result = client.translateSubtitles(loadSources(), allowMerging = true) { confirmed += it }

        assertEquals(437, result.size)
        assertEquals(437, result.sumOf { it.sourceIndices.size })
        result.forEach { caption ->
            assertTrue(caption.chineseText.isNotBlank())
        }
        printJourney("场景3: 健康档位 TPM=12000", endpoint)
        println("完成：${result.size} 条字幕，共 ${endpoint.turnLog.size} 轮请求")
    }

    // ---------------- 解析函数单测 ----------------

    @Test
    fun parseRateLimitError_identifiesGroqTpm413AndTpd429() {
        val tpm = parseRateLimitError(
            """{"error":{"message":"Request too large for model `openai/gpt-oss-20b` in organization `org_x` """ +
                """service tier `on_demand` on tokens per minute (TPM): Limit 800. Please try again in 2.5m."}}""",
            statusCode = 413
        )
        assertEquals(SubtitleRateLimitKind.TPM, tpm?.kind)
        assertEquals(800, tpm?.limitTokens)

        val tpd = parseRateLimitError(
            "Rate limit reached for model `openai/gpt-oss-20b` in organization `org_x` " +
                "service tier `on_demand` on tokens per day (TPD): Limit 20000. Please try again in 9h55m21s.",
            statusCode = 429
        )
        assertEquals(SubtitleRateLimitKind.TPD, tpd?.kind)
        assertEquals(20000, tpd?.limitTokens)

        assertNull(parseRateLimitError("no rate limit here", statusCode = 413))
        assertNull(parseRateLimitError("tokens per minute (TPM): Limit 800", statusCode = 400))
    }

    @Test
    fun tpm413_视为瞬时限速可重试并标记降级() {
        val small = SubtitleRateLimitError(SubtitleRateLimitKind.TPM, limitTokens = 8_000, statusCode = 413)
        val error = small.toTranslationException(providerLabel = "自定义 AI")
        assertTrue(error.retryable)
        assertEquals(65_000L, error.retryAfterMs)
        assertTrue(error.payloadTooLarge)
        assertTrue(error.rateLimited)
        assertEquals(SubtitleRateLimitKind.TPM, error.rateLimitKind)
    }
}
