/* 注意：Kotlin 块注释可嵌套，注释中若出现「斜杠加星号」会提前闭合注释。涉及 `/jwapp/sys/wdkb/` 后接星号与 `default` 的路径，注释内写作 `<星号>default`，代码字符串字面量保持原样。 */

package com.seu.timetable.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.seu.timetable.data.AUTH_BASE
import com.seu.timetable.data.CasAuthClient
import com.seu.timetable.data.CasLoginResult
import com.seu.timetable.data.CredentialStore
import com.seu.timetable.data.EHALL_APP
import com.seu.timetable.data.EHALL_BASE
import com.seu.timetable.data.EhallClient
import com.seu.timetable.data.SessionProbe
import com.seu.timetable.ui.components.AccountField
import com.seu.timetable.ui.components.BackIcon
import com.seu.timetable.ui.components.FieldLabel
import com.seu.timetable.ui.components.PrimaryButton
import com.seu.timetable.ui.components.SectionLabel
import com.seu.timetable.ui.components.SeuCard
import com.seu.timetable.ui.components.SeuToggle
import com.seu.timetable.ui.theme.LocalSeuColors
import com.seu.timetable.ui.theme.LocalSeuType
import com.seu.timetable.ui.theme.SeuTheme
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页 = 账号密码表单 + 一层隐身的门户页。
 *
 * 界面：默认只给表单（学号 + 密码 + 是否保存），用户不必看见学校的网页。
 * 后台：拿到 TGT（见 [CasAuthClient]）后由 [watchSession] 驱动隐身 WebView 走完
 * 「门户 → 点击我的课表 → 静默换票」，建立课表会话（GS_SESSIONID + _WEU）。
 *
 * 为什么不能干脆删掉 WebView：ehall 的授权 cookie `_WEU` 只认「从门户点入」的事务。
 * 直接打开课表地址同样能拿到 GS_SESSIONID，但 `_WEU` 不下发、接口恒 403（详见 [watchSession]）。
 * 门户那一步只能交给网页 JS 去执行；之所以把它藏起来，是因为这个过程对用户毫无信息量。
 *
 * 网页只在两种情况下露出：学校要求验证码（只能人工过），或用户主动点「改用网页登录」。
 *
 * 入口用 `index.html`：首页为壳页面，内容位于 `<iframe id="template-container">`，该 iframe 高 100%
 * 而在 WebView 中高度链断裂致整页白屏，加载后由 [fitPortalFrame] 撑满视口。
 */
const val LOGIN_ENTRY_URL = "https://i.seu.edu.cn/index.html"

/** 自动打开的目标：课表微应用。使用不带 gid_ 的朴素地址即可（实测 gid_ 与鉴权无关）。 */
private const val EHALL_APP_URL = "$EHALL_BASE$EHALL_APP/*default/index.do?EMAP_LANG=zh&THEME="

/** 课表微应用在 URL 上的特征串。WebView 走到含该串的地址即表示用户已点开课表，此时探测才有意义。 */
private const val WDKB_MARK = "wdkb"

/** 诊断用接口真实地址。须用其查询 cookie 才能取得接口实际携带的批次（见 [refreshDiagnostics]）；
 * 不得用域名根查询：`getCookie()` 遵循 path 匹配，而 GS_SESSIONID 的 path 为 `/jwapp/`，根路径返回空会误导排查。 */
private const val EHALL_API_PROBE = "$EHALL_BASE$EHALL_APP/modules/jshkcb/dqxnxq.do"

/**
 * 清理 ehall 侧 cookie 时覆盖的候选路径。
 * `CookieManager` 无「按域删除」API，只能对具体 URL 以 `setCookie(url, "名=; Path=…; Max-Age=0")` 覆盖，
 * 且（域, 路径）须与原 cookie 完全一致，否则静默失败。
 * 坑：若不写 `Path=`，WebView 按 URL 推导默认路径（取末斜杠前部分），`…/jwapp/` 推得 `/jwapp`（无尾斜杠），
 * 而服务端 GS_SESSIONID 路径为 `/jwapp/`（有尾斜杠），二者不等导致覆盖不到。故须显式写 `Path=`，且带与不带尾斜杠都试。
 */
private val EHALL_COOKIE_PATH_CANDIDATES: List<String> = run {
    val out = mutableListOf("/")
    var acc = ""
    // EHALL_APP 形如 /jwapp/sys/wdkb，逐段展开成各级路径
    EHALL_APP.trim('/').split('/').forEach { seg ->
        acc += "/$seg"
        out += acc
        out += "$acc/"
    }
    out += "$acc/*default"
    out += "$acc/modules/jshkcb"
    out.distinct()
}

private enum class LoginPhase { WAITING, CHECKING, DONE }

/**
 * 登录页当前呈现哪一种界面。
 *
 * FORM 为默认：只给账号密码表单，门户页在背后隐身运行。
 * WEB 是兜底：学校要求验证码、或用户主动点「改用网页登录」时才露出网页——
 * 验证码这一步只能人工在网页里完成。
 */
private enum class LoginStage { FORM, WEB }

/**
 * 校园账号登录页。
 *
 * 两种进入方式：`auto=false` 只显示表单等用户填；`auto=true` 时若本地已存凭据，
 * 先替你静默试一次（见 [autoLogin]），成不成都退回表单——失败原因会写在表单下方。
 * 无论走哪条路，门户链都由同一个 [watchSession] 完成，区别只是 TGT 从哪来。
 *
 * 凭据见 [CredentialStore]：密码经 Android Keystore 的 AES-256-GCM 加密后落盘，密钥不出硬件；
 * 是否保存由用户在表单上勾选，不勾则本次用完即忘。
 * 登录成功判定不依赖猜测 cookie 名或页面返回，而是直接探测一次接口；时机为走到课表页后，
 * 由后台轮询（[watchSession]）与网页模式下的手动确认共同触发。全过程日志 tag 为 `SeuTT`。
 */
class LoginActivity : ComponentActivity() {

    private val client by lazy { EhallClient() }

    /** 认证接口客户端。自动模式之外用不到，故延迟初始化。 */
    private val casAuth by lazy { CasAuthClient() }

    /** 凭据存储。手动输入密码的路径不会用到。 */
    private val credentials by lazy { CredentialStore(this) }

    /** 是否「能自动则自动」。由调用方决定（设置页关闭自动登录则传 false）。 */
    private var autoMode = false

    private val phase = mutableStateOf(LoginPhase.WAITING)
    private val hint = mutableStateOf(WAITING_HINT)
    private val pageInfo = mutableStateOf("正在打开网上办事大厅…")
    private val cookieInfo = mutableStateOf("")
    private val probeInfo = mutableStateOf("尚未探测")

    // ---- 表单界面状态 ----

    /** 默认表单；验证码或用户主动改用网页时切到 WEB。 */
    private val stage = mutableStateOf(LoginStage.FORM)

