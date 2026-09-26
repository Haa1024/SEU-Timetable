package com.seu.timetable.ai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

data class ReplyDelta(val text: String = "", val reasoning: String = "", val finished: Boolean = false, val reason: String = "")

/** Only explicit chat messages enter this client; it has no timetable repository or tools. */
class AiClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun requestBody(config: AiConfig, history: List<ChatMessage>, imageBytes: (ChatImage) -> ByteArray, test: Boolean = false): String {
        config.validated()
        val messages = if (test) listOf(ChatMessage(role = "user", text = "请只回复 OK。")) else chatContext(history, config.contextTurns)
        require(messages.isNotEmpty()) { "请先输入消息" }
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", !test)
            put("max_tokens", if (test) 64 else config.maxTokens)
            if (config.provider == "deepseek") putJsonObject("thinking") { put("type", if (config.thinking && !test) "enabled" else "disabled") }
            if (!config.thinking || config.provider != "deepseek" || test) put("temperature", config.temperature)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "本应用当前仅支持基础聊天。你无法访问、添加、编辑或删除用户的实际课表，不要声称已经执行这些操作。仅根据用户明确发送的对话和图片作答。\n${config.systemPrompt}")
                }
                messages.forEach { message ->
                    require(message.text.length <= 200000) { "单条消息过长" }
                    addJsonObject {
                        put("role", message.role)
                        if (message.images.isEmpty()) put("content", message.text)
                        else {
                            require(config.vision) { "对话包含图片，请启用图片输入并选择视觉模型，或清空聊天后使用文本模型" }
                            require(message.images.size <= 4) { "每条消息最多 4 张图片" }
                            putJsonArray("content") {
                                addJsonObject { put("type", "text"); put("text", message.text.ifBlank { "请查看这张图片。" }) }
                                message.images.forEach { image ->
                                    val bytes = imageBytes(image)
                                    require(bytes.size <= 2300000) { "图片过大，请重新选择" }
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        require(body.toByteArray().size <= 24 * 1024 * 1024) { "请求超过 24 MB，请减少图片或上下文轮数" }
        return body
    }

    fun chat(config: AiConfig, key: String, history: List<ChatMessage>, imageBytes: (ChatImage) -> ByteArray, test: Boolean = false): Flow<ReplyDelta> = callbackFlow {
        require(key.isNotBlank() || config.endpoint().host in listOf("localhost", "127.0.0.1", "::1")) { "请先在「我的 → AI 模型设置」填写 API Key" }
        require(key.length <= 4096 && '\n' !in key && '\r' !in key) { "API Key 格式不正确" }
        val body = requestBody(config, history, imageBytes, test)
        val request = Request.Builder().url(config.endpoint())
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}") }.build()
        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(IOException(if (call.isCanceled()) "请求已停止" else "连接失败或超时，请检查网络与 API 地址")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            val detail = runCatching { json.parseToJsonElement(response.peekBody(16000).string()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull().orEmpty()
                            val hint = when (response.code) {
                                401 -> "API Key 无效或已过期"; 402 -> "模型账户余额不足"; 403 -> "接口拒绝访问，请检查权限"
                                404 -> "接口地址或模型不存在"; 429 -> "请求过于频繁，请稍后重试"; else -> "模型接口返回 HTTP ${response.code}"
                            }
                            error(hint + if (detail.isBlank()) "" else "：${detail.take(350)}")
                        }
                        val source = response.body?.source() ?: error("模型接口未返回内容")
                        var finished = false
                        var doneEvent = false
                        var count = 0
                        fun consume(data: String) {
                            if (data.trim() == "[DONE]") { finished = true; doneEvent = true; return }
                            val value = json.parseToJsonElement(data).jsonObject
                            value["error"]?.let { error((it as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: "模型接口返回错误") }
                            val choice = value["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
                            val delta = (choice["delta"] ?: choice["message"])?.jsonObject
                            val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val chunk = ReplyDelta(delta?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(), delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull.orEmpty(), reason.isNotBlank(), reason)
                            if (chunk.finished) finished = true
                            trySend(chunk)
                        }
                        if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                            val event = StringBuilder()
                            while (!doneEvent && !source.exhausted()) {
                                val line = source.readUtf8LineStrict(2L * 1024 * 1024)
                                count += line.length
                                require(count <= 2 * 1024 * 1024) { "回复过长，请降低最大输出长度" }
                                if (line.isEmpty()) {
                                    if (event.isNotEmpty()) { consume(event.toString()); event.clear() }
                                } else if (line.startsWith("data:")) { if (event.isNotEmpty()) event.append('\n'); event.append(line.substring(5).trimStart()) }
                            }
                            if (event.isNotEmpty()) consume(event.toString())
                            check(finished) { "连接提前断开，已保留收到的内容，可重试" }
                        } else {
                            consume(response.peekBody(2L * 1024 * 1024).string())
                        }
                        close()
                    } catch (e: Exception) {
                        val message = e.message.orEmpty().ifBlank { "模型响应无法读取，请重试" }
                        close(IOException(if (key.isBlank()) message else message.replace(key, "[已隐藏]")))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
}
