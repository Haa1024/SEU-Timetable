package com.seu.timetable.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientTest {
    @Test fun endpointAndValidation() {
        assertEquals("https://api.deepseek.com/chat/completions", AiConfig().endpoint().toString())
        assertEquals("https://example.com/v1/chat/completions", AiConfig(baseUrl = "https://example.com/v1/").endpoint().toString())
        for (config in listOf(AiConfig(baseUrl = "http://example.com"), AiConfig(baseUrl = "https://user:secret@example.com"), AiConfig(contextTurns = 0), AiConfig(maxTokens = 3), AiConfig(model = ""))) {
            assertTrue(runCatching { config.validated() }.isFailure)
        }
    }
    @Test fun contextAndPayloadDoNotIncludeToolsOrIncompleteReplies() {
        val image = ChatImage("a", "test")
        val history = listOf(ChatMessage(role = "user", text = "old"), ChatMessage(role = "assistant", text = "done"), ChatMessage(role = "user", text = "second"), ChatMessage(role = "assistant", text = "partial", status = "stopped"), ChatMessage(role = "user", images = listOf(image)))
        val config = AiConfig(contextTurns = 2)
        assertEquals(2, chatContext(history, 2).size)
        val body = Json.parseToJsonElement(AiClient().requestBody(config, history, { byteArrayOf(1, 2, 3) })).jsonObject
        assertFalse(body.containsKey("tools")); assertFalse(body.toString().contains("partial")); assertFalse(body.toString().contains("old"))
        assertEquals("data:image/jpeg;base64,AQID", body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(runCatching { AiClient().requestBody(config.copy(vision = false), history, { byteArrayOf() }) }.isFailure)
    }
    @Test fun windowBoundsIncludeSmallAndKeyboardReducedScreens() {
        for ((width, height) in listOf(384f to 720f, 360f to 650f, 430f to 800f, 384f to 330f)) {
            val limits = chatLimits(width, height)
            for (rect in listOf(ChatRect(-900f, -900f, 1f, 1f), ChatRect(9000f, 9000f, 9000f, 9000f))) {
                val result = rect.constrain(width, height)
                assertTrue(result.width in limits.minWidth..limits.maxWidth)
                assertTrue(result.height in limits.minHeight..limits.maxHeight)
                assertTrue(result.x >= 12 && result.x + result.width <= width - 12)
                assertTrue(result.y >= 0 && result.y + result.height <= height)
                assertTrue(result.height < height)
            }
        }
    }
    @Test fun streamsUnicodeAndCancelsNetworkOnStop() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val config = AiConfig(baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n\r\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").throttleBody(1, 1, TimeUnit.MILLISECONDS))
            val chunks = AiClient().chat(config, "secret", listOf(ChatMessage(role = "user", text = "hi")), { byteArrayOf() }).toList()
            assertEquals("你好", chunks.joinToString("") { it.text })
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"start\"}}]}\n\n".repeat(1000)).throttleBody(64, 50, TimeUnit.MILLISECONDS))
            withTimeout(2000) { AiClient().chat(config, "secret", listOf(ChatMessage(role = "user", text = "hi")), { byteArrayOf() }).take(1).collect() }
        } finally { server.shutdown() }
    }
    @Test fun httpErrorsAreReadableAndSecretsAreRedacted() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad secret-key\"}}"))
            val config = AiConfig(baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString())
            val result = runCatching { AiClient().chat(config, "secret-key", listOf(ChatMessage(role = "user", text = "hi")), { byteArrayOf() }).collect() }
            assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("API Key 无效")); assertFalse(result.exceptionOrNull()!!.message!!.contains("secret-key"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
            val truncated = runCatching { AiClient().chat(config, "secret-key", listOf(ChatMessage(role = "user", text = "hi")), { byteArrayOf() }).collect() }
            assertTrue(truncated.exceptionOrNull()!!.message!!.contains("提前断开"))
        } finally { server.shutdown() }
    }
}
