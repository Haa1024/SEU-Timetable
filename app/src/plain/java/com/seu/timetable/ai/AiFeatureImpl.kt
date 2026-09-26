package com.seu.timetable.ai

import androidx.compose.runtime.Composable
import com.seu.timetable.data.LoadedBoard

/**
 * 原版（不含 AI）实现：全部为空。
 *
 * 这个文件是 plain 变体与 ai 变体在 AI 上的**唯一差别**。
 * 它存在的意义是让「没有 AI」成为编译期事实：
 * 原版产物里既没有 ai 包的字节码，也没有 assets/ai，也没有 markwon / exifinterface 依赖。
 */
object AiFeatureImpl : AiFeature {

    /** null = 「我的」页不出现 AI 入口 */
    @Composable
    override fun profileSubtitle(): String? = null

    /** 无浮窗。onOpenChange 因此永远不会被调用，外层的展开态恒为 false */
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
    ) = Unit

    /** 无设置页 */
    @Composable
    override fun SettingsScreen(onBack: () -> Unit) = Unit
}