    /** 表单下方的那行错误提示，空串表示不显示。 */
    private val formError = mutableStateOf("")

    /** 后台轮询是否已在跑，保证只启动一次（见 [startWatch]）。 */
    private var watchRunning = false

    private var inFlight = false
    private var finishedOk = false

    /** 自动模式「用凭据换票」是否已尝试。仅试一次，理由见 [autoLogin]。 */
    private var credentialLoginTried = false

    /**
     * 自动模式自愈是否已使用。
     * `hasAuthTicket()` 仅表示 auth 域存在 TGT cookie，不保证服务端仍认可（过期或别处登出后 cookie 仍在）。
     * 此类「看似有实则失效」的 TGT 会让页面滑向认证页且不报错，故发现「停在认证页且 TGT 失效」时
     * 清除旧票再换一次，仅补一次以防死循环。
     */
    private var ticketRetried = false

    /** 最近一次探测结果，用于失败时给出准确文案 */
    private var lastProbe: SessionProbe = SessionProbe.NotLoggedIn("尚未探测")

    /** 用户是否曾走到课表页（含被 403 拦截的那次） */
    @Volatile
    private var reachedWdkb = false

    /** 是否已进入「自动接管」模式（检测到认证完成，后续无需用户操作） */
    @Volatile
    private var autoLaunched = false

    /** 直链兜底是否已使用 */
    @Volatile
    private var fallbackUsed = false

    /** 自动点击入口的尝试次数 */
    private var clickTries = 0

    /** 当前主框架所在主机名，用于判断用户是否已回到门户 */
    @Volatile
    private var currentHost: String = ""

    /**
     * 连续停在认证页的轮数。正常换票也会短暂经过认证域（不足 1 秒），若连续多轮均停在认证页
     * 才是真正卡在登录表单。用「连续」而非「曾经」，以避免误判正常换票为卡住。
     */
    private var authHostRounds = 0

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        autoMode = intent.getBooleanExtra(EXTRA_AUTO, false)
        webView = buildWebView()

        setContent {
            SeuTheme {
                LoginScreen(
                    stage = stage.value,
                    phase = phase.value,
                    hint = hint.value,
                    pageInfo = pageInfo.value,
                    cookieInfo = cookieInfo.value,
                    probeInfo = probeInfo.value,
                    error = formError.value,
                    store = credentials,
                    webView = webView,
                    onSubmit = ::submitLogin,
                    onUseWeb = { revealWeb("用户主动改用网页登录") },
                    onManualCheck = { verify("手动确认") },
                    onGoBack = ::goBack,
                    onRestart = ::restartFromEntry,
                )
            }
        }

        DebugLog.i("===== 登录页打开（auto=$autoMode）=====")
        DebugLog.i("UA = ${webView.settings.userAgentString}")

        // 进门前先清掉 ehall 侧残留会话（否则持续 403、不跳统一认证死锁），保留 auth 的 TGT。
        clearEhallCookies()

        setStatus(LoginPhase.WAITING, FORM_HINT)
        lifecycleScope.launch { refreshDiagnostics() }

