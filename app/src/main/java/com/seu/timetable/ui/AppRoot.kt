package com.seu.timetable.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.seu.timetable.data.CasAuthClient
import com.seu.timetable.data.CasLoginResult
import com.seu.timetable.data.CredentialStore
import com.seu.timetable.data.LoadedBoard
import com.seu.timetable.data.SessionManager
import com.seu.timetable.data.SettingsStore
import com.seu.timetable.data.SyncOutcome
import com.seu.timetable.data.TimetableRepository
import com.seu.timetable.domain.BoardIndex
import com.seu.timetable.domain.BoardMeta
import com.seu.timetable.domain.Course
import com.seu.timetable.domain.CourseColorAssigner
import com.seu.timetable.reminder.ReminderScheduler
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.BusyDialog
import com.seu.timetable.ui.components.ConfirmDialog
import com.seu.timetable.ui.components.HomeTab
import com.seu.timetable.ui.components.MessageDialog
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.RowDivider
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.Segmented
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuTabBar
import com.seu.timetable.ui.components.SeuToggle
import com.seu.timetable.ui.components.SettingRow
import com.seu.timetable.ui.pages.AccountPage
import com.seu.timetable.ui.pages.BoardSettingsPage
import com.seu.timetable.ui.pages.BoardsPage
import com.seu.timetable.ui.pages.CourseDetailPage
import com.seu.timetable.ui.pages.CourseEditPage
import com.seu.timetable.ui.pages.HelpPage
import com.seu.timetable.ui.pages.NewBoardPage
import com.seu.timetable.ui.pages.ProfilePage
import com.seu.timetable.ui.pages.TimetablePage
import com.seu.timetable.ui.pages.TodayPage
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuRadius
import com.seu.timetable.ui.theme.SeuTheme
import com.seu.timetable.ui.theme.ThemeMode
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 主界面的几个二级页。用状态机而非 navigation-compose，页面数量少，不值得多一个依赖。
 *
 * 只剩一个编辑课程的目标（[EditCourse]）：教务导入的课表与自建课表共用同一个编辑页
 * （见 `CourseEditPage`）。曾按"能否整体编辑"拆成两个目标，该前提已不成立。
 */
private sealed interface Screen {
    data object Home : Screen
    data class CourseDetail(val courseId: String) : Screen

    /** 课程编辑页。courseId = null 表示新增 */
    data class EditCourse(val courseId: String?) : Screen

    data object Reminder : Screen
    data object Account : Screen

    /** 静态使用说明，从「我的」页进入 */
    data object Help : Screen
}

/**
 * 盖在主界面之上的整屏页。
 *
 * 课表库相关的三页（列表 / 导入新建 / 课表设置）在一张课表都没有时也必须能打开，
 * 否则用户无法导入第一张课表；而 [LibraryState] 的 Empty 状态没有主界面，
 * 因此这几页不能挂在主界面内部，只能挂在它外面。
 */
private sealed interface Overlay {
    data object Boards : Overlay
    data object NewBoard : Overlay

    /** 课表设置：学期骨架（自建可改）+ 作息时间 */
    data class BoardSettings(val boardId: String) : Overlay
}

/**
 * 课表库状态。
 *
 * 没有 `NeedLogin` 状态：课表存放在本地磁盘，未登录只意味着暂时不能导入新学期，
 * 不构成阻塞。把"未登录"从阻塞态降级为普通提示，是本地化改造的核心行为变化。
 */
private sealed interface LibraryState {
    data object Loading : LibraryState

    /** 本地一张课表都没有 */
    data object Empty : LibraryState

    data class Ready(val board: LoadedBoard) : LibraryState

    data class Failed(val message: String) : LibraryState
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val credentials = remember { CredentialStore(context) }
    val repo = remember { TimetableRepository(context) }
    val scope = rememberCoroutineScope()

