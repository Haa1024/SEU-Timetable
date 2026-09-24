package com.seu.timetable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius

/**
 * 弹层与表单控件。
 *
 * 为何不使用 Material3 的 `AlertDialog`：
 * 本 App 的外观统一走 `LocalSeuColors`（同一套 token、浅色与深色两套取值）。
 * Material 的对话框使用的是 `MaterialTheme` 的语义色，在深色模式下会与其余界面对不上
 * （其中的 `surface` 属于 Material，而非本 App 的配色）。
 * 自行基于 [Dialog] 拼装卡片，配色即可完全由我们掌控。
 */

// ---------------------------------------------------------------------------
// 对话框骨架
// ---------------------------------------------------------------------------

@Composable
private fun DialogCard(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalSeuColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeuRadius.card))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(SeuRadius.card))
                .padding(vertical = 18.dp, horizontal = 18.dp),
        ) { content() }
    }
}

@Composable
private fun DialogTitle(text: String) {
    val c = LocalSeuColors.current
    Text(text, style = LocalSeuType.current.itemTitle, color = c.textPrimary)
}

@Composable
private fun DialogBody(text: String) {
    val c = LocalSeuColors.current
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = LocalSeuType.current.caption,
        color = c.textSecondary,
        // 限高并允许滚动。引导类正文可能很长（「一键添加」失败那条有五六步 + 一段原因说明），
        // 不限高的话在小屏上整个弹层会被撑出屏幕，连底部的按钮都点不到。
        // 短正文完全不受影响：够不到上限就相当于原来的排版。
        modifier = Modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/**
 * 底部对齐的按钮行：左侧「取消」（文字按钮），右侧主操作。
 * 主操作可以是危险色（删除类）。
 */
@Composable
private fun DialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelText: String = "取消",
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(SeuRadius.button))
                .clickable { onCancel() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(cancelText, style = t.itemTitle, color = c.textSecondary)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(SeuRadius.button))
                .background(if (confirmEnabled) (if (danger) c.danger else c.primary) else c.border)
                .clickable(enabled = confirmEnabled) { onConfirm() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                confirmText,
                style = t.itemTitle,
                color = if (confirmEnabled) c.onPrimary else c.textTertiary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 三种具体弹层
// ---------------------------------------------------------------------------

/** 一个动作。`danger = true` 时文字用危险色。 */
data class MenuAction(
    val label: String,
    val hint: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 动作菜单。用于课表行的「更多」——将五个动作挤入一行小按钮会过于拥挤，
 * 改为竖排列表既便于点击，也可附带一行说明。
 */
@Composable
fun ActionMenuDialog(
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    DialogCard(onDismiss) {
        DialogTitle(title)
        if (subtitle != null) DialogBody(subtitle)
        Spacer(Modifier.height(10.dp))
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                    // 先关闭弹层再执行：动作中可能包含"删除并刷新列表"，
                    // 若弹层仍开启，会覆盖在新界面之上，令用户误以为无响应。
                        onDismiss()
                        action.onClick()
                    }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        action.label,
                        style = t.body,
                        color = if (action.danger) c.danger else c.textPrimary,
                    )
                    if (action.hint != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(action.hint, style = t.caption, color = c.textTertiary)
                    }
                }
            }
        }
    }
}

/** 确认弹层。删除等不可逆操作经由它进行二次确认。 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String = "确定",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogCard(onDismiss) {
        DialogTitle(title)
        DialogBody(body)
        DialogButtons(confirmText, onConfirm, onDismiss, danger = danger)
    }
}

/**
 * 纯信息弹层（仅含一个"知道了"按钮）。
 *
 * 用于反馈同步结果，以及"该课表无法删除/无法同步"这类并非错误但需告知的情况。
 * 它与 [ConfirmDialog] 的区别在于没有取消路径——因为不存在任何需要选择的项，
 * 放置一个"取消"按钮只会让用户困惑"取消究竟会取消什么"。
 *
 * [actionText] / [onAction] 可选：当"知道问题在哪"还不够、需要把用户直接送去能解决问题的地方时
 * （例如把 vivo 用户送进原子组件库），用它们加一个主色按钮。它画在"知道了"右侧；
 * 一旦出现，"知道了"就降级为文字按钮，避免两个实心按钮抢主次。
 * 点击它等价于「关掉弹层并把这件事做了」，调用方不必自己再关一次。
 */
@Composable
fun MessageDialog(
    title: String,
    body: String? = null,
    confirmText: String = "知道了",
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    DialogCard(onDismiss) {
        DialogTitle(title)
        if (body != null) DialogBody(body)
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actionText != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(SeuRadius.button))
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(confirmText, style = t.itemTitle, color = c.textSecondary)
                }
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(SeuRadius.button))
                    .background(c.primary)
                    .clickable {
                        onDismiss()
                        onAction?.invoke()
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                // 没有次要动作时，这个按钮就是原来的"知道了"（[onAction] 为 null，什么也不多做），
                // 外观与从前完全一致。
                Text(actionText ?: confirmText, style = t.itemTitle, color = c.onPrimary)
            }
        }
    }
}

/**
 * 忙碌遮罩：一层覆盖在所有内容之上的半透明黑色蒙版加一个居中小卡片。
 *
 * 为何必须存在，而不能仅将按钮文字改为"正在同步…"：
 * 同步需访问教务接口，可能耗时数秒至十余秒。此期间用户完全可能点击其他位置，
 * 甚至再次发起同步。将 `onDismiss` 设为空使其不可被关闭，
 * 交互上即等价于"这一步完成前不得进行其他操作"——比散落在各处的禁用标记更为可靠。
 */
@Composable
fun BusyDialog(text: String) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Dialog(onDismissRequest = {}) {
        Column(
            Modifier
                .clip(RoundedCornerShape(SeuRadius.card))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(SeuRadius.card))
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = c.primary,
                strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(text, style = t.body, color = c.textSecondary)
        }
    }
}

/** 单行文本输入弹层（重命名、新建课表名、跳周等用）。 */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String = "",
    placeholder: String = "",
    body: String? = null,
    confirmText: String = "确定",
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    DialogCard(onDismiss) {
        DialogTitle(title)
        if (body != null) DialogBody(body)
        Spacer(Modifier.height(12.dp))
        SeuTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            keyboardType = keyboardType,
        )
        DialogButtons(
            confirmText = confirmText,
            onConfirm = { onConfirm(value.trim()) },
            onCancel = onDismiss,
            confirmEnabled = value.isNotBlank(),
        )
    }
}

// ---------------------------------------------------------------------------
// 表单控件
// ---------------------------------------------------------------------------

/**
 * 与设计语言一致的输入框：卡片底 + 1px 描边 + 13sp。
 * [singleLine] = false 时高度 72dp，可容纳三行。
 */
@Composable
fun SeuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (singleLine) 42.dp else 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceSunken)
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = t.body, color = c.textTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                enabled = enabled,
                textStyle = TextStyle(
                    fontSize = t.body.fontSize,
                    lineHeight = t.body.lineHeight,
                    color = c.textPrimary,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (suffix != null) {
            Spacer(Modifier.width(8.dp))
            Text(suffix, style = t.caption, color = c.textTertiary)
        }
    }
}
