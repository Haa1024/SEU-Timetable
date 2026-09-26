package com.seu.timetable.ai

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.Segmented
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType

@Composable
fun AiSettingsPage(vm: AiViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsState()
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    var config by remember(ui.ready) { mutableStateOf(ui.config) }
    // The key is intentionally excluded from SavedState/Bundle; persistence uses Android Keystore.
    var apiKey by remember(ui.ready) { mutableStateOf(vm.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var temperature by remember(ui.ready) { mutableStateOf(config.temperature.toString()) }
    var tokens by remember(ui.ready) { mutableStateOf(config.maxTokens.toString()) }
    var turns by remember(ui.ready) { mutableStateOf(config.contextTurns.toString()) }
    fun withConfig(action: (AiConfig, String) -> Unit) {
        try { action(config.copy(temperature = temperature.toDoubleOrNull() ?: error("请输入有效温度"), maxTokens = tokens.toIntOrNull() ?: error("请输入整数输出长度"), contextTurns = turns.toIntOrNull() ?: error("请输入整数上下文轮数")).validated(), apiKey) }
        catch (e: Exception) { vm.settingsError(e.message ?: "请检查参数") }
    }
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AiAction("‹", "返回", onClick = onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("AI 模型设置", style = t.navTitle, color = c.textPrimary) }
            Spacer(Modifier.width(32.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("连接你自己的模型", style = t.itemTitle, color = c.textPrimary)
            Text("用于聊天与图片问答", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
            SectionLabel("接口配置"); Spacer(Modifier.height(8.dp))
            SeuCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Segmented(listOf("deepseek", "compatible"), config.provider, { if (it == "deepseek") "DeepSeek" else "自定义兼容接口" }, { config = if (it == "deepseek") config.copy(provider = it, baseUrl = AiConfig().baseUrl, model = "deepseek-flash", vision = true) else config.copy(provider = it) }, Modifier.fillMaxWidth())
                AiField("API 地址", config.baseUrl, { config = config.copy(baseUrl = it) }, keyboard = KeyboardType.Uri)
                Note("可填写基础地址、/v1 或完整 /chat/completions 地址。")
                Column {
                    AiField("API Key", apiKey, { apiKey = it }, masked = !showKey)
                    Text(if (showKey) "隐藏 Key" else "显示 Key", color = c.primary, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).clickable { showKey = !showKey }.padding(6.dp))
                }
                AiCheck("在本机记住 API Key", "使用 Android Keystore 加密保存；未勾选仅在本次进程中保留。", config.rememberKey) { config = config.copy(rememberKey = it) }
                AiField("模型名称", config.model, { config = config.copy(model = it) })
                AiCheck("启用图片输入", "需要视觉模型，默认 deepseek-flash 支持图片。", config.vision) { config = config.copy(vision = it) }
                AiCheck("深度思考", "仅向 DeepSeek 接口发送此参数。", config.thinking) { config = config.copy(thinking = it) }
            } }
            Spacer(Modifier.height(20.dp)); SectionLabel("对话参数"); Spacer(Modifier.height(8.dp))
            SeuCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AiField("温度", temperature, { temperature = it }, keyboard = KeyboardType.Decimal)
                Note("范围 0–2；DeepSeek 思考模式下不使用温度。")
                AiField("最大输出长度（Tokens）", tokens, { tokens = it }, keyboard = KeyboardType.Number)
                AiField("携带最近几轮对话", turns, { turns = it }, keyboard = KeyboardType.Number)
                AiField("系统提示词", config.systemPrompt, { config = config.copy(systemPrompt = it.take(8000)) }, multiline = true)
            } }
            Spacer(Modifier.height(16.dp)); Note("聊天和照片保存在本机，发送时提交最近几轮聊天上下文。图片会随上下文再次发送。")
            if (ui.settingsStatus.isNotBlank()) Text(ui.settingsStatus, fontSize = 12.sp, color = if (ui.settingsStatus.startsWith("连接成功") || ui.settingsStatus.startsWith("设置已保存")) c.primary else c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
            Text(if (ui.testing) "正在测试…" else "测试连接", color = c.primary, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).border(1.dp, c.border, RoundedCornerShape(12.dp)).clickable(enabled = ui.ready && !ui.testing) { withConfig(vm::testSettings) }.padding(14.dp))
        }
        Box(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
            PrimaryButton("保存设置", { withConfig(vm::saveSettings) }, Modifier.fillMaxWidth(), enabled = ui.ready)
        }
    }
}

@Composable
private fun Note(text: String) { Text(text, fontSize = 10.sp, lineHeight = 16.sp, color = LocalSeuColors.current.textTertiary) }

@Composable
private fun AiCheck(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked = value, onCheckedChange = onChange, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 6.dp).weight(1f).clickable { onChange(!value) }) {
            Text(title, fontSize = 12.sp, color = LocalSeuColors.current.textPrimary)
            Spacer(Modifier.height(4.dp)); Note(hint)
        }
    }
}

@Composable
private fun AiField(label: String, value: String, onChange: (String) -> Unit, masked: Boolean = false, multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    val c = LocalSeuColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(bottom = 7.dp))
        BasicTextField(value = value, onValueChange = onChange, singleLine = !multiline, minLines = if (multiline) 4 else 1, maxLines = if (multiline) 7 else 1,
            textStyle = TextStyle(fontSize = 13.sp, color = c.textPrimary, lineHeight = 19.sp), cursorBrush = SolidColor(c.primary),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboard),
            modifier = Modifier.fillMaxWidth().background(c.surfaceSunken, RoundedCornerShape(10.dp)).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(12.dp).semantics { contentDescription = label })
    }
}