    val themeName by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM.name)
    val themeMode = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.SYSTEM)

    // 凭据状态提到这里：它决定「我的」页那一行怎么写，也决定导入时要不要自动登。
    val autoLogin by credentials.autoLoginEnabled.collectAsState(initial = false)
    val savedUsername by credentials.savedUsername.collectAsState(initial = null)
    val hasSavedPassword by credentials.hasPassword.collectAsState(initial = false)

    var state by remember { mutableStateOf<LibraryState>(LibraryState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var boardIndex by remember { mutableStateOf(BoardIndex.EMPTY) }
    var courseCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    /** 校园会话是否有效。null 表示尚未探测。不决定 App 能否使用。 */
    var sessionOk by remember { mutableStateOf<Boolean?>(null) }

    // ---- 手动同步的状态 ----
    //
    // 这几个状态放在 AppRoot 而非 BoardsPage：同步需要访问教务接口、会话过期时要弹
    // 登录页、完成后还要刷新课表库与主界面，只有 AppRoot 能覆盖这些职责。
    // BoardsPage 只负责发出一次同步请求（见 [BoardsPage] 的 onSync）。
    /** 待用户确认的课表：同步会覆盖课程数据，先确认再执行 */
    var syncConfirmFor by remember { mutableStateOf<BoardMeta?>(null) }

    /** 同步进行中，用于压一层不可点掉的遮罩，避免重复触发 */
    var syncBusy by remember { mutableStateOf(false) }

    /** 同步结果提示。null 表示不显示 */
    var syncResult by remember { mutableStateOf<String?>(null) }

    /** 会话过期时记录待同步的课表，登录成功后自动接上 */
    var pendingSyncId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        reloadKey++
    }

    suspend fun refreshBoardList() {
        boardIndex = repo.index()
        courseCounts = repo.courseCounts()
    }

    fun openBoards() {
        scope.launch {
            refreshBoardList()
            overlay = Overlay.Boards
        }
    }

    // ---- 载入本地课表 ----
    LaunchedEffect(reloadKey) {
        // 已显示课表时不切回 Loading：切一次会把 [MainScaffold] 移出组合再挂回，
        // 其内部 remember（当前 tab、正在查看的课程详情）会全部销毁重建，
        // 表现为"在课程详情里换色后被弹回首页"。
        // 本地课表重读只需几毫秒，Loading 仅对首次打开、尚无内容时有意义。
        if (state !is LibraryState.Ready) state = LibraryState.Loading
        state = try {
            val idx = repo.index()
            boardIndex = idx
            val activeId = idx.effectiveActiveId
            val loaded = if (activeId == null) null else repo.open(activeId)
            if (loaded == null) LibraryState.Empty else LibraryState.Ready(loaded)
        } catch (e: Exception) {
            readLibraryFailure(e)
        }
    }

    // ---- 探测校园会话（不阻塞界面）----
    //
    // key 用 Unit 而非 reloadKey：会话状态与本地课表的变更无关，
    // 挂在 reloadKey 上会导致每加一门课都请求一次教务接口。
    // 登录成功后的刷新由 loginLauncher 的回调负责。
    LaunchedEffect(Unit) {
        sessionOk = runCatching { repo.hasSession() }.getOrDefault(false)
    }

    // ---- 重排上课提醒 ----
    //
    // 全靠这一处「进 App 就重排」：闹钟只排下一个，重排是无状态的，
    // 所以课表改了、作息改了、系统清过闹钟（重启 / 清缓存 / 换时区）之后都能自动接上。
    // 未开启提醒时该调用自身会取消已排的闹钟，等于零开销。
    LaunchedEffect(Unit) {
        ReminderScheduler.reschedule(context)
    }

    // ---- 启动时静默续期登录态 ----
    //
    // 续期只需取得 auth 域的 TGT，用 OkHttp 即可完成（见 CasAuthClient），无需打开界面。
    // 取得 TGT 后，用户下次点"导入课表"时门户那条链会静默发票并建好课表会话。
    //
    // 启动时有意不弹登录页：查看本地课表不需要登录，为"以后可能用得上"的会话
    // 弹登录页得不偿失。也有意不重试：认证服务端有风控，密码输错即停止（见 LoginActivity）。
    LaunchedEffect(autoLogin, hasSavedPassword, sessionOk) {
        if (!autoLogin || !hasSavedPassword || sessionOk != false) return@LaunchedEffect
        val creds = credentials.load() ?: return@LaunchedEffect
        val result = runCatching { CasAuthClient().login(creds.username, creds.password) }
            .getOrElse { CasLoginResult.Failed(it.message ?: "异常") }
        DebugLog.i("启动续期：${result::class.simpleName}")
    }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 登录**只为了导入 / 同步**：回来后重新探测会话即可，本地课表一个字都不动。
        scope.launch {
            sessionOk = if (result.resultCode == Activity.RESULT_OK) {
                runCatching { repo.hasSession() }.getOrDefault(true)
            } else {
                false
            }
        }
    }

    fun launchLogin() {
        loginLauncher.launch(LoginActivity.intent(context, auto = autoLogin))
    }

    /**
     * 从教务重新拉取并覆盖该课表的课程数据。
     *
     * 不放在 [BoardsPage] 中直接调用仓库：同步还涉及会话过期时弹登录页、
     * 刷新课表库列表、让主界面重载，这些只有本层能处理，页面只需发出请求。
     *
     * 不会自动重试：认证服务端有风控，一次失败即要求验证码（见 LoginActivity），
     * 因此失败时如实报告原因，由用户决定下一步。
     */
    fun runSync(boardId: String) {
        scope.launch {
            syncBusy = true
            when (val r = repo.syncFromEhall(boardId)) {
                is SyncOutcome.Success -> {
                    val name = boardIndex.meta(boardId)?.name ?: "这张课表"
                    refreshBoardList()
                    reload()
                    syncResult = if (r.changed) {
                        "已从教务重新拉取「$name」。\n\n" +
                            "现有 ${r.courseCount} 门课、${r.sessionCount} 个时间块" +
                            (if (r.unplacedCount > 0) "，另有 ${r.unplacedCount} 门未排课" else "") +
                            "。\n\n本地修改过的颜色、学分、备注均已保留。"
                    } else {
                        "「$name」与教务数据一致，没有变化。\n\n" +
                            "本地改动未被覆盖（仍为 ${r.courseCount} 门课）。"
                    }
                }

                SyncOutcome.NeedLogin -> {
                    // 记下目标：用户走完登录页回来后**自动接上**这一次同步，
                    // 而不是让他再去菜单里点一遍（点的时候会话可能又过期了）。
                    pendingSyncId = boardId
                    launchLogin()
                }

                is SyncOutcome.NotSyncable -> syncResult = r.message

                is SyncOutcome.Failed -> syncResult =
                    "同步失败：${r.message}\n\n本地课表未被修改。"
            }
            syncBusy = false
        }
    }

    // ---- 会话恢复后自动接上被打断的同步 ----
    //
    // 用 LaunchedEffect 而非在登录回调里直接调用 runSync：后者会形成循环依赖
    // （runSync 需先于回调声明，而回调又依赖 launchLogin）。以状态变化驱动的写法
    // 没有顺序约束，同时覆盖了用户从其它入口登录成功的情况。
    LaunchedEffect(sessionOk, pendingSyncId) {
        val target = pendingSyncId ?: return@LaunchedEffect
        if (sessionOk != true) return@LaunchedEffect
        // 先清空待办再执行：runSync 内部若再次发现会话过期会重新写回该字段，
        // 因此清空这一步不会把自己再次触发。
        pendingSyncId = null
        runSync(target)
    }

    SeuTheme(themeMode = themeMode) {
        val c = LocalSeuColors.current
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // ---- 盖层页的返回键 ----
            //
            // 必须挂在这一层：盖层页显示时 MainScaffold 已不在组合中（见下方 if/else），
            // 挂在它内部的 BackHandler 不会生效，系统返回键会落到 Activity 的默认行为
            // 而直接退回桌面。
            //
            // 层级：Boards 是盖层的根，再返回才退出盖层回到主界面；
            // NewBoard 与 BoardSettings 均由 Boards 进入，故返回 Boards。
            BackHandler(enabled = overlay != null) {
                overlay = when (overlay) {
                    is Overlay.NewBoard -> Overlay.Boards
                    is Overlay.BoardSettings -> Overlay.Boards
                    else -> null
                }
            }

            val ov = overlay
            if (ov != null) {
                when (ov) {
                    Overlay.Boards -> BoardsPage(
                        index = boardIndex,
                        courseCounts = courseCounts,
                        onBack = { overlay = null },
                        onActivate = { id ->
                            scope.launch {
                                repo.activate(id)
                                overlay = null
                                reload()
                            }
                        },
                        onRename = { id, name -> scope.launch { repo.rename(id, name); refreshBoardList() } },
                        onDuplicate = { id -> scope.launch { repo.duplicate(id); refreshBoardList() } },
                        onDelete = { id -> scope.launch { repo.remove(id); refreshBoardList(); reload() } },
                        onEditSchedule = { id -> overlay = Overlay.BoardSettings(id) },
                        // 同步会覆盖课程数据，所以先弹确认（见文件末尾的 ConfirmDialog）
                        onSync = { id -> syncConfirmFor = boardIndex.meta(id) },
                        onNewBoard = { overlay = Overlay.NewBoard },
                    )

                    Overlay.NewBoard -> NewBoardPage(
                        repo = repo,
                        onNeedLogin = { launchLogin() },
                        onCreated = { id ->
                            scope.launch { repo.activate(id); overlay = null; reload() }
                        },
                        onBack = { overlay = Overlay.Boards },
                    )

                    is Overlay.BoardSettings -> BoardSettingsPage(
                        repo = repo,
                        boardId = ov.boardId,
                        // 显示开关这类即时改动不关页面，但主界面的课表须静默重载，
                        // 等盖层关掉时看到的就是新设置。
                        onMetaChanged = { scope.launch { refreshBoardList(); reload() } },
                        onSaved = {
                            scope.launch {
                                refreshBoardList()
                                overlay = Overlay.Boards
                                reload()
                            }
                        },
                        onBack = { overlay = Overlay.Boards },
                    )
                }
            } else {
                when (val s = state) {
                    is LibraryState.Loading -> StatusView("正在读取本地课表…", showBar = true)

                    is LibraryState.Empty -> EmptyLibraryView(
                        onNewBoard = { overlay = Overlay.NewBoard },
                        onLogin = { launchLogin() },
                        sessionOk = sessionOk,
                    )

                    is LibraryState.Failed -> StatusView(
                        title = "读取课表失败",
                        body = s.message,
                        actionText = "重试",
                        onAction = { scope.launch { reload() } },
                    )

                    is LibraryState.Ready -> MainScaffold(
                        loaded = s.board,
                        settings = settings,
                        credentials = credentials,
                        repo = repo,
                        scope = scope,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            scope.launch { settings.setThemeMode(mode.name) }
                        },
                        accountSubtitle = when {
                            hasSavedPassword && autoLogin ->
                                "已保存 ${savedUsername.orEmpty()} · 导入时自动续期"
                            hasSavedPassword ->
                                "已保存 ${savedUsername.orEmpty()} · 自动登录未开启"
                            else ->
                                "未保存（导入课表时需手动登录）"
                        },
                        // 只传登录态本身，文案由 ProfilePage 决定。
                        // 传字符串会让界面被迫"比对文案"来判断状态，那是最脆的做法。
                        sessionOk = sessionOk,
                        lastImportLabel = formatImportTime(s.board.meta.lastImportAt),
                        boardCount = boardIndex.boards.size,
                        onOpenBoards = { openBoards() },
                        syncSubtitle = if (s.board.meta.isSyncable) {
                            "重新拉取该学期数据。不会自动同步，仅在你点击后执行"
                        } else {
                            "该课表为自建课表，教务系统没有对应学期"
                        },
                        onSyncCurrent = {
                            // 自建课表没有可同步的学期——给一句明确的解释，
                            // 而不是让按钮点了毫无反应（"点了没反应"是最差的交互）。
                            if (s.board.meta.isSyncable) {
                                syncConfirmFor = s.board.meta
                            } else {
                                syncResult = "「${s.board.meta.name}」是自建课表，" +
                                    "教务系统没有对应的学期可以拉取。\n\n" +
                                    "自建课表的内容由你自行编排，" +
                                    "不存在与教务不一致的问题。"
                            }
                        },
                        onLogin = { launchLogin() },
                        onSignOut = {
                            // 退出登录只清校园登录态，本地课表一张都不删。
                            //   这正是"课表是本地资产"的体现：退了也能离线看课表。
                            //   顺带关掉自动登录，否则刚退出去就被自动登回来，用户会以为按钮坏了。
                            scope.launch {
                                credentials.setAutoLogin(false)
                                SessionManager.signOut()
                                sessionOk = false
                            }
                        },
                        onUpdateCourse = { course ->
                            scope.launch {
                                repo.upsertCourse(s.board.meta.id, course)
                                reload()
                            }
                        },
                        onDeleteCourse = { courseId ->
                            scope.launch {
                                repo.deleteCourse(s.board.meta.id, courseId)
                                reload()
                            }
                        },
                        onContentChanged = { scope.launch { reload() } },
                    )
                }
            }

            // ------------------------------------------------------------------
            // 手动同步：确认 → 执行 → 结果
            //
            // 三层弹层均挂在最外层（Box 的直接子节点）而非页面内：
            // 同步可能从「我的课表」或「我的」页发起，执行期间用户还可能切页，
            // 挂在这里才能保证始终可见。
            // ------------------------------------------------------------------

            syncConfirmFor?.let { board ->
                ConfirmDialog(
                    title = "从教务同步「${board.name}」？",
                    body = "会重新拉取「${board.term.termName.ifBlank { board.term.termCode }}」，\n\n" +
                        "• 课程、教师、星期节次、周次以教务为准，本地修改的这些会被覆盖；\n" +
                        "• 你设置过的颜色、学分、备注会保留（教务没有这几个字段）；\n" +
                        "• 作息时间与课表名不会被改动。\n\n" +
                        "不会自动同步，此操作仅在你确认后执行。",
                    confirmText = "开始同步",
                    onConfirm = {
                        val id = board.id
                        syncConfirmFor = null
                        runSync(id)
                    },
                    onDismiss = { syncConfirmFor = null },
                )
            }

            if (syncBusy) {
                BusyDialog("正在从教务拉取课表…")
            }

            syncResult?.let { text ->
                MessageDialog(
                    title = "同步结果",
                    body = text,
                    onDismiss = { syncResult = null },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 空课表库
// ---------------------------------------------------------------------------

/**
 * 本地没有任何课表时的首屏。
 *
 * 首句为「还没有课表」而非「未绑定校园账号」，主按钮是导入或新建，
 * 登录只是可能用到的步骤之一。
 */
@Composable
private fun EmptyLibraryView(
    onNewBoard: () -> Unit,
    onLogin: () -> Unit,
    sessionOk: Boolean?,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有课表", style = t.heroName, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "课表存储在本机。可从学校教务导入一个学期的课表，也可新建一张空白课表自行编排；" +
                "多张课表可随时切换。仅在导入时需要登录校园账号。",
            style = t.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("导入 / 新建课表", onNewBoard, Modifier.fillMaxWidth(0.72f))

        if (sessionOk == false) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(SeuRadius.button))
                    .clickable { onLogin() }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("先登录校园账号", style = t.body, color = c.primary)
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun MainScaffold(
    loaded: LoadedBoard,
    settings: SettingsStore,
    credentials: CredentialStore,
    repo: TimetableRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accountSubtitle: String,
    /**
     * 校园登录态三态：true 已登录 / false 未登录 / null 正在检查。
     * 传状态而非文案：界面据此分支，「我的」页与「校园账号」页都靠它决定显示什么。
     */
    sessionOk: Boolean?,
    lastImportLabel: String,
    boardCount: Int,
    onOpenBoards: () -> Unit,
    /** 「我的」页那一行的副标题：这张课表能不能同步、为什么不能 */
    syncSubtitle: String,
    onSyncCurrent: () -> Unit,
    /** 从「我的」页发起校园登录：登录与会话状态都归「校园账号」一处管 */
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onUpdateCourse: (Course) -> Unit,
    onDeleteCourse: (String) -> Unit,
    onContentChanged: () -> Unit,
) {
    val c = LocalSeuColors.current
    val meta = loaded.meta
    val timetable = loaded.timetable

    var tab by remember { mutableStateOf(HomeTab.TODAY) }

    // 进入过的主页面。一旦进入过就留在组合里（见 HomeTab 循环处的说明）。
    // 三个元素的线性查找，不值得为此换成 Set。
    val visitedTabs = remember(meta.id) { mutableStateListOf(HomeTab.TODAY) }

    // 切主页面统一走这里：顺手记下"这一页来过了"，页面才会在之后被保留下来。
    fun switchTab(to: HomeTab) {
        if (to !in visitedTabs) visitedTabs.add(to)
        tab = to
    }

    // 周次状态提到本层，切换 tab 后返回时不会丢失（规格第 8 章交互清单）。
    // key 用课表 id：切换课表后周次必须重算，沿用上一张课表的周次会越界。
    var week by remember(meta.id) {
        mutableIntStateOf(timetable.currentWeek.coerceIn(1, timetable.displayedWeeks))
    }
    var screen by remember(meta.id) { mutableStateOf<Screen>(Screen.Home) }

    val slots = remember(timetable.courses) { CourseColorAssigner(timetable.courses) }
    // 作息表跟随当前课表：自建课表由用户填写时刻，导入的课表使用默认 SEU 作息。
    // key 带上 schedule 内容，修改作息后会重算。
    val schedule = remember(meta.id, meta.schedule) { meta.periodSchedule }

    // 30 秒一跳的时钟：足以刷新进度条与「还有 N 分钟」，又不至于每帧重算。
    // 用 LocalDateTime 而非缓存的时间整数，跨 0 点时日期会自然跟随（规格 7.1）。
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            delay(30_000L)
        }
    }

    val dismissedTerm by settings.dismissedUnplacedTerm.collectAsState(initial = null)

    BackHandler(enabled = screen !is Screen.Home) { screen = Screen.Home }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                is Screen.Home -> HomeTabHost(
                    current = tab,
                    visited = visitedTabs,
                    // 容器自带底色：被收起的那一页不参与绘制，铺这一层可以避免
                    // 露出窗口背景（深色模式下是一闪白）。
                    modifier = Modifier.fillMaxSize().background(c.bg),
                ) { entry ->
                    when (entry) {
                    HomeTab.TODAY -> TodayPage(
                        timetable = timetable,
                        schedule = schedule,
                        slots = slots,
                        today = clock.toLocalDate(),
                        now = clock.toLocalTime(),
                        onCourseClick = { screen = Screen.CourseDetail(it) },
                        onBellClick = { screen = Screen.Reminder },
                    )

                    HomeTab.TIMETABLE -> TimetablePage(
                        timetable = timetable,
                        slots = slots,
                        schedule = schedule,
                        boardName = meta.name,
                        onOpenBoards = onOpenBoards,
                        today = clock.toLocalDate(),
                        week = week,
                        onWeekChange = { week = it },
                        onCourseClick = { screen = Screen.CourseDetail(it) },
                        onAddClick = {
                            // 「+」对所有课表都是"新增一门课"：课表已是本机资产，
                            // 往导入的课表里补一门教务未排的课（讲座、辅导课）属正常需求，
                            // 因此不再按来源分叉。
                            screen = Screen.EditCourse(null)
                        },
                        showUnplacedBar = dismissedTerm != meta.term.termCode,
                        onDismissUnplaced = {
                            scope.launch { settings.dismissUnplaced(meta.term.termCode) }
                        },
                        showOutOfWeek = meta.showOutOfWeek,
                    )

                    HomeTab.PROFILE -> ProfilePage(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accountSubtitle = accountSubtitle,
                        onOpenAccount = { screen = Screen.Account },
                        onOpenHelp = { screen = Screen.Help },
                        // 「校园账号」一行同时承担"看状态"和"去登录"，不再另设「重新登录」。
                        sessionOk = sessionOk,
                        boardCount = boardCount,
                        activeBoardSummary = meta.subtitleOf(timetable.courses.size),
                        onOpenBoards = onOpenBoards,
                        lastImportLabel = lastImportLabel,
                        syncSubtitle = syncSubtitle,
                        onSyncCurrent = onSyncCurrent,
                        courseCount = timetable.courses.size,
                        sessionCount = timetable.sessions.size,
                        unplacedCount = timetable.unplaced.size,
                        onSignOut = onSignOut,
                    )
                }
                }

                is Screen.CourseDetail -> timetable.course(s.courseId)?.let { course ->
                    CourseDetailPage(
                        timetable = timetable,
                        course = course,
                        slots = slots,
                        onBack = { screen = Screen.Home },
                        onEdit = { screen = Screen.EditCourse(course.id) },
                        // 点一下即落盘。换色是高频的整理动作，不该逼用户走一遍表单。
                        onChangeColor = { slot ->
                            onUpdateCourse(course.copy(colorOverride = slot))
                        },
                        onDelete = {
                            onDeleteCourse(course.id)
                            screen = Screen.Home
                            switchTab(HomeTab.TIMETABLE)
                        },
                    )
                }

                // 所有来源的课表共用这一个编辑页（见 CourseEditPage）。
                // courseId == null 表示新增，不存在 existing。
                is Screen.EditCourse -> {
                    val existing = s.courseId?.let { timetable.course(it) }
                    CourseEditPage(
                        repo = repo,
                        boardId = meta.id,
                        term = meta.term,
                        schedule = schedule,
                        // 新增时给个"第一个空槽"当预览色；没亲手点就仍然是自动分配。
                        defaultColorSlot = existing?.let { slots[it.id] } ?: slots.nextFreeSlot(),
                        existing = existing,
                        existingSessions = existing?.let { timetable.sessionsOf(it.id) }.orEmpty(),
                        // 教务那边有对应学期 → 顶部提示"你改的是本机副本"
                        importedFromEhall = meta.isSyncable,
                        onSaved = {
                            onContentChanged()
                            screen = Screen.Home
                            switchTab(HomeTab.TIMETABLE)
                        },
                        onDeleted = {
                            onContentChanged()
                            screen = Screen.Home
                            switchTab(HomeTab.TIMETABLE)
                        },
                        onBack = {
                            screen = existing?.let { Screen.CourseDetail(it.id) } ?: Screen.Home
                        },
                    )
                }

                is Screen.Reminder -> ReminderPage(
                    settings = settings,
                    scope = scope,
                    onBack = { screen = Screen.Home },
                )

                is Screen.Account -> AccountPage(
                    store = credentials,
                    // 登录与会话状态就住在这一页：「校园账号」既是展示也是入口。
                    sessionOk = sessionOk,
                    onLogin = onLogin,
                    onBack = { screen = Screen.Home },
                )

                is Screen.Help -> HelpPage(
                    onBack = { screen = Screen.Home },
                )
            }
        }

        if (screen is Screen.Home) {
            Box(Modifier.background(c.bg)) {
                SeuTabBar(
                    current = tab,
                    onSelect = { switchTab(it) },
                )
            }
        }
    }
}

