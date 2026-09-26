package com.seu.timetable.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AiUiState(
    val config: AiConfig = AiConfig(), val document: ChatDocument = ChatDocument(), val position: ChatPosition = ChatPosition(),
    val ready: Boolean = false, val busy: Boolean = false, val importing: Boolean = false,
    val cacheError: String = "", val error: String = "", val settingsStatus: String = "", val testing: Boolean = false, val configured: Boolean = false,
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    val store = AiStore(application)
    private val client = AiClient()
    private val mutable = MutableStateFlow(AiUiState())
    val state = mutable.asStateFlow()
    @Volatile var apiKey: String = ""
        private set
    private data class CacheWrite(val document: ChatDocument, val clearImages: Boolean = false)
    private val saves = Channel<CacheWrite>(Channel.CONFLATED)
    private var reply: Job? = null
    private var draftSave: Job? = null
    private var positionSave: Job? = null

    init {
        viewModelScope.launch {
            for (write in saves) {
                try {
                    withContext(Dispatchers.IO) { store.saveChat(write.document); if (write.clearImages) store.removeUnusedImages(write.document) }
                    mutable.update { it.copy(cacheError = "") }
                }
                catch (_: Exception) { mutable.update { it.copy(cacheError = "本机保存失败，请检查存储空间") } }
                finally { if (write.clearImages) mutable.update { it.copy(ready = true) } }
            }
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.loadSettings() } }.onSuccess { (config, key) ->
                apiKey = key; mutable.update { it.copy(config = config, configured = key.isNotBlank()) }
            }.onFailure { mutable.update { it.copy(error = "模型设置或已存 Key 无法读取，请重新配置") } }
            runCatching { withContext(Dispatchers.IO) { val doc = store.loadChat(); store.removeUnusedImages(doc); doc to store.loadPosition() } }.onSuccess { (doc, position) ->
                mutable.update { it.copy(document = doc, position = position) }
            }.onFailure { mutable.update { it.copy(cacheError = "聊天记录无法读取，可清空后重新开始") } }
            mutable.update { it.copy(ready = true) }
        }
    }
    private fun persist() { if (mutable.value.ready) saves.trySend(CacheWrite(mutable.value.document)) }
    /** Web-backed AI uses the same application-session settings as the native profile. */
    fun acceptWebSettings(config: AiConfig, key: String) {
        apiKey = key
        mutable.update { it.copy(config = config, configured = key.isNotBlank()) }
    }
    fun flush() { draftSave?.cancel(); persist() }
    fun draft(text: String) {
        mutable.update { it.copy(document = it.document.copy(draft = text.take(20000))) }
        draftSave?.cancel(); draftSave = viewModelScope.launch { delay(200); persist() }
    }
    fun position(value: ChatPosition) {
        mutable.update { it.copy(position = value) }
        positionSave?.cancel(); positionSave = viewModelScope.launch { delay(200); withContext(Dispatchers.IO) { runCatching { store.savePosition(value) } } }
    }
    fun removeImage(id: String) { mutable.update { it.copy(document = it.document.copy(images = it.document.images.filterNot { image -> image.id == id })) }; persist() }
    fun addImages(uris: List<Uri>) {
        if (mutable.value.busy || mutable.value.importing || !mutable.value.ready) return
        if (!mutable.value.config.vision) { mutable.update { it.copy(error = "请启用图片输入并选择视觉模型") }; return }
        mutable.update { it.copy(importing = true, error = "") }
        viewModelScope.launch {
            try {
                uris.forEach { uri ->
                    require(mutable.value.document.images.size < 4) { "每条消息最多 4 张照片" }
                    val image = withContext(Dispatchers.IO) { store.importImage(uri) }
                    mutable.update { it.copy(document = it.document.copy(images = it.document.images + image)) }
                    persist()
                }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "照片无法读取") } }
            finally { mutable.update { it.copy(importing = false) } }
        }
    }
    fun clear() {
        if (mutable.value.busy || mutable.value.importing || !mutable.value.ready) return
        draftSave?.cancel()
        mutable.update { it.copy(document = ChatDocument(), error = "", ready = false) }
        saves.trySend(CacheWrite(ChatDocument(), clearImages = true))
    }
    fun stop() { reply?.cancel() }
    fun send(retryId: String? = null) {
        val before = mutable.value
        if (!before.ready || before.busy || before.importing) return
        if (apiKey.isBlank()) { mutable.update { it.copy(error = "请先在「我的 → AI 模型设置」填写 API Key") }; return }
        val doc = before.document
        if (retryId == null && doc.draft.isBlank() && doc.images.isEmpty()) return
        val history = if (retryId != null) {
            val index = doc.messages.indexOfFirst { it.id == retryId && it.role == "assistant" }
            if (index < 0 || index != doc.messages.lastIndex) return
            doc.messages.take(index)
        } else doc.messages + ChatMessage(role = "user", text = doc.draft.trim(), images = doc.images)
        if (!before.config.vision && chatContext(history, before.config.contextTurns).any { it.images.isNotEmpty() }) {
            mutable.update { it.copy(error = "对话包含图片，请启用图片输入或清空聊天") }; return
        }
        val answer = ChatMessage(role = "assistant", status = "streaming")
        val next = if (retryId == null) ChatDocument(history + answer) else doc.copy(messages = history + answer)
        mutable.update { it.copy(document = next, busy = true, error = "") }; persist()
        val config = before.config; val key = apiKey
        reply = viewModelScope.launch {
            var text = ""; var reasoning = ""; var finishReason = ""; var lastSave = 0L
            fun updateAnswer(status: String, error: String = "") {
                mutable.update { ui -> ui.copy(document = ui.document.copy(messages = ui.document.messages.map { if (it.id == answer.id) it.copy(text = text, reasoning = reasoning, status = status, error = error) else it })) }
            }
            try {
                client.chat(config, key, history, { store.imageFile(it).readBytes() }).collect { delta ->
                    text += delta.text; reasoning += delta.reasoning
                    if (delta.reason.isNotBlank()) finishReason = delta.reason
                    updateAnswer("streaming")
                    if (System.currentTimeMillis() - lastSave > 250) { persist(); lastSave = System.currentTimeMillis() }
                }
                check(text.isNotBlank()) { if (finishReason == "tool_calls") "模型返回了工具调用，当前不执行此类操作" else "模型未返回文字回答，请重试或检查设置" }
                if (finishReason == "length") text += "\n\n> 已达到输出长度上限，可在设置中提高后重新生成。"
                updateAnswer("complete")
            } catch (_: CancellationException) { updateAnswer("stopped") }
            catch (e: Exception) { updateAnswer("error", e.message ?: "请求失败，请重试") }
            finally { mutable.update { it.copy(busy = false) }; persist() }
        }
    }
    fun saveSettings(config: AiConfig, key: String) {
        viewModelScope.launch {
            try {
                val checked = config.validated(); val cleaned = key.trim()
                require(cleaned.length <= 4096 && '\n' !in cleaned && '\r' !in cleaned) { "API Key 格式不正确" }
                withContext(Dispatchers.IO) { store.saveSettings(checked, cleaned) }
                apiKey = cleaned
                mutable.update { it.copy(config = checked, configured = cleaned.isNotBlank(), settingsStatus = "设置已保存，返回课表即可聊天", error = "") }
            } catch (e: Exception) { mutable.update { it.copy(settingsStatus = e.message ?: "设置保存失败") } }
        }
    }
    fun testSettings(config: AiConfig, key: String) {
        if (mutable.value.testing) return
        mutable.update { it.copy(testing = true, settingsStatus = "正在测试连接…") }
        viewModelScope.launch {
            try {
                var text = ""
                client.chat(config.validated(), key.trim(), emptyList(), { byteArrayOf() }, test = true).collect { text += it.text }
                check(text.isNotBlank()) { "接口可达，但没有返回文字" }
                mutable.update { it.copy(settingsStatus = "连接成功 · ${config.model}，请保存设置") }
            } catch (e: Exception) { mutable.update { it.copy(settingsStatus = e.message ?: "连接失败") } }
            finally { mutable.update { it.copy(testing = false) } }
        }
    }
    fun settingsError(message: String) { mutable.update { it.copy(settingsStatus = message) } }
}
