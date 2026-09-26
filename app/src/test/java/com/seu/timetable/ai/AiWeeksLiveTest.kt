package com.seu.timetable.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in replay of the actual APK UI's week clarification requests via native OkHttp. */
class AiWeeksLiveTest {
    @Test fun missingWeeksThenEveryWeek() {
        val keyPath = System.getenv("SEU_AI_TEST_KEY_FILE")
        val requestsPath = System.getenv("SEU_AI_WEEKS_REQUESTS_FILE")
        assumeTrue("Provide key and week regression requests", !keyPath.isNullOrBlank() && !requestsPath.isNullOrBlank())
        val key = File(keyPath!!).readText().trim().removePrefix("\uFEFF")
        val requests = Json.parseToJsonElement(File(requestsPath!!).readText()).jsonArray.map { it.jsonObject }
            .filter { it.getValue("body").jsonObject.getValue("messages").jsonArray[0].jsonObject
                .getValue("content").jsonPrimitive.content.startsWith("你是课表操作助手") }
        assertTrue(requests.size >= 3)
        val events = LinkedBlockingQueue<JsonObject>()
        val transport = AiNativeTransport { _, event -> events.add(event) }
        try {
            for ((index, prepared) in listOf(requests[0], requests[1], requests.last()).withIndex()) {
                events.clear()
                transport.request("weeks-$index", JsonObject(prepared + ("apiKey" to JsonPrimitive(key))).toString())
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val event = events.poll(185, TimeUnit.SECONDS) ?: error("Native response timed out")
                    check(!event.containsKey("error")) { "Native week request failed" }
                    event["chunk"]?.let { bytes.write(Base64.getDecoder().decode(it.jsonPrimitive.content)) }
                    if (event.containsKey("done")) break
                }
                val message = Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
                    .getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
                val decision = Json.parseToJsonElement(message.getValue("content").jsonPrimitive.content).jsonObject
                val action = decision.getValue("action")
                if (index < 2) assertEquals("Missing time/weeks must remain a clarification", JsonNull, action)
                else {
                    assertEquals("add", action.jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals("工科数分", action.jsonObject.getValue("name").jsonPrimitive.content)
                    val sessions = action.jsonObject.getValue("sessions").jsonArray
                    assertEquals(1, sessions.size)
                    val slot = sessions[0].jsonObject
                    assertEquals(7, slot.getValue("day").jsonPrimitive.int)
                    assertEquals(1, slot.getValue("start").jsonPrimitive.int)
                    assertEquals(12, slot.getValue("end").jsonPrimitive.int)
                    assertEquals((1..16).toList(), slot.getValue("weeks").jsonArray.map { it.jsonPrimitive.int })
                }
            }
        } finally { transport.close() }
    }
}