        // 表单模式**不加载任何页面**：门户链等用户提交、或点了「改用网页登录」时再开（见 [openPortal]）。
        // 这样打开登录页是零网络开销的。
        if (autoMode) lifecycleScope.launch { autoLogin() }
    }
    // -------------------------------- 自动续期与表单提交

    /**
     * 自动续期：使 auth 域出现可用 TGT，随后开门户走完课表会话。
     *
     * 仅尝试一次（见 [credentialLoginTried]）：认证服务端有风控，连续失败会要求验证码，
     * 无限重试在「密码已改」场景下只会把账号推向锁定，一次不成即交还用户是唯一稳妥策略。
     */
    private suspend fun autoLogin() {
        setStatus(LoginPhase.CHECKING, AUTO_START_HINT)

        // 已有票则无需接触密码——这也是「存了密码却几乎用不上」的常态。
        if (hasAuthTicket()) {
            DebugLog.i("auto：auth 域已有 TGT → 不碰密码，直接开门户")
            openPortal()
            return
        }

        val creds = credentials.load()
        if (creds == null) {
            DebugLog.i("auto：本地没有可用的账号密码 → 回到表单")
            degradeToManual("")
            return
        }

        DebugLog.i("auto：auth 域没有 TGT，但有保存的账号（${creds.username}）→ 开始换票")
        when (val r = tryCredentialLogin(creds.username, creds.password)) {
            is CasLoginResult.Success -> {
                DebugLog.i(
                    "auto：换票成功（TGT 落库=${r.tgtInStore}，有效期 ${r.maxAge} 秒）→ 开门户"
                )
                // tgtInStore 为 false 时仍开门户：CAS 可能仍认本次会话，否则 [watchSession] 自愈分支会兜底。
                if (!r.tgtInStore) {
                    DebugLog.w("auto：TGT 没落进 CookieManager，门户可能仍要求登录")
                }
                openPortal()
            }
            CasLoginResult.BadCredentials ->
                degradeToManual("保存的学号或密码不对，请重新输入。")
            CasLoginResult.CaptchaRequired ->
                degradeToManual("学校这次要求输入验证码，请点下方「改用网页登录」完成。")
            CasLoginResult.SessionNotEstablished ->
                degradeToManual("自动登录没成功（认证会话没建立起来），请手动输入账号密码。")
            is CasLoginResult.Failed ->
                degradeToManual("自动登录没成功（${r.reason}），请手动输入账号密码。")
        }
    }

    /** 执行一次 CAS 换票，不含重试——重试策略在调用方，见 [autoLogin]。 */
    private suspend fun tryCredentialLogin(username: String, password: String): CasLoginResult {
        credentialLoginTried = true
        return casAuth.login(username, password)
    }

    /**
     * 表单提交：用账号密码换 TGT，成功后交给门户链完成剩下的步骤。
     *
     * 认证这一步是纯 HTTP（见 [CasAuthClient]），所以用户面对的是表单而不是网页；
     * 之后的门户换票必须由网页执行，故紧接着驱动隐身 WebView（见 [openPortal]）。
     *
     * 全程只提交一次密码：失败即如实报告，不自动重试（理由同 [autoLogin]）。
     */
    private fun submitLogin(username: String, password: String, remember: Boolean) {
        if (phase.value != LoginPhase.WAITING) return
        formError.value = ""
        setStatus(LoginPhase.CHECKING, "正在验证账号…")

        lifecycleScope.launch {
            // 提交前清掉 ehall 侧旧会话：网关「有会话 cookie 即不看票据」，
            // 残留的 GS_SESSIONID 会让后续请求恒 403 且不跳统一认证（见 [clearEhallCookies]）。
            clearEhallCookies()

            val result = runCatching { casAuth.login(username, password) }
                .getOrElse { CasLoginResult.Failed(it.message ?: "异常") }

            when (result) {
                is CasLoginResult.Success -> {
                    if (remember) {
                        runCatching {
                            credentials.save(username, password)
                            // 顺手打开自动续期：用户已经勾了「保存」，意图是明确的，
                            // 再让他去「我的」页拨一次开关属多余。
                            credentials.setAutoLogin(true)
                        }.onFailure { DebugLog.w("凭据保存失败：${it.message}") }
                    }
                    DebugLog.i("表单登录：换票成功（TGT 落库=${result.tgtInStore}）→ 开门户")
                    // 这次是全新的门户会话，把上一轮的进度全部复位。
                    reachedWdkb = false
                    autoLaunched = false
                    fallbackUsed = false
                    clickTries = 0
                    authHostRounds = 0
                    ticketRetried = false
                    credentialLoginTried = true
                    // 置 true 以启用「卡在认证页」的自愈分支（见 [watchSession] ①.5）：
                    // 手上可能留着别处登出后失效的废票，那种情况下需要清票重来一次。
                    autoMode = true
                    setStatus(LoginPhase.CHECKING, AUTO_START_HINT)
                    openPortal()
                }

                CasLoginResult.BadCredentials -> {
                    DebugLog.w("表单登录：学号或密码不对")
                    degradeToManual("学号或密码不对。请检查后重试；连续输错会被要求输入验证码。")
                }
                CasLoginResult.CaptchaRequired -> {
                    DebugLog.w("表单登录：服务端要求验证码")
                    degradeToManual("学校这次要求输入验证码，请点下方「改用网页登录」完成。")
                }
                CasLoginResult.SessionNotEstablished ->
                    degradeToManual("登录没成功（认证会话没建立起来），请稍后重试。")
                is CasLoginResult.Failed ->
                    degradeToManual("登录没成功：${result.reason}")
            }
        }
    }

    /**
     * 把控制权交还用户。
     *
     * 表单模式下留在表单、把原因写在表单下方：改密码或换账号都只需重填一次，比把人扔进网页直接。
     * 网页模式下只更新提示、不动页面——用户可能正在网页里过验证码。
     */
    private fun degradeToManual(reason: String) {
        DebugLog.w("退回用户操作：$reason")
        autoMode = false
        if (stage.value == LoginStage.FORM) {
            formError.value = reason
            setStatus(LoginPhase.WAITING, FORM_HINT)
        } else {
            setStatus(
                LoginPhase.WAITING,
                if (reason.isBlank()) {
                    WAITING_HINT
                } else {
                    "$reason\n登录完成后 App 会自动接着把课表会话建好。"
                },
            )
        }
    }

    /** 露出网页（验证码，或用户主动要求）。此后一切照旧由网页主导。 */
    private fun revealWeb(reason: String) {
        DebugLog.i("露出网页：$reason")
        stage.value = LoginStage.WEB
        autoMode = false
        setStatus(LoginPhase.WAITING, WAITING_HINT)
        openPortal()
    }

    /** 打开门户入口并开始后台轮询。凡是要走门户链的地方都从这里进。 */
    private fun openPortal() {
        webView.loadUrl(LOGIN_ENTRY_URL)
        startWatch()
    }

    /**
     * 启动后台轮询，只启动一次。
     *
     * 改造前它在 onCreate 就开跑，于是 60 轮 × 2 秒的轮询窗口会从用户还在输密码时开始倒计时；
     * 现在改为「真正要用门户链时才启动」。
     */
    private fun startWatch() {
        if (watchRunning) return
        watchRunning = true
        lifecycleScope.launch {
            try {
                watchSession()
            } finally {
                watchRunning = false
            }
        }
    }

    // -------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 基于 WebView 默认 UA（已含真实机型与 Chrome 版本）抹去两处 WebView 特征串："; wv"（部分站点降级/拒绝渲染）
        // 与 "Version/4.0 "（WebView 固定版本号，真浏览器不如此书写）。UA 供认证页做设备指纹，一次会话内须保持一致，仅此处设一次。
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(VERSION_MARKER, "")

        // 门户内「我的课表」可能经 `window.open` / `target=_blank` 打开，WebView 默认丢弃此类请求
        // （未接管 onCreateWindow 即「点了无反应」），故须开启多窗口并在 onCreateWindow 接住。
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // 门户首页会 302 到 http，页内又混有 https 资源，两种混合内容均放行以免拦截页面。
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 认证页与 ehall 间存在跨站请求，需放开第三方 cookie
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        /**
         * 接住 `window.open` / `target=_blank`：以临时 WebView 探出目标 URL，再交主 WebView 打开。
         * 门户内「我的课表」常以新窗口打开，而开启 `setSupportMultipleWindows(true)` 后必须实现
         * onCreateWindow，否则请求被静默丢弃（用户只见「点了无反应」且无日志）。
         * 注意该方法位于 **WebChromeClient** 而非 `WebViewClient`（写错会报 overrides nothing）。
         */
        webChromeClient = object : WebChromeClient() {

            /** 将页面自身 console 输出转入本应用日志，门户页白屏时借此查看 Vue 报错。 */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                DebugLog.i(
                    "JS[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val scout = WebView(this@LoginActivity)
                scout.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        req: WebResourceRequest,
                    ): Boolean {
                        val target = req.url.toString()
                        DebugLog.i(DebugLog.url("新窗口请求 → 改在主窗口打开:", target))
                        if (target.startsWith("http")) view.loadUrl(target)
                        scout.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = scout
                resultMsg.sendToTarget()
                return true
            }
        }

        webViewClient = object : WebViewClient() {

            /**
             * 不插手任何 URL，完全交由 WebView 自身浏览器行为。
             * 此处刻意「什么都不做」，勿再添加「http 升级 https」之类的改写：此前的改写曾将 CAS 回跳的
             * service 由 http 改为 https 导致验票失配，排查耗时甚久。目标与用户手机浏览器路径完全一致。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                DebugLog.i(DebugLog.url("导航 →", request.url.toString()))
                return false
            }

            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                DebugLog.i(DebugLog.url("onPageStarted:", url))
                // 尽早记录主机名：判定「是否已回到门户」依赖它（onPageFinished 偏晚）
                runCatching { Uri.parse(url ?: "").host }.getOrNull()?.let {
                    if (it.endsWith("seu.edu.cn")) currentHost = it
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                DebugLog.i(DebugLog.url("onPageFinished:", url))
                onWebUrl(url)
                probeDom(view)
                // 门户壳的 iframe 高度为 0（见 fitPortalFrame），Vue 挂载后才出现，故 onPageFinished 后再补两次
                view.postDelayed({ fitPortalFrame(view, "撑满 iframe(+1.5s)") }, 1500)
                view.postDelayed({ fitPortalFrame(view, "撑满 iframe(+4s)") }, 4000)
            }

            // 门户与认证页均为 SPA，pushState 换页时 onPageFinished 未必触发，此处兜底
            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                onWebUrl(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                DebugLog.e("主框架加载失败: ${error.description} @ ${request.url}")
                pageInfo.value = "加载失败：${error.description}"
                setStatus(
                    LoginPhase.WAITING,
                    if (error.description.contains("CLEARTEXT", ignoreCase = true)) {
                        "学校页面里混着 http://，被 Android 的明文拦截挡住了。" +
                            "本工程已对 seu.edu.cn 放行（network_security_config.xml）——" +
                            "若仍看到这条，请确认装的是最新构建的包。"
                    } else {
                        "页面没能打开（${error.description}）。检查网络后点「重新开始」再试。"
                    },
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                // 记录所有失败请求（不只主框架）：门户白屏时「哪个 API 失败」往往就是答案。
                DebugLog.w(
                    "HTTP ${errorResponse.statusCode} " +
                        "${if (request.isForMainFrame) "[主框架] " else "[资源] "}${request.url}"
                )
                if (request.isForMainFrame) {
                    pageInfo.value =
                        "HTTP ${errorResponse.statusCode} ${shorten(request.url.toString())}"
                    // 此处不清 cookie：清除 ehall cookie 会连同用户刚建立的会话一并作废，
                    // 只能由「重新开始」显式触发。
                }
            }
        }
    }

    /**
     * 后台主循环：自动完成「登录之后」的全部步骤，并在可取数据时收工返回。
     * 三阶段：① 等待认证完成（auth 域出现 TGT 且页面已回到门户）；② 替用户点击门户内「我的课表」
     * （两步：先点应用卡片，门户弹二次确认框，再点「打开」）；③ 探测接口，取得合法 JSON 即返回。
     * 第 ② 步须为「点击」而非「直接打开课表地址」：直链同样能取 GS_SESSIONID，但 _WEU（授权 cookie）
     * 不下发、接口恒 403；门户点击路径则 _WEU 随会话下发、接口 200，二者在服务端非同一事务，故由程序
     * 点击真实元素、由门户决定 URL 与跳转。兜底：点不到则退回直链；用户手动点开课表（[reachedWdkb]）亦启动探测。
     */
    private suspend fun watchSession() {
        repeat(MAX_WATCH_ROUNDS) { round ->
            delay(WATCH_INTERVAL_MS)
            if (finishedOk) return

            authHostRounds = if (currentHost == AUTH_HOST) authHostRounds + 1 else 0

            // ① 认证完成 → 进入自动接管
            if (!autoLaunched && hasAuthTicket() && onPortal()) {
                autoLaunched = true
                DebugLog.i("认证已完成（auth 域已有 TGT）→ 开始自动接管")
                setStatus(LoginPhase.CHECKING, AUTO_HINT)
                return@repeat  // 给门户时间渲染应用列表
            }

            // ①.5 卡在认证页 = 手上的票为废票（cookie 仍在、服务端已不认）。TGT 过期或别处登出后 cookie 不消失，
            // 导致 hasAuthTicket() 仍为真，但门户将其打发至认证页、认证页见「有票」又不显表单，用户卡在登不进的页面。
            if (autoMode && !autoLaunched && !ticketRetried && authHostRounds >= STUCK_AT_AUTH_ROUNDS) {
                ticketRetried = true
                if (credentialLoginTried) {
                    // 密码这条本轮已经试过还是卡住 → 不再纠缠，交回给人（避免把账号试进锁定）
                    degradeToManual("自动登录没成功，请手动登录一次。")
                    return
                }
                DebugLog.w("auto：连续 $authHostRounds 轮停在认证页 → 判定旧票失效，清票后重新换一次")
                clearAuthTicket()
                val creds = credentials.load()
                if (creds == null) {
                    degradeToManual("登录态已失效，请手动登录一次。")
                    return
                }
                when (tryCredentialLogin(creds.username, creds.password)) {
                    is CasLoginResult.Success -> openPortal()
                    CasLoginResult.BadCredentials ->
                        degradeToManual("保存的学号或密码不对，请手动登录一次（可在「我的」页重新保存）。")
                    CasLoginResult.CaptchaRequired ->
                        degradeToManual("学校这次要求输入验证码，请手动登录一次。")
                    else ->
                        degradeToManual("自动登录没成功，请手动登录一次。")
                }
                return@repeat
            }

            // ② 替用户完成「点开课表」这条链：须每轮都点，不能「点中一次即收手」——门户点开应用先弹
            // 二次确认框，完整动作是「点课表 → 点打开」两步。故改为一直点到真正走到课表页（[reachedWdkb]），
            // 由 [CLICK_ENTRY_JS] 判断当前该点哪个（有确认框点「打开」，否则点「我的课表」）。
            if (autoLaunched && !reachedWdkb) {
                if (clickTries < MAX_CLICK_TRIES) {
                    clickTries++
                    clickTimetableEntry()
                    return@repeat
                }
                if (!fallbackUsed) {
                    fallbackUsed = true
                    DebugLog.w("自动点击没能走到课表页（试了 $MAX_CLICK_TRIES 次）→ 退回直链")
                    webView.post { webView.loadUrl(EHALL_APP_URL) }
                    return@repeat
                }
            }

            // 未自动接管且用户也未手动走到课表页时，探测必然失败，无需再发请求
            if (!autoLaunched && !reachedWdkb) return@repeat

            DebugLog.i("后台轮询第 ${round + 1} 次（autoLaunched=$autoLaunched）")
            if (probeOnce()) return
        }
        if (!finishedOk) {
            setStatus(
                LoginPhase.WAITING,
                if (stage.value == LoginStage.WEB) {
                    // WEB 通路：网页露出，右上角确实有「已完成」按钮，可以指它
                    "还没拿到课表会话。若尚未登录请先登录；已登录的话，" +
                        "点右上角「已完成」再试一次，或点「重新开始」。"
                } else {
                    // 表单通路：网页是隐藏的，右上角没有「已完成」按钮，
                    // 此时提它只会让用户去找一个不存在的东西。这里只给可执行的动作。
                    "还没拿到课表会话，请再点一次「登录」；" +
                        "若反复不行，可点「改用网页登录」手动操作。"
                },
            )
        }
    }

    /**
     * 在门户页面内点击——点「我的课表」还是确认框「打开」由 JS 自行判断。
     * 为何「点元素」而非「自拼 URL」：见 [watchSession]，点击真实元素使 URL、gid_、请求头、跳转全由门户决定。
     * 返回值仅用于打日志，调用方不依赖：是否点中最终由能否走到课表页判定，而非此处猜测。
     */
    private fun clickTimetableEntry() {
        webView.evaluateJavascript(CLICK_ENTRY_JS) { raw ->
            DebugLog.i("自动点击 → ${raw.orEmpty().take(200)}")
        }
    }

    /**
     * 统一身份认证是否完成——判据为 auth 域出现 TGT。认 TGT 而非「页面是否跳回门户」：TGT 是 CAS 中
     * 「此人已通过认证」的凭证（cookie 名即 `TGT`），一旦出现即可换票，比任何页面特征可靠，且不受 SPA
     * pushState 换页（不触发 onPageFinished）影响。只读名字不打印值：TGT 的值本身即凭证。
     */
    private fun hasAuthTicket(): Boolean {
        val raw = runCatching {
            CookieManager.getInstance().getCookie("https://auth.seu.edu.cn/")
        }.getOrNull().orEmpty()
        return raw.split(';').any {
            val t = it.trim()
            t.startsWith("TGT=") && t.length > "TGT=".length
        }
    }

    /** 当前是否停在门户（而非仍在认证页）——避免登录进行中抢走页面 */
    private fun onPortal(): Boolean = currentHost == "i.seu.edu.cn"

    /**
     * 探测一次会话。成功即 [finish] 返回。
     *
     * 以接口为判据而非页面形态：页面白屏 / 403 / 正常都无关紧要，课表数据均经接口获取
     * （`dqxnxq.do` 等）。
     */
    private suspend fun probeOnce(): Boolean {
        val result = client.probeSession()
        lastProbe = result
        probeInfo.value = describe(result)
        DebugLog.i("探测结果 = ${describe(result)}")
        refreshDiagnostics()

        if (result is SessionProbe.LoggedIn) {
            CookieManager.getInstance().flush()   // 落盘，进程被回收后仍可复用
            DebugLog.i("===== 登录成功：${describe(result)} =====")
            finishedOk = true
            setStatus(LoginPhase.DONE, "登录成功，正在返回…")
            setResult(RESULT_OK)
            finish()
            return true
        }
        return false
    }

    /** 用户主动确认：多探几次，覆盖"会话刚建好"的时间窗 */
    private fun verify(trigger: String) {
        if (inFlight || finishedOk) return
        inFlight = true

        lifecycleScope.launch {
            setStatus(LoginPhase.CHECKING, "正在确认课表会话…")
            var ok = false
            for (i in 1..MANUAL_ATTEMPTS) {
                DebugLog.i("$trigger：探测第 $i/$MANUAL_ATTEMPTS 次")
                ok = probeOnce()
                if (ok) return@launch
                if (i < MANUAL_ATTEMPTS) delay(RETRY_DELAY_MS)
            }

            inFlight = false
            val reason = when (val p = lastProbe) {
                is SessionProbe.Failed -> "${p.reason}（接口没通，可能是网络问题）"
                is SessionProbe.NotLoggedIn -> p.reason
                is SessionProbe.LoggedIn -> ""
            }
            DebugLog.w("$trigger：仍未拿到会话（$reason）")
            setStatus(
                LoginPhase.WAITING,
                "还没拿到课表会话（$reason）。" +
                    "如果还没登录，请先在上面登录；登录后记得点开办事大厅里的「我的课表」，" +
                    "App 会在那之后自动确认。",
            )
        }
    }

    /** 页面变化时更新诊断信息；走到课表页就打开轮询开关 */
    private fun onWebUrl(url: String?) {
        if (url == null) return
        pageInfo.value = shorten(url)

        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        currentHost = host            // 给"认证完成了没有、能不能自动开课表"用
        if (!host.endsWith("seu.edu.cn")) return

        if (url.contains(WDKB_MARK)) {
            if (!reachedWdkb) {
                reachedWdkb = true
                DebugLog.i("检测到课表微应用页面 → 开始后台探测会话")
                setStatus(LoginPhase.WAITING, BOARD_HINT.getValue(stage.value))
            }
            // 页面刚加载完时它自己的初始化请求可能还没回来，交给轮询等一两轮
        }
    }

    private fun goBack() {
        // 表单模式下没有「上一页」可退，直接结束本页；
        // 网页模式则退回网页的上一页——用户点歪了不该被困住。
        if (stage.value == LoginStage.WEB && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    /**
     * 重新开始：清除 ehall 侧 cookie，回到入口重走一遍。
     *
     * 严禁调用 `removeAllCookies()`：它会连 auth.seu.edu.cn 的 TGT 一并清除，而 TGT 是
     * 「用户已通过统一身份认证」的凭证，清除即强制重新输入账号密码。
     */
    private fun restartFromEntry() {
        DebugLog.w("用户点了「重新开始」：清 ehall 侧 cookie，保留统一身份认证的 TGT")
        clearEhallCookies()
        reachedWdkb = false
        autoLaunched = false
        finishedOk = false
        inFlight = false
        // 用户主动重开，将自动模式两道闸门复位：此为用户明确意图，不适用「仅试一次」的防误伤约束。
        authHostRounds = 0
        ticketRetried = false
        credentialLoginTried = false
        lastProbe = SessionProbe.NotLoggedIn("尚未探测")
        probeInfo.value = "尚未探测"
        formError.value = ""
        setStatus(
            LoginPhase.WAITING,
            if (stage.value == LoginStage.WEB) WAITING_HINT else FORM_HINT,
        )
        lifecycleScope.launch { refreshDiagnostics() }
        // 表单模式的「重来」只是清掉 ehall 残留、等用户重新提交；
        // 只有网页模式才需要退回入口把整条链重走一遍。
        if (stage.value == LoginStage.WEB) openPortal()
    }

    /**
     * 将 ehall 侧 cookie 全部置为过期，保留统一身份认证（auth）的 TGT。
     *
     * 为何非清不可：ehall 网关「有会话 cookie 即不看票据」。只要 GS_SESSIONID 仍在
     * （即便服务端会话早已失效），它既不认（返回整页 403）也不跳统一认证，登录入口永远不出现。
     * 实测对照：带 GS_SESSIONID → 403；去除 GS_SESSIONID → 302 → 统一认证。
     */
    private fun clearEhallCookies() {
        val cm = CookieManager.getInstance()

        // ① 枚举：以各层路径查询，收齐 ehall 侧出现过的 cookie 名
        val names = mutableSetOf<String>()
        EHALL_COOKIE_PATH_CANDIDATES.forEach { path ->
            runCatching { cm.getCookie(EHALL_BASE + path) }.getOrNull().orEmpty()
                .split(';')
                .forEach { names += it.substringBefore('=').trim() }
        }
        names.remove("")

        if (names.isEmpty()) {
            DebugLog.i("ehall 侧本来就没有 cookie，无需清理")
            return
        }

        // ② 覆盖：每个名字 × 每条候选路径均置过期，路径须显式写入 cookie 串（见上方注释）。
        EHALL_COOKIE_PATH_CANDIDATES.forEach { path ->
            names.forEach { name ->
                cm.setCookie(
                    EHALL_BASE,
                    "$name=; Path=$path; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0",
                )
            }
        }
        cm.flush()

        // ③ 自查：仅报名字不报值。此清理曾「看似成功、实则未删」，自查可及时发现。
        val left = runCatching { cm.getCookie(EHALL_API_PROBE) }.getOrNull().orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
        DebugLog.w(
            "ehall cookie 清理：目标=${names.joinToString(",")}；" +
                "接口侧残留=${if (left.isEmpty()) "无" else left.joinToString(",")}" +
                "（auth 的 TGT 保持不动）"
        )
    }

    private fun setStatus(p: LoginPhase, h: String) {
        phase.value = p
        hint.value = h
    }

    /**
     * 仅清 auth 域票据（TGT / CHIPER_UID），不动 ehall 侧。
     *
     * 适用场景：手上的 TGT 已服务端作废（过期或在别处登出过）但 cookie 仍在。此时 CAS 认为
     * 「已登录过」而不显登录表单，用户卡在登不进也退不出的页面。清除后认证页才会重新出表单。
     *
     * 与 [clearEhallCookies] 为反向操作，勿混淆：
     *   清 ehall → 解 403 死锁（有会话 cookie 即不看票据），保留 TGT；
     *   清 auth  → 解「废票卡登录页」，清除 TGT。
     * 混清即把用户打回原点（重输账号密码），故仅 [watchSession] 明确判定「旧票失效」时调用。
     */
    private fun clearAuthTicket() {
        val cm = CookieManager.getInstance()
        // 路径除 `/` 外，覆盖认证域常见路径（同名票可能落在不同 path）
        listOf("/", "/auth", "/dist", "/auth/casback").forEach { path ->
            listOf("TGT", "CASTGC", "CHIPER_UID").forEach { name ->
                cm.setCookie(
                    AUTH_BASE,
                    "$name=; Path=$path; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0",
                )
            }
        }
        cm.flush()
        DebugLog.w("已清掉 auth 域票据（TGT / CHIPER_UID），让认证页重新出登录表单")
    }

    // -------------------------------- 诊断

    /**
     * 修复门户壳布局：将 iframe（`#template-container`）及其祖先链全部撑满视口。
     *
     * 白屏根因（DOM 探针实测）：门户首页加载正常，外壳 Vue 与内层门户均挂载成功，
     * 唯独 iframe 高度为 0（CSS 高 100%，但 WebView 中高度链未接上）。修法为逐级显式设视口高度，
     * iframe 内层 `height:100%` 随之解析，门户完整显示。已有高度（>50px）则不动，对其他页面无影响。
     */
    private fun fitPortalFrame(view: WebView, tag: String) {
        val js = """
            (function(){
              var f = document.getElementById('template-container');
              if (!f) return 'no-frame';
              if (f.clientHeight > 50) return 'already ' + f.clientHeight;
              var h = window.innerHeight;
              document.documentElement.style.height = h + 'px';
              if (document.body) { document.body.style.height = h + 'px'; document.body.style.margin = '0'; }
              var n = f;
              while (n && n !== document.body) {
                n.style.height = h + 'px';
                n.style.width = '100%';
                n.style.display = 'block';
                n = n.parentElement;
              }
              try {
                var d = f.contentDocument;
                if (d) {
                  d.documentElement.style.height = h + 'px';
                  if (d.body) d.body.style.height = h + 'px';
                }
              } catch(e) {}
              return 'fitted h=' + h + ' -> frame=' + f.clientWidth + 'x' + f.clientHeight;
            })()
        """.trimIndent()
        view.evaluateJavascript(js) { r -> DebugLog.i("$tag = $r") }
    }

    /**
     * DOM 探针：页面加载后抓取「内部有何内容」并打日志。
     *
     * 白屏仅两种可能，探针可一刀切开：
     *   - `appLen` 很小（几十）→ Vue 未挂载（JS 未跑或初始化被卡）；
     *   - `appLen` 很大但 `bodyTxt` 为空 → 内容在，是渲染不可见（CSS / 视口 / 字体）。
     * 另附 viewport 尺寸——WebView 在未布局完成时加载页面，视口可能 0×0，导致按视口自适应的页面整页空白。
     */
    private fun probeDom(view: WebView) {
        val js = """
            (function(){
              var f = document.getElementById('template-container');
              var out = {
                url: location.href,
                elemCount: document.getElementsByTagName('*').length,
                docH: document.documentElement.clientHeight,
                winH: window.innerHeight,
                bodyTxt: (document.body ? (document.body.innerText||'') : '').replace(/\s+/g,' ').slice(0, 120)
              };
              if (f) {
                out.frameSrc = f.getAttribute('src');
                out.frameBox = f.clientWidth + 'x' + f.clientHeight;
                try {
                  var d = f.contentDocument;
                  if (d) {
                    out.frameReady = d.readyState;
                    var fa = d.getElementById('app');
                    out.frameAppLen = fa ? fa.innerHTML.length : -1;
                    out.frameTxt = ((d.body||{}).innerText||'').replace(/\s+/g,' ').slice(0, 150);
                  }
                } catch(e) { out.frameErr = '' + e; }
              } else { out.noFrame = true; }
              return JSON.stringify(out);
            })()
        """.trimIndent()
        view.evaluateJavascript(js) { r -> DebugLog.i("DOM 探针(即时) = $r") }
        view.postDelayed({
            view.evaluateJavascript(js) { r -> DebugLog.i("DOM 探针(+6s) = $r") }
        }, 6000)
    }

    /**
     * 仅报 cookie 的名字不碰值——名字足以判断会话是否建立，值属敏感信息。
     * 探测 URL 用真实接口地址，理由见 [EHALL_API_PROBE]。
     */
    private suspend fun refreshDiagnostics() = withContext(Dispatchers.IO) {
        cookieInfo.value = cookieLabel(EHALL_API_PROBE, "ehall") + " · " +
            cookieLabel("https://i.seu.edu.cn/", "i") + " · " +
            cookieLabel("https://auth.seu.edu.cn/", "auth")
        DebugLog.i("cookie：${cookieInfo.value}")
    }

    private fun cookieLabel(url: String, label: String): String {
        val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (raw.isNullOrBlank()) return "$label[无]"
        val names = raw.split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf { s -> s.isNotBlank() } }
            .distinct()
        val joined = names.joinToString(",")
        return "$label[${if (joined.length > 44) joined.take(44) + "…" else joined}]"
    }

    private fun shorten(url: String): String =
        url.removePrefix("https://").removePrefix("http://").take(80)

    private fun describe(r: SessionProbe): String = when (r) {
        is SessionProbe.LoggedIn -> "已登录（${r.detail}）"
        is SessionProbe.NotLoggedIn -> "未登录（${r.reason}）"
        is SessionProbe.Failed -> "出错（${r.reason}）"
    }

    companion object {
        /** 后台轮询：每 2 秒一次，最多 60 次（约 2 分钟）——够用户输完账号密码 */
        private const val WATCH_INTERVAL_MS = 2000L
        private const val MAX_WATCH_ROUNDS = 60

        /** 手动确认时的探测次数与间隔：覆盖"会话刚建好"的时间窗 */
        private const val MANUAL_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L

        /** WebView UA 里的固定版本标识，真浏览器不这么写 */
        private val VERSION_MARKER = Regex("Version/[0-9.]+ ")

        /** 表单模式的开场话术 */
        private const val FORM_HINT =
            "用统一身份认证的学号与密码登录。App 会在后台把课表会话一并建好，不用你再点别的。"

        /** 网页模式的话术：此时只剩"在网页里登录"这一步需要人做 */
        private const val WAITING_HINT =
            "用校园账号登录即可。登录完成后 App 会自动接着把课表会话建好并返回，" +
                "不用你再点别的。"

        /** 检测到认证已完成、正在自动打开课表 */
        private const val AUTO_HINT = "登录成功，正在自动打开课表…"

        /**
         * 已经到课表页、正在等接口的时刻。
         *
         * 分通路：只有 WEB 通路才在界面上放了「已完成」按钮（见 [WebBar] 的挂载条件），
         * 表单通路的网页是隐藏的、根本没有那个按钮，此时提它只会让用户去找一个不存在的东西。
         */
        private val BOARD_HINT = mapOf(
            LoginStage.WEB to "已打开课表页，正在确认会话… 若一直不返回，点右上角「已完成」。",
            LoginStage.FORM to "已打开课表页，正在确认会话… 稍等片刻即可，无需操作。",
        )

        /** 自动点击入口最多试几次（每轮 2s，8 次约 16s），超了就退回直链 */
        private const val MAX_CLICK_TRIES = 8

        /** 认证域的主机名。用来判断"是不是卡在登录页上了"（见 [authHostRounds] 与 [watchSession] ①.5）。 */
        private const val AUTH_HOST = "auth.seu.edu.cn"

        /**
         * 连续几轮停在认证页才判定「卡住」。
         * 取值须明显大于正常换票路过认证域的时间：正常 SSO 仅停几百毫秒，而每轮轮询 2s。
         * 取 4 轮（约 8s）既不误伤正常换票，也不让用户久等。
         */
        private const val STUCK_AT_AUTH_ROUNDS = 4

        /** 自动模式开场时的文案：让用户知道"不用你动手，稍等" */
        private const val AUTO_START_HINT = "正在自动登录校园账号，请稍候…"

        /** 传 true 走自动模式（有凭据就自己登，失败静默退化成普通登录页）。 */
        const val EXTRA_AUTO = "com.seu.timetable.extra.AUTO"

        fun intent(context: Context, auto: Boolean = false): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_AUTO, auto)

        /**
         * 在门户页面内替用户「点击」——每次调用推进一步。
         *
         * 两步动作，勿只做第一步：点「我的课表」→ 门户弹二次确认框（带「打开」按钮）→ 点「打开」才跳转。
         * 故按优先级查找：先找「打开」（确认框在则点它），无确认框再点「我的课表」。外层每 2s 调用一次，两步自然串联。
         *
         * 为何不用 `el.click()`：门户应用卡片为 `<div>` 包 `<img>` + `<p class="title">我的课表</p>`，
         * 处理器绑在外层容器，仅点 `<p>` 实测无反应。故改为：① 沿祖先链找「真正可点」的一级
         * （`cursor:pointer` / `<a>` / 带 `onclick`），找不到则退至父级；② 不调 `el.click()`，
         * 而是派发完整指针事件序列（pointerdown→mousedown→pointerup→mouseup→click），与真手指一致。
         *
         * 搜索范围：优先门户内容所在的同源 iframe（`#template-container`），其次顶层文档
         * （确认框可能由顶层文档渲染，故两者皆查）。匹配方式：叶子节点且文本恰为目标词，优先可见者。
         *
         * 返回值：`clicked:<点了哪个>:<元素>|<祖先链>` / `not-found|<正文片段>` / `click-error:…`。
         * 带回祖先链以便一次看清门户 DOM 结构。
         */
        private val CLICK_ENTRY_JS = """
            (function(){
              function visible(e){
                if (!e.getClientRects) return false;
                var r = e.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }
              function desc(e){
                var s = e.tagName;
                if (e.id) s += '#' + e.id;
                var c = ('' + (e.className || '')).trim();
                if (c) s += '.' + c.split(/\s+/).join('.');
                try { s += '{' + getComputedStyle(e).cursor + '}'; } catch (err) {}
                if (e.getAttribute && e.getAttribute('onclick')) s += '[onclick]';
                if (e.tagName === 'A' && e.getAttribute('href')) {
                  s += '[href=' + ('' + e.getAttribute('href')).slice(0, 50) + ']';
                }
                return s;
              }
              function findLeaf(root, want){
                var all = root.querySelectorAll('*');
                var fallback = null;
                for (var i = 0; i < all.length; i++){
                  var e = all[i];
                  if (e.children && e.children.length) continue;
                  var t = (e.textContent || '').replace(/\s+/g, '');
                  if (t !== want) continue;
                  if (visible(e)) return e;
                  if (!fallback) fallback = e;
                }
                return fallback;
              }
              function tap(e){
                var r = e.getBoundingClientRect();
                var o = {
                  bubbles: true, cancelable: true, composed: true,
                  clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
                  view: e.ownerDocument.defaultView
                };
                var names = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (var i = 0; i < names.length; i++){
                  var ev;
                  try {
                    ev = (names[i].indexOf('pointer') === 0)
                      ? new PointerEvent(names[i], o) : new MouseEvent(names[i], o);
                  } catch (err) { ev = new MouseEvent(names[i], o); }
                  try { e.dispatchEvent(ev); } catch (err2) {}
                }
              }
              var docs = [];
              var f = document.getElementById('template-container');
              if (f) { try { if (f.contentDocument) docs.push(f.contentDocument); } catch (err) {} }
              docs.push(document);

              var wants = ['打开', '我的课表'];
              for (var w = 0; w < wants.length; w++){
                for (var d = 0; d < docs.length; d++){
                  var el = null;
                  try { el = findLeaf(docs[d], wants[w]); } catch (err) { el = null; }
                  if (!el) continue;
                  var body = el.ownerDocument.body;
                  var chain = [];
                  var n = el;
                  for (var k = 0; k < 5 && n && n !== body; k++, n = n.parentElement) chain.push(desc(n));
                  var target = el.parentElement || el;
                  var q = el.parentElement;
                  for (var j = 0; j < 4 && q && q !== body; j++, q = q.parentElement){
                    var cur = '', oc = null;
                    try { cur = getComputedStyle(q).cursor; } catch (err3) {}
                    if (q.getAttribute) oc = q.getAttribute('onclick');
                    if (cur === 'pointer' || q.tagName === 'A' || oc) target = q;
                  }
                  try { tap(target); } catch (err4) { return 'click-error:' + err4; }
                  return 'clicked:' + wants[w] + ':' + desc(target) + ' | chain=' + chain.join(' < ');
                }
              }
              var sample = '';
              try {
                var b = docs[0].body;
                if (b) sample = (b.innerText || '').replace(/\s+/g, ' ').slice(0, 100);
              } catch (err5) {}
              return 'not-found|' + sample;
            })()
        """.trimIndent()
    }
}

