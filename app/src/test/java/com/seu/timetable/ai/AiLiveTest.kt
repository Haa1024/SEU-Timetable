package com.seu.timetable.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in only. The key is read at test runtime and never becomes an APK resource. */
class AiLiveTest {
    @Test fun realDeepSeekTextVisionAndContext() = runBlocking {
        val path = System.getenv("SEU_AI_TEST_KEY_FILE")
        assumeTrue("Set SEU_AI_TEST_KEY_FILE to run paid API verification", !path.isNullOrBlank() && File(path).isFile)
        val key = File(path!!).readText().trim().removePrefix("\uFEFF")
        val client = AiClient(); val config = AiConfig(maxTokens = 512)
        val connection = client.chat(config, key, emptyList(), { byteArrayOf() }, test = true).toList().joinToString("") { it.text }
        assertTrue("Connection test returned no text", connection.isNotBlank())
        val prompt = ChatMessage(role = "user", text = "请只回答：安卓对话测试成功。")
        val answer = client.chat(config, key, listOf(prompt), { byteArrayOf() }).toList().joinToString("") { it.text }
        assertTrue("Streamed text verification failed", answer.contains("安卓对话测试成功"))
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("ai_vision.jpg")).use { it.readBytes() }
        val imageMessage = ChatMessage(role = "user", text = "识别图片中的英文、数字和方块颜色。简短回答。", images = listOf(ChatImage("test", "test")))
        val history = listOf(prompt, ChatMessage(role = "assistant", text = answer), imageMessage)
        val vision = client.chat(config, key, history, { bytes }).toList().joinToString("") { it.text }
        assertTrue("Vision must identify SEU, 42 and green", vision.contains("SEU", ignoreCase = true) && vision.contains("42") && vision.contains("绿"))
        val followup = history + ChatMessage(role = "assistant", text = vision) + ChatMessage(role = "user", text = "刚才图片上的数字是多少？只回答数字。")
        val continued = client.chat(config, key, followup, { bytes }).toList().joinToString("") { it.text }
        assertTrue("Multi-turn image context failed", continued.contains("42"))
        println("REAL_API_PASS: DeepSeek Flash connection, streamed text, image recognition, multi-turn context")
    }
}