/**
 * 三个主页面（今日 / 课表 / 我的）的宿主。
 *
 * 设计要点是「**常驻**」：页面进入过一次就留在组合里，[current] 之外的页面只是不再摆放。
 * 之前的写法是 `AnimatedContent`，它会在过渡动画结束后销毁上一页，于是每次切换都要从零组合
 * 一整页。实测「课表」页（7×13 网格 + 十几个按可用宽度自适应的文字）从零组合会稳定产生
 * 一帧 150–200ms 的 UI 线程卡顿；快速连点时，上一页还没退完、下一页又开始组合，
 * 叠加起来就是用户感受到的「卡卡的」。
 *
 * 为什么用 [key] 包一层：`visited` 只增不减，页面在列表里的位置会变（例如先只去过「今日」
 * 与「我的」，之后才去「课表」）。没有 key 时 `remember` 的槽位会按位置错配到别的页面上——
 * 滚动位置与动画状态会串页，而且这种错配在编译期毫无痕迹。
 *
 * **刻意的取舍：切页不加任何进场动画。** 早先这里给进场页做过 `alpha` 淡入 + 四分之一屏的
 * 横向位移（180ms tween），结果是"页面整体弹一下"，与底部导航胶囊自身的弹性动画叠在一起，
 * 观感是两套节奏在打架——胶囊在弹、页面也在弹。现在页面是硬切，位移的示意完全交给导航胶囊：
 * 它横向滑动并回弹，本身就说明了"从哪一页移动到哪一页"，这比让整页内容跟着飘一遍更干净。
 * 因此 [incomingDir] 这个「进场方向」也已一并删掉：没有页面位移，方向就没人用了。
 */
