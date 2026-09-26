package com.seu.timetable.ai

import androidx.compose.runtime.Composable
import com.seu.timetable.data.LoadedBoard

/**
 * AI 能力的唯一接入点。
 *
 * 共享层（`app/src/main`）只认识这一个接口，不认识任何具体实现。实现由 flavored
 * source set 提供：`app/src/plain` 给空实现，`app/src/ai` 给真实现；两者提供同名的
 * `AiFeatureImpl`，AGP 保证每个变体只会看到一个。
 *
 * 这样划分之后，「不接入 AI」是一个**编译期事实**而不是运行时开关：
 * 原版 APK 里不含 ai 包、不含 `assets/ai`、不含 markwon/exifinterface，
 * 也就不存在"忘了关"这回事。
 *
 * 连带的一条约束：共享层里**不允许**出现 `if (变体 == "ai")` 这类分支。
 * 一旦有了分支，两套行为就有了各自演化的可能，迟早漂移。
 * 能力的有无由变体决定，不由代码判断。
 */
interface AiFeature {

    /**
     * 「我的」页 AI 那一行的副标题。
     *
     * 返回 null 表示不展示这个入口（原版行为）——界面整块隐藏，
     * 而不是留一个点了没反应的灰按钮，那种"看起来有、其实是坏的"最容易被当成 Bug。
     */
    @Composable fun profileSubtitle(): String?

    /**
     * 挂在课表页上的聊天浮窗。
     *
     * 参数看着多，是因为要同时容纳两套既有的 UI 实现：Compose 原生浮窗（进设置走页面导航）
     * 与 WebView 浮窗（设置在浮窗内部切换）。差异在这个接口里吸收掉，
     * 共享层因此不需要知道当前用的是哪一套。
     *
     * @param loaded 当前课表，AI 读取并改写它
     * @param week 当前周次
     * @param visible 是否处于可见页面（课表页 且 不在新手引导中）
     * @param settingsVisible 设置态是否盖在浮窗之上（WebView 浮窗用）
     * @param open 浮窗是否展开。展开时会遮住课表，需由调用方锁住横向滚动
     * @param onOpenChange 浮窗展开态变化。原版不会触发
     * @param onOpenSettings 进入设置（Compose 浮窗用）
     * @param onBack 从设置态返回课表
     * @param onChanged 课表被 AI 改写后通知外层重新加载
     */
    @Composable fun Overlay(
        loaded: LoadedBoard,
        week: Int,
        visible: Boolean,
        settingsVisible: Boolean,
        open: Boolean,
        onOpenChange: (Boolean) -> Unit,
        onOpenSettings: () -> Unit,
        onBack: () -> Unit,
        onChanged: () -> Unit,
    )

    /**
     * `Screen.AiSettings` 对应的整页。
     *
     * WebView 版在此返回空占位——它的设置界面画在浮窗内部，不占独立页面。
     */
    @Composable fun SettingsScreen(onBack: () -> Unit)
}
