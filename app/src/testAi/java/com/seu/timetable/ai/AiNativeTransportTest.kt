package com.seu.timetable.ai

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.Base64

class AiNativeTransportTest {
    private fun prepared(url:String, key:String="test-key")=buildJsonObject {
        put("url",url);put("apiKey",key);put("body",buildJsonObject { put("model","test");put("stream",true);put("messages",JsonArray(emptyList())) })
    }.toString()
    @Test fun `stream retains bytes and authorization while forwarding no redirects`() {
        MockWebServer().use { server ->
            val events=LinkedBlockingQueue<JsonObject>()
            val transport=AiNativeTransport { _,event->events.add(event) }
            try {
                val sse="data: {\"choices\":[{\"delta\":{\"content\":\"中文课程\"}}]}\n\ndata: [DONE]\n\n"
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse))
                val localUrl=server.url("/chat/completions").newBuilder().host("127.0.0.1").build().toString()
                transport.request("one",prepared(localUrl))
                val header=events.poll(5,TimeUnit.SECONDS)
                assertFalse(header.toString(),header.containsKey("error"))
                assertEquals(200,header.getValue("status").jsonPrimitive.int)
                val chunks=java.io.ByteArrayOutputStream()
                while(true) {
                    val event=events.poll(5,TimeUnit.SECONDS) ?: error("Transport stalled")
                    assertFalse(event.containsKey("error"))
                    if(event.containsKey("done"))break
                    chunks.write(Base64.getDecoder().decode(event.getValue("chunk").jsonPrimitive.content))
                }
                assertEquals(sse,chunks.toString("UTF-8"))
                assertEquals("Bearer test-key",server.takeRequest().getHeader("Authorization"))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://example.com/secret"))
                transport.request("two",prepared(localUrl))
                assertTrue(events.poll(5,TimeUnit.SECONDS).getValue("error").jsonPrimitive.content.contains("302"))
                assertEquals(2,server.requestCount)
            } finally {transport.close()}
        }
    }
    @Test fun `insecure remote endpoints and header injection rejected without network`() {
        val events=LinkedBlockingQueue<JsonObject>();val transport=AiNativeTransport { _,event->events.add(event) }
        try {
            transport.request("one",prepared("http://example.com/chat/completions"))
            assertTrue(events.poll(1,TimeUnit.SECONDS).containsKey("error"))
            transport.request("two",prepared("https://example.com/chat/completions","secret\r\nHeader:value"))
            val message=events.poll(1,TimeUnit.SECONDS).toString()
            assertTrue(message.contains("error"));assertFalse(message.contains("secret"))
        } finally {transport.close()}
    }
}