/**
 * 登录页。
 *
 * 结构与理由见 [LoginStage]：
 *  - FORM：表单盖在最上层，门户页以全尺寸但 alpha=0 的状态在背后运行；
 *  - WEB：门户页露出（alpha=1），并让出顶部一条给返回与确认按钮。
 *
 * 为何用 alpha 而不用 `visibility` 来藏：WebView 必须真的完成布局，门户 DOM 里
 * `getBoundingClientRect()` 才有非零尺寸，[CLICK_ENTRY_JS] 那句「挑可见元素来点」才有对象可点。
 * `INVISIBLE` / `GONE` 还可能让 WebView 暂停 JS 定时器，故取 alpha=0——对 View 系统而言它仍是 VISIBLE。
 *
 * 另：`AndroidView` 所在的槽位始终存在（内容随 stage 变），以免切换 stage 时它因在 Column 中的
 * 位置变化被重新挂载——那会打断正在进行的门户跳转。
 */
@Composable
private fun LoginScreen(
    stage: LoginStage,
    phase: LoginPhase,
    hint: String,
    pageInfo: String,
    cookieInfo: String,
    probeInfo: String,
    error: String,
    store: CredentialStore,
    webView: WebView,
    onSubmit: (String, String, Boolean) -> Unit,
    onUseWeb: () -> Unit,
    onManualCheck: () -> Unit,
    onGoBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val c = LocalSeuColors.current

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (stage == LoginStage.WEB) {
                WebBar(
                    hint = hint,
                    pageInfo = pageInfo,
                    cookieInfo = cookieInfo,
                    probeInfo = probeInfo,
                    phase = phase,
                    onManualCheck = onManualCheck,
                    onGoBack = onGoBack,
                    onRestart = onRestart,
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .alpha(if (stage == LoginStage.WEB) 1f else 0f),
                factory = { webView },
            )
        }

        if (stage == LoginStage.FORM) {
            LoginForm(
                phase = phase,
                hint = hint,
                error = error,
                store = store,
                onSubmit = onSubmit,
                onUseWeb = onUseWeb,
                onGoBack = onGoBack,
            )
        }
    }
}

