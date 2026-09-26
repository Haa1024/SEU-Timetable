package com.seu.timetable.ai

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@Serializable
data class AiConfig(
    val provider: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val vision: Boolean = true,
    val thinking: Boolean = false,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val contextTurns: Int = 12,
    val systemPrompt: String = "你是一位耐心、清晰的学习助手。优先使用中文，准确回答问题，不确定时说明。",
    val rememberKey: Boolean = false,
) {
    fun endpoint(): HttpUrl {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: error("请输入完整的 API 地址")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "API 地址不能包含账号、查询参数或片段" }
        require(url.isHttps || url.host in listOf("localhost", "127.0.0.1", "::1")) { "远程 API 请使用 HTTPS" }
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath(if (path.endsWith("/chat/completions")) path else "$path/chat/completions").build()
    }

    fun validated(): AiConfig {
        endpoint()
        require(provider in listOf("deepseek", "compatible")) { "接口类型不正确" }
        require(model.isNotBlank() && model.length <= 160 && !model.contains('\n') && !model.contains('\r')) { "请填写有效的模型名称" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "温度应在 0–2 之间" }
        require(maxTokens in 128..32768) { "最大输出长度应在 128–32768 之间" }
        require(contextTurns in 1..30) { "上下文轮数应在 1–30 之间" }
        require(systemPrompt.length <= 8000) { "系统提示词最多 8000 字" }
        return copy(baseUrl = baseUrl.trim(), model = model.trim(), systemPrompt = systemPrompt.trim())
    }
}

@Serializable
data class ChatImage(val id: String, val name: String)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String = "",
    val reasoning: String = "",
    val images: List<ChatImage> = emptyList(),
    val status: String = "complete",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatDocument(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val images: List<ChatImage> = emptyList(),
)

@Serializable
data class ChatRect(val x: Float = 20f, val y: Float = 180f, val width: Float = 344f, val height: Float = 492f)

@Serializable
data class ChatPosition(val window: ChatRect = ChatRect(), val bubbleX: Float = 300f, val bubbleY: Float = 550f)

data class ChatLimits(val minWidth: Float, val maxWidth: Float, val minHeight: Float, val maxHeight: Float)

fun chatLimits(width: Float, height: Float): ChatLimits {
    val maxW = (width - 24f).coerceAtLeast(1f)
    val maxH = (height * .88f).coerceAtLeast(1f)
    return ChatLimits(minOf(280f, maxW), maxW, minOf(300f, maxH), maxH)
}

fun ChatRect.constrain(width: Float, height: Float): ChatRect {
    val limits = chatLimits(width, height)
    val w = this.width.coerceIn(limits.minWidth, limits.maxWidth)
    val h = this.height.coerceIn(limits.minHeight, limits.maxHeight)
    return ChatRect(x.coerceIn(12f, (width - w - 12f).coerceAtLeast(12f)), y.coerceIn(0f, (height - h).coerceAtLeast(0f)), w, h)
}

fun chatContext(history: List<ChatMessage>, turns: Int): List<ChatMessage> {
    val groups = mutableListOf<MutableList<ChatMessage>>()
    history.forEach { message ->
        if (message.role == "user") groups.add(mutableListOf(message))
        else if (message.role == "assistant" && message.status == "complete" && message.text.isNotBlank()) groups.lastOrNull()?.add(message)
    }
    return groups.takeLast(turns).flatten()
}