@Composable
private fun HomeTabHost(
    current: HomeTab,
    visited: List<HomeTab>,
    modifier: Modifier = Modifier,
    content: @Composable (HomeTab) -> Unit,
) {
    Box(modifier) {
        visited.forEach { entry ->
            key(entry) {
                val active = entry == current
                Box(
                    Modifier
                        .fillMaxSize()
                        .composeKept(active)
                ) { content(entry) }
            }
        }
    }
}

/**
 * 只在 [visible] 为真时测量并摆放子内容；为假时**保持组合**，但不测量、不摆放。
 *
 * 为什么需要它：Compose 没有「只组合、不显示」的公开开关。隐藏一页的常规做法是把它从组合树
 * 里去掉（`AnimatedContent`、`when` 都是如此），代价是切回来要从零组合一整页，而本 App 的
 * 课表页恰恰是最重的一屏。这里改为把组合结果留着（页内状态、滚动位置、文字测量缓存都还在），
 * 只跳过测量与摆放，于是切回来几乎是零成本。
 *
 * 安全性来自 Compose 的一条规则：**命中测试只考虑已摆放的节点**。不摆放的子树既不参与绘制
 * （不会露出来），也不参与命中测试（不会抢走上层页面的点击）。
 */
private fun Modifier.composeKept(visible: Boolean): Modifier =
    layout { measurable, constraints ->
        if (!visible) {
            layout(0, 0) {}
        } else {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

// ---------------------------------------------------------------------------

@Composable
private fun ReminderPage(
    settings: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current
    val context = LocalContext.current
    val enabled by settings.reminderEnabled.collectAsState(initial = false)
    val minutes by settings.reminderMinutes.collectAsState(initial = 15)

    var notice by remember { mutableStateOf("") }
    var nextLabel by remember { mutableStateOf("正在计算…") }
    var exactOk by remember { mutableStateOf(true) }

    // 开关或提前量一变就重排，并把「下一次提醒」算出来给用户看。
    // 提醒这种事，看不见就等于没有——能显示下一次的时刻，才说明这条路真的通了。
    LaunchedEffect(enabled, minutes) {
        ReminderScheduler.reschedule(context)
        nextLabel = ReminderScheduler.describeNext(context)
        exactOk = ReminderScheduler.canScheduleExact(context)
    }

    // 通知权限：Android 13 起必须显式申请。开开关与发测试通知共用一次申请，
    // 用 wantTest 区分授权后的去向。
    // 没拿到权限就不打开开关——开了也发不出来，那是「设了却永远不响」的假象。
    var wantTest by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            !granted ->
                notice = "没有通知权限，提醒发不出来。可在系统设置 → 应用 → SEU 课表 → 通知里开启。"
            wantTest -> {
                notice = ""
                scope.launch { ReminderScheduler.testNotify(context) }
            }
            else -> {
                notice = ""
                scope.launch { settings.setReminderEnabled(true) }
            }
        }
        wantTest = false
    }

    fun needsPermissionAsk() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun requestEnable() {
        if (needsPermissionAsk()) {
            wantTest = false
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notice = ""
            scope.launch { settings.setReminderEnabled(true) }
        }
    }

    fun sendTest() {
        if (needsPermissionAsk()) {
            wantTest = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { ReminderScheduler.testNotify(context) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBack() }
                    .padding(6.dp)
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("上课提醒", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(30.dp))
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("提醒")
        Spacer(Modifier.height(8.dp))
        SeuCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(
                    title = "开启上课提醒",
                    subtitle = "每节课开始前通知一次",
                    trailing = {
                        // 具名传参：SeuToggle 的最后一个参数是 modifier，
                        // 写成尾随 lambda 会被当成 modifier
                        SeuToggle(
                            checked = enabled,
                            onCheckedChange = { value ->
                                if (value) {
                                    requestEnable()
                                } else {
                                    notice = ""
                                    scope.launch { settings.setReminderEnabled(false) }
                                }
                            },
                        )
                    },
                )
                RowDivider()
                // 五个选项在「标题在左、选择器在右」的窄行里会挤（同「我的」页主题那处），
                // 故与它一致地改为上下排布。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp)
                ) {
                    Text("提前时间", style = t.body, color = c.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        options = listOf(5, 10, 15, 20, 30),
                        selected = minutes,
                        label = { "$it 分" },
                        onSelect = { v -> scope.launch { settings.setReminderMinutes(v) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(11.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("下一次提醒", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (enabled) nextLabel else "未开启",
                    style = t.caption,
                    color = c.textSecondary,
                )

                if (enabled && !exactOk) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "系统未授予「精确闹钟」权限，提醒时间可能有几分钟偏差。",
                        style = t.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(SeuRadius.button))
                            .background(c.primarySoft)
                            .clickable {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                    runCatching { context.startActivity(intent) }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text("去开启", style = t.caption, color = c.primary)
                    }
                }

                if (notice.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(notice, style = t.micro, color = c.danger)
                }

                Spacer(Modifier.height(12.dp))
                // 测试入口：开启前就能看到提醒的实际样子，内容取「下一节真实要提醒的课」。
                Box(
                    Modifier
                        .clip(RoundedCornerShape(SeuRadius.button))
                        .background(c.primarySoft)
                        .clickable { sendTest() }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text("发一条测试通知看看", style = t.caption, color = c.primary)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun StatusView(
    title: String,
    body: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    showBar: Boolean = false,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Column(Modifier.fillMaxSize()) {
        // 规格第 8 章：同步中不要全屏转圈，用顶部一条细进度条
        if (showBar) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = t.heroName, color = c.textPrimary, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(10.dp))
                Text(body, style = t.body, color = c.textSecondary, textAlign = TextAlign.Center)
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton(actionText, onAction, Modifier.fillMaxWidth(0.6f))
            }
            if (secondaryText != null && onSecondary != null) {
                Spacer(Modifier.height(6.dp))
                // 必须真正可点击：漏掉 clickable 会渲染成一段蓝色文字，
                // 看似按钮却无响应。padding 取 12dp 以保证点击热区足够大。
                Box(
                    Modifier
                        .clip(RoundedCornerShape(SeuRadius.button))
                        .clickable { onSecondary() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(secondaryText, style = t.body, color = c.primary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

private val IMPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 读取本地课表失败的文案。
 *
 * 与网络错误是两回事：本地课表读取失败只可能源于磁盘或文件格式问题，
 * 用户能做的只有重试或重装，提示中不得出现"检查网络""连接学校 VPN"等误导性建议。
 */
private fun readLibraryFailure(e: Throwable): LibraryState.Failed {
    val raw = e.message ?: (e::class.simpleName ?: "未知错误")
    return LibraryState.Failed(
        "本机上的课表文件无法读取。这不是网络问题——课表数据就在本机。\n\n" +
            "原始信息：$raw"
    )
}

private fun formatImportTime(millis: Long?): String {
    if (millis == null) return "还没有导入过"
    val at = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    val today = java.time.LocalDate.now()
    return when (at.toLocalDate()) {
        today -> "今天 " + at.format(DateTimeFormatter.ofPattern("HH:mm"))
        today.minusDays(1) -> "昨天 " + at.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> at.format(IMPORT_TIME_FORMAT)
    }
}
