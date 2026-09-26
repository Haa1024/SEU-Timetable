package com.seu.timetable.ai

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Android network transport for bundled AI modules; no browser CORS proxy/server required. */
class AiNativeTransport(private val emit: (String, JsonObject) -> Unit) {
    private val calls = ConcurrentHashMap<String, Call>()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    @Volatile private var closed = false

    fun request(id: String, prepared: String) {
        var secret = ""
        try {
            check(!closed && calls.size < 4 && id.length <= 100 && !calls.containsKey(id)) { "请求仍在进行，请稍后重试" }
            require(prepared.length <= 24 * 1024 * 1024) { "请求过大，请减少图片或上下文" }
            val data = Json.parseToJsonElement(prepared).jsonObject
            val url = data.getValue("url").jsonPrimitive.content.toHttpUrl()
            require((url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "远程 API 必须使用 HTTPS" }
            secret = data["apiKey"]?.jsonPrimitive?.content ?: ""
            require(secret.length <= 4096 && secret.none { it == '\r' || it == '\n' }) { "API Key 格式无效" }
            val body = data.getValue("body").jsonObject.toString()
            val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (secret.isNotBlank()) header("Authorization", "Bearer $secret") }.build()
            val call = client.newCall(request); calls[id] = call
            val key = secret
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (calls.remove(id) != null) failure(id, "模型连接失败或超时，请检查网络后重试", key)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (closed || !calls.containsKey(id)) return
                            if (!response.isSuccessful) {
                                val tips = mapOf(401 to "API Key 无效或已过期", 402 to "模型账户余额不足", 403 to "接口拒绝访问", 404 to "API 地址或模型名不存在", 429 to "请求过于频繁，请稍后重试")
                                error(tips[response.code] ?: "模型接口返回 HTTP ${response.code}")
                            }
                            emit(id, buildJsonObject {
                                put("status", response.code)
                                put("contentType", if (response.header("Content-Type").orEmpty().contains("text/event-stream")) "text/event-stream" else "application/json")
                            })
                            val input = response.body?.byteStream() ?: error("模型接口未返回内容")
                            val buffer = ByteArray(16384); var total = 0
                            while (!closed && calls.containsKey(id)) {
                                val count = input.read(buffer); if (count < 0) break
                                total += count; check(total <= 8 * 1024 * 1024) { "模型响应过大" }
                                emit(id, buildJsonObject { put("chunk", java.util.Base64.getEncoder().encodeToString(buffer.copyOf(count))) })
                            }
                            if (!closed && calls.containsKey(id)) emit(id, buildJsonObject { put("done", true) })
                        } catch (e: Exception) { if (calls.containsKey(id) && !closed) failure(id, e.message ?: "模型响应读取失败", key) }
                        finally { calls.remove(id) }
                    }
                }
            })
        } catch (e: Exception) { failure(id, e.message ?: "请求无效", secret) }
    }
    private fun failure(id: String, message: String, key: String) {
        emit(id, buildJsonObject { put("error", if (key.isBlank()) message.take(500) else message.replace(key, "[已隐藏]").take(500)) })
    }
    fun cancel(id: String) { calls.remove(id)?.cancel() }
    fun close() { closed = true; calls.keys.toList().forEach(::cancel); client.dispatcher.executorService.shutdown() }
}
