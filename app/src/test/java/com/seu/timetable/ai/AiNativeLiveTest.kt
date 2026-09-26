package com.seu.timetable.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Replays secret-free requests produced by the APK's actual UI, through Android's actual transport. */
class AiNativeLiveTest {
    @Test fun sharedUiPayloadsThroughNativeOkHttp() {
        val keyPath=System.getenv("SEU_AI_TEST_KEY_FILE")
        val requestsPath=System.getenv("SEU_AI_TEST_REQUESTS_FILE")
        assumeTrue("Provide runtime key and prepared requests for native live checks",
            !keyPath.isNullOrBlank() && !requestsPath.isNullOrBlank())
        val key=File(keyPath!!).readText().trim().removePrefix("\uFEFF")
        val requests=Json.parseToJsonElement(File(requestsPath!!).readText()).jsonArray.map { it.jsonObject }
        val events=LinkedBlockingQueue<JsonObject>()
        val transport=AiNativeTransport { _,event -> events.add(event) }
        fun complete(prepared:JsonObject):JsonObject {
            events.clear()
            transport.request(java.util.UUID.randomUUID().toString(),JsonObject(prepared+("apiKey" to JsonPrimitive(key))).toString())
            val bytes=java.io.ByteArrayOutputStream()
            var contentType=""
            while(true){
                val event=events.poll(185,TimeUnit.SECONDS) ?: error("Native response timed out")
                check(!event.containsKey("error")){event["error"]?.jsonPrimitive?.content.orEmpty()}
                if(event.containsKey("contentType"))contentType=event.getValue("contentType").jsonPrimitive.content
                if(event.containsKey("chunk"))bytes.write(Base64.getDecoder().decode(event.getValue("chunk").jsonPrimitive.content))
                if(event.containsKey("done"))break
            }
            val body=bytes.toString("UTF-8")
            if(contentType.contains("event-stream")) {
                val text=body.lineSequence().filter { it.startsWith("data: ") && !it.contains("[DONE]") }.mapNotNull { line ->
                    Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                }.joinToString("")
                return buildJsonObject { put("text",text) }
            }
            return Json.parseToJsonElement(body).jsonObject
        }
        fun system(r:JsonObject)=r.getValue("body").jsonObject.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
        fun lastUser(r:JsonObject)=r.getValue("body").jsonObject.getValue("messages").jsonArray.last().jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
        try {
            val ordinary=requests.first { it.getValue("body").jsonObject["stream"]?.jsonPrimitive?.boolean==true }
            assertTrue(complete(ordinary).getValue("text").jsonPrimitive.content.isNotBlank())
            val summary=requests.first { system(it).contains("你正在压缩一段对话") }
            val summarized=complete(summary).getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
            assertTrue("Summary lost early fact",summarized.contains("73"))
            val add=requests.first { it.getValue("body").jsonObject.containsKey("response_format") && runCatching { lastUser(it).contains("只安排第二周") }.getOrDefault(false) }
            val decision=complete(add).getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
            val action=Json.parseToJsonElement(decision).jsonObject.getValue("action").jsonObject
            assertEquals("add",action.getValue("type").jsonPrimitive.content)
            assertEquals("工科数学分析",action.getValue("name").jsonPrimitive.content)
            assertEquals(listOf(2),action.getValue("sessions").jsonArray[0].jsonObject.getValue("weeks").jsonArray.map { it.jsonPrimitive.int })
            // The second independent image review includes original + overlapping full-height strips.
            val photo=requests.last { r -> system(r).contains("import_preview") && r.getValue("body").jsonObject.getValue("messages").jsonArray.any { m ->
                (m.jsonObject["content"] as? JsonArray)?.count { it.jsonObject["type"]?.jsonPrimitive?.content=="image_url" } == 3
            } }
            val photoReply=complete(photo).getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
            val courses=Json.parseToJsonElement(photoReply).jsonObject.getValue("action").jsonObject.getValue("courses").jsonArray
            assertEquals(9,courses.size)
            assertEquals(12,courses.sumOf { it.jsonObject.getValue("sessions").jsonArray.size })
            for(name in listOf("马克思主义基本原理","中共党史")) {
                val course=courses.single { it.jsonObject.getValue("name").jsonPrimitive.content==name }.jsonObject
                assertEquals(2,course.getValue("sessions").jsonArray[0].jsonObject.getValue("day").jsonPrimitive.int)
            }
            println("REAL_NATIVE_API_PASS: streamed chat, summary, semantic add, official photo 9 subjects / 12 slots")
        } finally { transport.close() }
    }
}
