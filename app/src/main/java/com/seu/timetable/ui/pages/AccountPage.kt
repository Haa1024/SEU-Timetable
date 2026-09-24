package com.seu.timetable.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.seu.timetable.data.CredentialStore
import com.seu.timetable.ui.components.AccountField
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.DangerButton
import com.seu.timetable.ui.components.FieldLabel
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuToggle
import com.seu.timetable.ui.components.SettingRow
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import kotlinx.coroutines.launch

/**
 * 页面 · 校园账号（看登录态 / 去登录 / 保存凭据 / 开自动登录）。
 *
 * 本页是校园身份的**唯一入口**：此前「校园账号」与「重新登录」是两行、却指向同一件事，
 * 用户要点哪一行全凭猜。合并后这一行既显示当前登录态，也负责把用户送进登录页。
 *
 * 保存凭据只是本页的一部分：保存与否都完全可用，不保存时会话过期后走手动登录页，
 * 故「保存」是纯增益项，不是必填项。
 *
 * @param sessionOk 当前校园登录态：true 已登录 / false 未登录 / null 正在检查。
 *   传三态而不是文案，是为了让界面按状态分支，而不是去比对字符串。
 * @param onLogin 进入登录页。由上游提供，登录完成回来后 sessionOk 会随之刷新。
 */
@Composable
fun AccountPage(
    store: CredentialStore,
    sessionOk: Boolean?,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val scope = rememberCoroutineScope()

    val savedUsername by store.savedUsername.collectAsState(initial = null)
    val hasPassword by store.hasPassword.collectAsState(initial = false)
    val autoLogin by store.autoLoginEnabled.collectAsState(initial = false)

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    // 只回填一次学号：用户已在修改时不覆盖其输入。
    // 密码永不回填：即便可解密也不显示，改密码须重新输入，避免密码暴露在屏幕上。
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(savedUsername) {
        val saved = savedUsername
        if (!prefilled && !saved.isNullOrBlank()) {
            username = saved
            prefilled = true
        }
    }

    val canSave = username.isNotBlank() && password.isNotEmpty()

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(6.dp)) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("校园账号", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(30.dp))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 登录态 + 登录入口 ----
        // 放在最上面：用户点进「校园账号」十有八九是来确认"我到底登录了没 / 要不要重登"。
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = when (sessionOk) {
                        true -> "已登录"
                        false -> "未登录"
                        null -> "正在检查登录态…"
                    },
                    subtitle = when (sessionOk) {
                        true -> "课表会话有效，导入课表时无需再次输入账号密码"
                        false -> "导入课表时需要先登录校园账号"
                        null -> "稍候片刻"
                    },
                    onClick = onLogin,
                    trailing = {
                        // 三种状态三种呈现：已登录给"重登"、未登录给"去登录"、检查中不给按钮
                        //（给了也没用，点下去只是把用户扔进一个还不知道要不要登的页面）。
                        if (sessionOk != null) {
                            Text(
                                if (sessionOk) "重新登录" else "去登录",
                                style = t.caption,
                                color = c.primary,
                            )
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- 说明：这条功能换来了什么、代价是什么 ----
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("为什么要保存账号密码", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "教务系统的登录态会失效。不保存时，每次失效都需要重新走一遍登录页；" +
                        "保存后，App 会在失效时自动续期会话，打开即可使用。",
                    style = t.caption,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text("密码存储方式", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "密码使用 Android 系统密钥库（Keystore）中的密钥加密后再写入本机，" +
                        "密钥不出硬件，App 亦无法读取。学号明文保存，用于界面显示以便确认。" +
                        "换机或清除应用数据后需重新输入。",
                    style = t.caption,
                    color = c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("账号密码")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                FieldLabel("学号 / 工号")
                AccountField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "例如 213xxxxxx",
                    keyboardType = KeyboardType.Text,
                    masked = false,
                )
                Spacer(Modifier.height(14.dp))
                FieldLabel("密码")
                AccountField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = if (hasPassword) "留空表示不修改（需重新输入方可更改）" else "统一身份认证密码",
                    keyboardType = KeyboardType.Password,
                    masked = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (hasPassword) "已保存账号：${savedUsername.orEmpty()}" else "尚未保存账号密码",
                    style = t.micro,
                    color = if (hasPassword) c.success else c.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("自动登录")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "登录态失效时自动续期",
                    subtitle = if (hasPassword) {
                        "启动 App 时检查，失效时使用保存的账号续期"
                    } else {
                        "需要先保存账号密码"
                    },
                    trailing = {
                        SeuToggle(
                            checked = autoLogin && hasPassword,
                            onCheckedChange = { scope.launch { store.setAutoLogin(it) } },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "保存",
            enabled = canSave,
            onClick = {
                scope.launch {
                    store.save(username.trim(), password)
                    // 保存成功后即开启自动登录——用户已走到此步，
                    //   意图明确，再令其多拨一次开关属多余。
                    store.setAutoLogin(true)
                    password = ""      // 立刻从内存里抹掉，不留在一个还活着的输入框里
                    status = "已保存。下次登录态失效时会自动续期。"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(status, style = t.caption, color = c.success)
        }

        Spacer(Modifier.height(16.dp))
        DangerButton(
            text = "清除已保存的账号密码",
            onClick = {
                scope.launch {
                    store.clear()
                    username = ""
                    password = ""
                    prefilled = true      // 别让 Flow 再把它回填回来
                    status = "已清除，并已关闭自动登录。"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "清除后不影响当前登录态，仅在下次失效时需要手动登录一次。",
            style = t.caption,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(24.dp))
    }
}
