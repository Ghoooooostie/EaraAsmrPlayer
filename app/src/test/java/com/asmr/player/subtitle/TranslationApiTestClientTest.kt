package com.asmr.player.subtitle

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationApiTestClientTest {
    private val gson = Gson()

    @Test
    fun request_buildsMinimalChatCompletion_withoutDeepSeekParams() {
        val json = JsonParser.parseString(buildTranslationApiTestRequestJson(gson, "mock-model")).asJsonObject
        assertEquals("mock-model", json.get("model").asString)
        assertEquals(16, json.get("max_tokens").asInt)
        assertEquals(false, json.get("stream").asBoolean)
        val messages = json.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
        assertTrue(!json.has("thinking"))
        assertTrue(!json.has("reasoning_effort"))
        assertTrue(!json.has("tools"))
        assertTrue(!json.has("response_format"))
    }

    @Test
    fun classify_200WithChoices_isSuccess() {
        val outcome = classifyTranslationApiTestResponse(
            200,
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        )
        assertEquals(TranslationApiTestOutcome.Success, outcome)
    }

    @Test
    fun classify_200WithoutChoices_isFormatFailure() {
        val outcome = classifyTranslationApiTestResponse(200, """{"usage":{}}""")
        assertTrue(outcome is TranslationApiTestOutcome.Failure)
        assertTrue((outcome as TranslationApiTestOutcome.Failure).message.contains("格式"))
    }

    @Test
    fun classify_200InvalidJson_isFormatFailure() {
        val outcome = classifyTranslationApiTestResponse(200, "not-json")
        assertTrue(outcome is TranslationApiTestOutcome.Failure)
        assertTrue((outcome as TranslationApiTestOutcome.Failure).message.contains("格式"))
    }

    @Test
    fun classify_401_pointsToApiKey() {
        val outcome = classifyTranslationApiTestResponse(401, """{"error":{"message":"Invalid api key"}}""")
        assertTrue(outcome is TranslationApiTestOutcome.Failure)
        val message = (outcome as TranslationApiTestOutcome.Failure).message
        assertTrue(message.contains("API Key"))
        assertTrue(message.contains("Invalid api key"))
    }

    @Test
    fun classify_404_pointsToUrlOrModel() {
        val outcome = classifyTranslationApiTestResponse(404, "")
        assertTrue(outcome is TranslationApiTestOutcome.Failure)
        val message = (outcome as TranslationApiTestOutcome.Failure).message
        assertTrue(message.contains("模型名"))
        assertTrue(message.contains("404"))
    }

    @Test
    fun classify_429_mentionsRateLimit() {
        val outcome = classifyTranslationApiTestResponse(429, "")
        assertTrue(outcome is TranslationApiTestOutcome.Failure)
        assertTrue((outcome as TranslationApiTestOutcome.Failure).message.contains("速率限制"))
    }

    @Test
    fun test_sendsBearerKey_andReportsSuccess() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        server.start()
        try {
            val outcome = runTranslationApiTest(
                okHttpClient = OkHttpClient(),
                gson = gson,
                apiUrl = server.url("/v1/chat/completions").toString(),
                apiKey = "sk-test",
                model = "mock-model"
            )
            assertEquals(TranslationApiTestOutcome.Success, outcome)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
            assertEquals(
                "mock-model",
                JsonParser.parseString(recorded.body.readUtf8()).asJsonObject.get("model").asString
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun test_server401_reportsFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.start()
        try {
            val outcome = runTranslationApiTest(
                okHttpClient = OkHttpClient(),
                gson = gson,
                apiUrl = server.url("/v1/chat/completions").toString(),
                apiKey = "sk-bad",
                model = "mock-model"
            )
            assertTrue(outcome is TranslationApiTestOutcome.Failure)
            assertTrue((outcome as TranslationApiTestOutcome.Failure).message.contains("bad key"))
        } finally {
            server.shutdown()
        }
    }
}
