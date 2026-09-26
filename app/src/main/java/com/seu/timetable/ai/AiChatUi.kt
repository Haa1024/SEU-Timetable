package com.seu.timetable.ai

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.seu.timetable.ui.components.ConfirmDialog
import com.seu.timetable.ui.theme.LocalSeuColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AiAction(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalSeuColors.current
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(symbol, fontSize = 17.sp, color = if (enabled) c.textSecondary else c.textTertiary.copy(alpha = .45f))
    }
}

@Composable
fun AiChatOverlay(vm: AiViewModel, visible: Boolean, onSettings: () -> Unit) {
    val ui by vm.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var previous by remember { mutableStateOf<ChatRect?>(null) }
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val c = LocalSeuColors.current
    val ime = WindowInsets.ime.getBottom(density) > 0
    DisposableEffect(visible) { onDispose { vm.flush() } }
    if (!visible) return
    BackHandler(open) { focus.clearFocus(); open = false; vm.flush() }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = if (ime) 8.dp else 80.dp)) {
        val w = maxWidth.value; val h = maxHeight.value
        val rect = ui.position.window.constrain(w, h)
        fun changeRect(next: ChatRect) { vm.position(vm.state.value.position.copy(window = next.constrain(w, h))) }
        fun scale(amount: Float) { changeRect(rect.copy(width = rect.width + amount, height = rect.height + amount * 1.5f)) }
        if (!open) {
            val x = ui.position.bubbleX.coerceIn(8f, (w - 60f).coerceAtLeast(8f))
            val y = ui.position.bubbleY.coerceIn(0f, (h - 60f).coerceAtLeast(0f))
            Box(Modifier.offset { IntOffset((x * density.density).roundToInt(), (y * density.density).roundToInt()) }
                .size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.primary).border(2.dp, c.surface, CircleShape)
                .pointerInput(w, h) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        val old = vm.state.value.position
                        vm.position(old.copy(bubbleX = (old.bubbleX.coerceIn(8f, (w - 60).coerceAtLeast(8f)) + delta.x / density.density).coerceIn(8f, (w - 60).coerceAtLeast(8f)),
                            bubbleY = (old.bubbleY.coerceIn(0f, (h - 60).coerceAtLeast(0f)) + delta.y / density.density).coerceIn(0f, (h - 60).coerceAtLeast(0f))))
                    }
                }.clickable(role = Role.Button) { open = true }.semantics { contentDescription = "打开 AI 聊天，可拖动" }, contentAlignment = Alignment.Center) {
                Text(if (ui.busy) "✦ ·" else "✦ AI", color = c.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(Modifier.offset { IntOffset((rect.x * density.density).roundToInt(), (rect.y * density.density).roundToInt()) }
                .size(rect.width.dp, rect.height.dp).shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                .background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp))) {
                Row(Modifier.fillMaxWidth().pointerInput(w, h) {
                    detectDragGestures { change, delta ->
                        change.consume(); val old = vm.state.value.position.window.constrain(w, h)
                        changeRect(old.copy(x = old.x + delta.x / density.density, y = old.y + delta.y / density.density))
                    }
                }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✦", fontSize = 23.sp, color = c.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI 助手", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Text(ui.config.model, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.textSecondary)
                    }
                    AiAction("⚙", "AI 模型设置") { focus.clearFocus(); onSettings() }
                    AiAction("⛶", "放大或还原聊天窗口") {
                        val restored = previous
                        if (restored != null) { changeRect(restored); previous = null }
                        else { previous = rect; val limit = chatLimits(w, h); changeRect(rect.copy(width = limit.maxWidth, height = limit.maxHeight)) }
                    }
                    AiAction("−", "收起聊天窗口") { focus.clearFocus(); open = false; vm.flush() }
                }
                var clearConfirm by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().background(c.surfaceSunken).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (!ui.ready) "正在读取记录…" else if (ui.cacheError.isNotBlank()) ui.cacheError else if (ui.busy) "正在回复 · 自动保存" else "聊天记录自动保存在本机",
                        fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = if (ui.cacheError.isBlank()) c.textTertiary else c.danger)
                    AiAction("−", "缩小聊天窗口") { scale(-35f) }
                    AiAction("+", "放大聊天窗口") { scale(35f) }
                    AiAction("⌫", "清空聊天记录", !ui.busy && !ui.importing && ui.ready) { clearConfirm = true }
                }
                if (clearConfirm) ConfirmDialog(title = "清空聊天记录？", body = "将删除本机聊天和图片，无法恢复。模型设置不受影响。", confirmText = "清空", onConfirm = { clearConfirm = false; vm.clear() }, onDismiss = { clearConfirm = false })
                ChatMessages(ui, vm, Modifier.weight(1f), onSettings)
                if (ui.error.isNotBlank()) Text(ui.error, fontSize = 10.sp, color = c.danger, modifier = Modifier.fillMaxWidth().heightIn(max = 48.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp))
                ChatComposer(ui, vm)
                Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("◢", fontSize = 16.sp, color = c.textTertiary, modifier = Modifier.width(30.dp).fillMaxHeight().semantics { contentDescription = "拖动调整聊天窗口大小" }.pointerInput(w, h) {
                        detectDragGestures { change, delta ->
                            change.consume(); val old = vm.state.value.position.window.constrain(w, h)
                            changeRect(old.copy(width = old.width + delta.x / density.density, height = old.height + delta.y / density.density))
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun ChatMessages(ui: AiUiState, vm: AiViewModel, modifier: Modifier, onSettings: () -> Unit) {
    val c = LocalSeuColors.current
    val list = rememberLazyListState()
    var imagePreview by remember { mutableStateOf<ChatImage?>(null) }
    val clipboard = LocalClipboardManager.current
    val last = ui.document.messages.lastOrNull()
    LaunchedEffect(last?.id, last?.text?.length, last?.reasoning?.length, last?.status) {
        val total = ui.document.messages.size
        if (total > 0 && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3) list.scrollToItem(total - 1)
    }
    if (ui.document.messages.isEmpty()) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("✦", color = c.primary, fontSize = 30.sp)
            Text("有什么想聊的？", fontSize = 17.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text("问问题、读图片，一起理清学习思路。", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
            if (!ui.configured) Text("先配置你的模型 →", fontSize = 12.sp, color = c.primary, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("整理学习计划" to "帮我制定一个可执行的学习计划。", "一起分析题目" to "我想弄懂一道题，请一步步帮我分析。").forEach { (title, prompt) ->
                    Text(title, fontSize = 10.sp, color = c.textSecondary, modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(8.dp)).clickable { vm.draft(prompt) }.padding(8.dp))
                }
            }
        }
    } else LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(ui.document.messages, key = { it.id }) { message ->
            val user = message.role == "user"
            Column(Modifier.fillMaxWidth().padding(start = if (user) 22.dp else 0.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                Text(if (user) "你" else "✦ AI 助手", fontSize = 10.sp, color = c.textTertiary, modifier = Modifier.padding(bottom = 6.dp))
                if (message.reasoning.isNotBlank()) {
                    var expanded by remember(message.id) { mutableStateOf(false) }
                    Text(if (expanded) "▾ 思考过程" else "▸ 思考过程", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp))
                    if (expanded) SelectionContainer { Text(message.reasoning, fontSize = 11.sp, color = c.textSecondary, lineHeight = 17.sp) }
                }
                message.images.forEach { image -> ChatImageView(vm, image, Modifier.sizeIn(maxWidth = 170.dp, maxHeight = 140.dp).clip(RoundedCornerShape(10.dp)).clickable { imagePreview = image }) }
                if (message.text.isNotBlank()) {
                    if (user) SelectionContainer { Text(message.text, fontSize = 12.sp, lineHeight = 19.sp, color = c.textPrimary, modifier = Modifier.clip(RoundedCornerShape(13.dp, 13.dp, 3.dp, 13.dp)).background(c.primarySoft).padding(10.dp)) }
                    else MarkdownReply(message.text)
                }
                val status = when (message.status) { "streaming" -> if (message.text.isBlank()) "正在等待回复…" else "正在生成…"; "stopped" -> "已停止"; "interrupted" -> "上次回复已中断"; "error" -> message.error; else -> "" }
                if (status.isNotBlank()) Text(status, fontSize = 10.sp, color = if (message.status == "error") c.danger else c.textTertiary, modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.text.isNotBlank()) AiAction("⧉", "复制消息") { clipboard.setText(AnnotatedString(message.text)) }
                    if (!user && message.id == last?.id && !ui.busy) Text(if (message.status == "complete") "↻ 重新生成" else "↻ 重试", fontSize = 10.sp, color = c.textSecondary, modifier = Modifier.clickable { vm.send(message.id) }.padding(8.dp))
                }
            }
        }
    }
    imagePreview?.let { image -> Dialog(onDismissRequest = { imagePreview = null }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp), horizontalAlignment = Alignment.End) {
            AiAction("×", "关闭图片") { imagePreview = null }
            ChatImageView(vm, image, Modifier.fillMaxWidth().heightIn(max = 480.dp))
        }
    } }
}