/** 网页模式下的顶部条：返回、手动确认、重来，以及三行诊断。 */
@Composable
private fun WebBar(
    hint: String,
    pageInfo: String,
    cookieInfo: String,
    probeInfo: String,
    phase: LoginPhase,
    onManualCheck: () -> Unit,
    onGoBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(c.bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 用户点歪了能退回去（退化成一个正常浏览器该有的样子）
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onGoBack() }
                    .padding(6.dp)
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("登录校园账号", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            // 兜底出口：学校的页面万一用了没预料到的跳转（比如新窗口、需要额外点确认），
            // 用户主动确认一次即可，不用卡死在这一页。
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onManualCheck() }
                    .padding(6.dp)
            ) {
                Text("已完成", style = t.caption, color = c.primary)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(hint, style = t.micro, color = c.textSecondary)
            // 诊断三行：登录不通时能直接说明卡在哪一步。网页是排障用的界面，故只在这里显示。
            DiagLine("页面：$pageInfo")
            DiagLine("会话：$probeInfo")
            DiagLine("cookie：$cookieInfo")
            if (phase != LoginPhase.DONE) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRestart() }
                        .padding(vertical = 8.dp)
                ) {
                    Text("重新开始", style = t.micro, color = c.primary)
                }
            }
        }

        if (phase == LoginPhase.CHECKING) {
            // 规格要求：不要全屏转圈，用一条细进度条
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 登录表单：本页的默认界面。 */
@Composable
private fun LoginForm(
    phase: LoginPhase,
    hint: String,
    error: String,
    store: CredentialStore,
    onSubmit: (String, String, Boolean) -> Unit,
    onUseWeb: () -> Unit,
    onGoBack: () -> Unit,
) {
    val c = LocalSeuColors.current
    val t = LocalSeuType.current

    val savedUsername by store.savedUsername.collectAsState(initial = null)
    val hasPassword by store.hasPassword.collectAsState(initial = false)
    val autoLogin by store.autoLoginEnabled.collectAsState(initial = false)

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }

    // 学号只回填一次，不覆盖用户正在输入的内容；密码永不回填。
    var usernameFilled by remember { mutableStateOf(false) }
    LaunchedEffect(savedUsername) {
        if (!usernameFilled && !savedUsername.isNullOrBlank()) {
            username = savedUsername.orEmpty()
            usernameFilled = true
        }
    }

    // 「保存账号密码」的初值跟随用户此前在「我的 → 校园账号」里的选择，
    // 但一旦用户手动拨过，就不再被数据流覆盖。
    var rememberTouched by remember { mutableStateOf(false) }
    LaunchedEffect(autoLogin) {
        if (!rememberTouched) remember = autoLogin
    }

    val busy = phase != LoginPhase.WAITING
    val canSubmit = !busy && username.isNotBlank() && password.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onGoBack() }
                    .padding(6.dp)
            ) {
                BackIcon(c.textPrimary, 18.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("登录校园账号", style = t.navTitle, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(30.dp))
        }

        Spacer(Modifier.height(12.dp))

        // ---- 先说清楚"为什么值得填"：不少人会误以为不登录就用不了 ----
        SeuCard(Modifier.fillMaxWidth()) {
            Column {
                Text("什么时候需要登录", style = t.itemTitle, color = c.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "只在从教务导入课表和「从教务同步」时需要。查看、编辑、切换课表都不需要登录，" +
                        "断网也能正常使用。",
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
                    placeholder = "统一身份认证密码",
                    keyboardType = KeyboardType.Password,
                    masked = true,
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, style = t.micro, color = c.danger)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SeuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存账号密码", style = t.body, color = c.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasPassword) {
                            "已保存 ${savedUsername.orEmpty()}；密码经系统密钥库加密后存在本机"
                        } else {
                            "密码经系统密钥库加密后存在本机，登录态失效时自动续期"
                        },
                        style = t.micro,
                        color = c.textTertiary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                SeuToggle(
                    checked = remember,
                    onCheckedChange = {
                        rememberTouched = true
                        remember = it
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (busy) "正在登录…" else "登录",
            enabled = canSubmit,
            onClick = { onSubmit(username.trim(), password, remember) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (phase == LoginPhase.CHECKING) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        Text(hint, style = t.caption, color = c.textSecondary)

        Spacer(Modifier.height(16.dp))
        // 出口：验证码，或表单这条路走不通时，退回学校原本的网页登录。
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !busy) { onUseWeb() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("改用网页登录", style = t.itemTitle, color = c.textSecondary)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagLine(text: String) {
    Text(
        text,
        style = LocalSeuType.current.micro,
        color = LocalSeuColors.current.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
