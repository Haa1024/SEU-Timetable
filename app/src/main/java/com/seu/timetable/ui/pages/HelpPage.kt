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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.Tag
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType

/**
 * 页面 · 使用帮助。
 *
 * 内容按用户实际的困惑顺序组织：先讲课表从哪来（这是新用户第一个问题），
 * 再讲登录与同步（最容易误解成"必须登录才能用"），最后是界面操作与常见疑问。
 * 全部为静态文案，不依赖任何状态，因此无需参数。
 */
@Composable
fun HelpPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

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
            Text("使用帮助", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(30.dp))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 一句话总览 ----
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("功能总览", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "课表是本机文件，无需登录即可查看。校园账号仅在导入或同步课表时需要用到。" +
                        "所有修改均保存在本机，不依赖网络。",
                    style = t.caption,
                    color = c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("课表的来源")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "从教务导入",
                    "「导入 / 新建课表」→「从教务导入」，选学期后会把该学期的课程下载到本机。" +
                        "这一步需要登录校园账号（学号 + 统一身份认证密码）。",
                )
                HelpDivider()
                HelpEntry(
                    "新建空白课表",
                    "自行编排课表。需先确定第一周的起始日期（必须是周一），之后在课表页逐门添加。" +
                        "教务未排的讲座、辅导课均可加入。",
                )
                HelpDivider()
                HelpEntry(
                    "复制已有课表",
                    "以某张课表为模板派生一张新课表，作息与周数一并复制，适合同一学期建立两张对照表。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("校园账号与登录")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "什么时候需要登录",
                    "仅在「导入课表」和「从教务同步」时需要。查看、编辑、切换课表均无需登录，" +
                        "断网也可正常使用。",
                )
                HelpDivider()
                HelpEntry(
                    "保存账号密码有什么用",
                    "登录态会过期。保存后，App 会在启动时静默续期会话，下次导入无需手动登录。" +
                        "密码经系统密钥库加密后存储在本机；不保存也可正常使用，仅在过期时需手动登录一次。",
                )
                HelpDivider()
                HelpEntry(
                    "登录失败或要求验证码",
                    "学校认证服务带风控，密码连续输错后会要求验证码。此时请稍后再试，" +
                        "避免在短时间内反复重试。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("从教务同步")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "不会自动同步",
                    "课表下载到本机后即由你掌握，App 不会在后台改写它。" +
                        "退课或改课后如需更新，需手动执行一次同步。",
                )
                HelpDivider()
                HelpEntry(
                    "在哪同步",
                    "「我的」→「从教务同步当前课表」，或「我的课表」→ 某张课表右侧菜单。" +
                        "只有从教务导入的课表能同步，自建课表不会显示该入口。",
                )
                HelpDivider()
                HelpEntry(
                    "同步会覆盖什么",
                    "课程名、教师、星期节次、周次以教务为准，会被覆盖；" +
                        "你设过的颜色、学分、备注会保留，因为教务没有这几个字段。作息时间和课表名也不会被改动。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("看课表")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "切换周次",
                    "点击顶部「第 X 周」可直接输入目标周数跳转，左右箭头逐周切换。",
                )
                HelpDivider()
                HelpEntry(
                    "半透明的课块",
                    "表示该课程不在当前查看的这一周。可在「我的课表」→ 该课表 → 「课表设置」中关闭此显示。",
                )
                HelpDivider()
                HelpEntry(
                    "左侧的时刻",
                    "是每节课的起止时刻，来自该课表的作息表。自建课表的作息可逐节填写。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("桌面小组件")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "怎么加",
                    "「我的」→「桌面小组件」→「添加到桌面」，选好尺寸后按桌面的提示操作：" +
                        "多数机型会直接弹「是否添加」的确认框；部分小米机型会把你送到小部件中心，" +
                        "在那里找到「SEU 课表」拖到桌面上即可。若最后弹出「桌面上还没有加上" +
                        "小组件」，说明这台机器的桌面拦住了应用主动添加，按弹层里的步骤手动加。",
                )
                HelpDivider()
                HelpEntry(
                    "为什么「一键添加」没反应",
                    "部分 ROM 的桌面不允许应用主动添加，而且拒绝得很安静——不弹窗、不报错，" +
                        "看起来就像点了没反应。已知的原因各家不同：实测 ColorOS 15 会直接关闭" +
                        "确认页（它的白名单只放行系统应用与自家应用）；vivo 桌面只接受接入其" +
                        "原子组件平台的应用，未接入时一键添加必然无效；小米的「创建桌面快捷方式」" +
                        "权限被关掉时会静默失败。失败后 App 会按你的机型给出手动添加的步骤，" +
                        "见下一条。",
                )
                HelpDivider()
                HelpEntry(
                    "为什么它藏在「插件」里",
                    "realme UI / ColorOS 自 14 版起把桌面上的「卡片」与「插件」合并为卡片中心，" +
                        "第三方应用的小组件统一归入「全部卡片」最底部的「插件」分组，与 App 本身无关。" +
                        "手动添加的完整路径：两指捏合桌面 → 左下角「卡片」→ 切到「全部卡片」" +
                        "→ 一直滑到最底部点「插件」→ 找到「SEU 课表」。",
                )
                HelpDivider()
                HelpEntry(
                    "显示什么",
                    "今天的课程，已上完的自动隐藏；不需要登录，也不需要联网。" +
                        "两种尺寸：2×2 显示时间与课名，4×2 另加教室与周次。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("编辑课程")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "改一门课",
                    "点击课块进入课程详情，底部提供「编辑」与「删除」。所有来源的课表均可修改，" +
                        "包括从教务导入的课表；修改的是本机副本，不影响教务系统。",
                )
                HelpDivider()
                HelpEntry(
                    "能改哪些内容",
                    "课程名、教师、教室、时间段（可加多个）、上课周次、颜色、学分、备注。",
                )
                HelpDivider()
                HelpEntry(
                    "快速换色",
                    "课程详情右上角有颜色圆点，点击即更换并立即保存，无需进入编辑页。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("课表设置")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "每张课表独立设置",
                    "学期信息、周数、作息时间均为每张课表各自持有，互不影响。" +
                        "可在「课表设置」中从其它课表导入一份设置作为起点，导入后仍各自独立。",
                )
                HelpDivider()
                HelpEntry(
                    "学期信息能不能改",
                    "自建课表可以改学期名、总周数和第一周起始日期；从教务导入的课表学期信息以教务为准，只读。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("数据与隐私")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                HelpEntry(
                    "数据存在哪",
                    "全部存储在本机。卸载应用或清除应用数据后，课表与保存的账号会一并删除。",
                )
                HelpDivider()
                HelpEntry(
                    "退出登录会删课表吗",
                    "不会。退出仅清除校园登录态并关闭自动登录，已下载到本机的课表不受影响。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("其它问题", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请先确认「我的」页中的登录状态，以及当前课表是否为目标课表。" +
                        "多数「数据不对」的情况是切换到了另一张课表。",
                    style = t.caption,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("提示", c.primary, c.primarySoft)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "顶部「我的课表」里能切换和整理所有课表",
                        style = t.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** 帮助条目：小标题 + 说明正文。 */
@Composable
private fun HelpEntry(title: String, body: String) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = t.body, color = c.textPrimary)
        Spacer(Modifier.height(5.dp))
        Text(body, style = t.caption, color = c.textSecondary)
    }
}

/** 帮助条目之间的细分隔线（比 RowDivider 更轻）。 */
@Composable
private fun HelpDivider() {
    val c = LocalSeuColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.border)
    )
}
