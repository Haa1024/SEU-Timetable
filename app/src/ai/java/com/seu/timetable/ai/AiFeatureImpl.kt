package com.seu.timetable.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seu.timetable.BuildConfig
import com.seu.timetable.data.LoadedBoard

/**
 * 接入 AI 的实现。同时承担两道闸门：
 *
 *  - **外层 · 编译期**：只有 ai 变体存在这个文件，原版连它的字节码都不生成；
 *  - **内层 · 运行时（默认关）**：见 [AiUiState.enabled]。
 *
 * 两道闸门解决的不是同一件事：外层决定「代码是否进包」——这是体积与隐私的硬边界，
 * 运行时怎么配也弥补不了；内层决定「进了包的代码是否生效」——让用户拥有最终决定权。
 * 代码在包里不等于就该跑起来。
 */
object AiFeatureImpl : AiFeature {

    @Composable
    override fun profileSubtitle(): String? {
        val ui by aiViewModel().state.collectAsState()
        if (!ui.enabled) return "未启用 · 点此打开"
        return "${ui.config.model} · ${if (ui.configured) "已配置" else "未填写 API Key"}"
    }

    @Composable
    override fun Overlay(
        loaded: LoadedBoard,
        week: Int,
        visible: Boolean,
        settingsVisible: Boolean,
        open: Boolean,
        onOpenChange: (Boolean) -> Unit,
        onOpenSettings: () -> Unit,
        onBack: () -> Unit,
        onChanged: () -> Unit,
    ) {
        val model = aiViewModel()
        val ui by model.state.collectAsState()
        // 运行时闸门：未启用就直接不渲染。于是不会有浮窗、不会有请求，
        // 课表与照片也就不会离开本机。
        if (!ui.enabled) return

        // AI_UI 由构建参数注入（-PaiUi=compose|webview），默认 webview。
        // 这是开发期的界面选型，不是「AI 是否启用」——后者由上面的 enabled 决定。
        if (BuildConfig.AI_UI == "compose") {
            AiChatOverlay(model, visible = visible, onSettings = onOpenSettings)
        } else {
            AiWebOverlay(
                vm = model,
                loaded = loaded,
                week = week,
                visible = visible,
                settingsVisible = settingsVisible,
                open = open,
                onOpenChange = onOpenChange,
                onBack = onBack,
                onChanged = onChanged,
            )
        }
    }

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        // 刻意不判 enabled：用户正是要靠这一页把 AI 打开。
        if (BuildConfig.AI_UI == "compose") {
            AiSettingsPage(aiViewModel(), onBack = onBack)
        } else {
            // WebView 版的设置界面画在浮窗内部（由 settingsVisible 驱动），不占独立页面。
            Box(Modifier)
        }
    }
}

/**
 * 取本变体共享的 AiViewModel。
 *
 * ViewModelStore 按类型复用，所以这里拿到的与原先直接 `viewModel()` 的是同一个实例，
 * 浮窗与设置页不会各自持有一份状态。
 */
@Composable
private fun aiViewModel(): AiViewModel = viewModel()