@Composable
private fun ChatComposer(ui: AiUiState, vm: AiViewModel) {
    val c = LocalSeuColors.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { vm.addImages(it) }
    Column(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceSunken).border(1.dp, c.border, RoundedCornerShape(12.dp)).padding(8.dp)) {
        if (ui.document.images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.document.images.forEach { image -> Box {
                ChatImageView(vm, image, Modifier.size(46.dp).clip(RoundedCornerShape(7.dp)))
                Text("×", color = c.onPrimary, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(c.primary).clickable { vm.removeImage(image.id) }.semantics { contentDescription = "移除照片" }.padding(horizontal = 4.dp))
            } }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 70.dp)) {
            if (ui.document.draft.isEmpty()) Text("发消息，或添加图片…", fontSize = 12.sp, color = c.textTertiary)
            BasicTextField(value = ui.document.draft, onValueChange = vm::draft, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "聊天消息" },
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = c.textPrimary), cursorBrush = SolidColor(c.primary), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (!ui.busy) vm.send() }))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AiAction("▧", "添加照片", ui.ready && !ui.busy && !ui.importing && ui.config.vision) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            Text(if (ui.importing) "正在处理照片…" else if (ui.config.vision) "可添加图片" else "文本模式", fontSize = 9.sp, color = c.textTertiary, modifier = Modifier.weight(1f))
            val enabled = ui.ready && !ui.importing && (ui.busy || ui.document.draft.isNotBlank() || ui.document.images.isNotEmpty())
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (enabled) c.primary else c.primary.copy(alpha = .4f)).clickable(enabled = enabled, role = Role.Button) { if (ui.busy) vm.stop() else vm.send() }
                .semantics { contentDescription = if (ui.busy) "停止生成" else "发送消息" }, contentAlignment = Alignment.Center) {
                Text(if (ui.busy) "■" else "↑", color = c.onPrimary, fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun ChatImageView(vm: AiViewModel, image: ChatImage, modifier: Modifier) {
    var bitmap by remember(image.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(image.id) {
        bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(vm.store.imageFile(image).path) }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), image.name, modifier) } ?: Text("照片无法读取", modifier, fontSize = 10.sp, color = LocalSeuColors.current.textTertiary)
}

@Composable
private fun MarkdownReply(text: String) {
    val context = LocalContext.current
    val c = LocalSeuColors.current
    val renderer = remember(context) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val uri = Uri.parse(link)
                    if (uri.scheme in listOf("https", "http")) runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
            }
        }).build()
    }
    AndroidView(factory = { TextView(it).apply { textSize = 12f; setTextIsSelectable(true); setLineSpacing(3f, 1f) } }, modifier = Modifier.fillMaxWidth(), update = { view ->
        view.setTextColor(c.textPrimary.toArgb()); view.setLinkTextColor(c.primary.toArgb())
        if (view.tag != text) { renderer.setMarkdown(view, text); view.tag = text }
    })
}
