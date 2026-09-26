package com.seu.timetable.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                // 设置页已改由 [SettingsScreen] 的原生页承担（缘由见那里）。
                // 共享层仍按老协议把 settingsVisible 传进来，因为它并不知道设置由谁画；
                // 本实现恒置 false——壳一旦进入设置态就会铺满屏幕，把原生页整个盖住。
                settingsVisible = false,
                open = open,
                onOpenChange = onOpenChange,
                onBack = onBack,
                onChanged = onChanged,
            )
        }
    }

    /**
     * `Screen.AiSettings` 对应的整页。
     *
     * 两种界面形态共用这一页，设置不再交给 WebView 壳去画。原因是那个做法本身有死锁：
     * 壳由 [Overlay] 渲染，而 [Overlay] 在未启用时直接返回——于是「未启用」时点进来
     * 既没有原生页、也没有壳，只剩一片空白。**设置页不能由一个可能被门禁拦住的层来渲染。**
     *
     * 总开关也在这里（[AiSettingsPage] 顶部）。默认关的功能，开关必须始终可及。
     */
    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        AiSettingsPage(aiViewModel(), onBack = onBack)
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
