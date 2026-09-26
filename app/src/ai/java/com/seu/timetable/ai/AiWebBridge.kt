package com.seu.timetable.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import com.seu.timetable.data.TimetableLibrary
import com.seu.timetable.domain.BoardContent
import com.seu.timetable.domain.BoardMeta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Exposed only to immutable APK assets. External documents cannot navigate this WebView. */
class AiWebBridge(
    private val context: Context,
    private val transport: AiNativeTransport,
    private val session: AiViewModel,
    private val changed: () -> Unit,
    private val windowAction: (String) -> Unit,
) {
    private val library = TimetableLibrary(context)
    private val store = AiStore(context)
    private val json = AiBoardCodec.json
    private val snapshots = LinkedHashMap<String, Pair<BoardMeta, BoardContent>>()
    @Volatile var viewedWeek: Int = 1
    @Volatile var active = false
    @Volatile var closed = false
    private inline fun result(block: () -> JsonElement): String = try {
        check(!closed) { "对话已关闭" }
        buildJsonObject { put("ok", true); put("value", block()) }.toString()
    } catch (e: Exception) { buildJsonObject { put("ok", false); put("error", e.message?.take(300) ?: "本机操作失败") }.toString() }

    @JavascriptInterface fun snapshot(): String = result {
        val value = runBlocking { library.activeStrict() }
        val snapshot = AiBoardCodec.snapshot(value.first, value.second, viewedWeek)
        synchronized(snapshots) {
            snapshots[snapshot.getValue("token").jsonPrimitive.content] = value
            while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
        }
        snapshot
    }
    @JavascriptInterface fun commit(token: String, board: String): String = result {
        check(active) { "已离开当前课表，本次未执行" }
        require(board.length <= 4_000_000) { "课表数据过大" }
        val expected = synchronized(snapshots) { snapshots[token] } ?: error("课表快照已过期，请重试")
        val next = AiBoardCodec.content(Json.parseToJsonElement(board).jsonObject, expected.first)
        runBlocking { library.compareAndSetActiveContent(expected.first, expected.second, next) }
        // This notification cannot change a successful disk commit into a failed request.
        runCatching { changed() }
        JsonPrimitive(true)
    }
    @JavascriptInterface fun loadSettings(): String = result {
        val value = if (session.state.value.ready) session.state.value.config to session.apiKey else store.loadSettings()
        buildJsonObject { put("config", json.encodeToJsonElement(value.first)); put("apiKey", value.second) }
    }
    @JavascriptInterface fun saveSettings(value: String): String = result {
        require(value.length <= 20000)
        val data = Json.parseToJsonElement(value).jsonObject
        val config = json.decodeFromJsonElement<AiConfig>(data.getValue("config")).validated()
        val key = data.getValue("apiKey").jsonPrimitive.content.trim()
        require(key.length <= 4096 && key.none { it == '\r' || it == '\n' }) { "API Key 格式无效" }
        store.saveSettings(config, key); session.acceptWebSettings(config, key)
        JsonPrimitive(true)
    }
    @JavascriptInterface fun request(id: String, prepared: String) { if (!closed) transport.request(id, prepared) }
    @JavascriptInterface fun cancel(id: String) { transport.cancel(id) }
    @JavascriptInterface fun action(value: String) { if (!closed && value in setOf("minimize", "size", "shrink", "grow", "back", "ready", "startup-error")) windowAction(value) }
    @JavascriptInterface fun copy(text: String) {
        if (!closed && text.length <= 200000) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("AI 消息", text))
    }
    fun close() { closed = true; active = false; transport.close() }
}
