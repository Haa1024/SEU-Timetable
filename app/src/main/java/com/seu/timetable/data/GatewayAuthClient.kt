package com.seu.timetable.data

import android.webkit.CookieManager
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 零信任网关（Sangfor aTrust）+ ehall 会话的纯 HTTP 建立。
 *
 * ## 为什么需要它
 *
 * `ehall.seu.edu.cn` 整个域已被学校零信任网关接管：未持网关会话时，任何路径
 * （根、深链、`/login?service=`、接口 `dqxnxq.do`）均被 `Server: Sangine` 302 到
 * `vpn.seu.edu.cn/controller/v1/public/verify`，后者再落到一个 **必须跑 JS 的门户 SPA**。
 * 这就是旧版登录页非用 WebView 不可的原因——但真正挡路的是**网关**，不是课表页。
 *
 * ## 这条链是怎么被读出来的
 *
 * 靠两份浏览器 HAR（`i.seu.edu.cn.har` 门户登录链 191 条、`ehall.seu.edu.cn.har` 课表链 90 条）
 * 逐跳比对，再用 `tools/GOLDEN_PATH.py` 复刻、连跑三次全 200 钉死。九步如下
 * （`VPN` = [GATEWAY_BASE]，`AUTH` = [AUTH_BASE]，`EHALL` = [EHALL_BASE]）：
 *
 * ```
 * ① GET  VPN/passport/v1/public/casLogin?sfDomain=CAS-auth      → 302 到 auth SPA，从中取回跳 service
 * ② GET  AUTH/dist/                                             → 让 auth 域有自己的会话 cookie
 * ③ POST AUTH/auth/casback/verifyTgt {service:GW_CB}            → 400（此时确实未登录），顺手把 TGT 刷活
 * ④ POST AUTH/auth/casback/casLogin  {…, service:GW_CB}         → 200 + redirectUrl（带 ST），TGT 落库
 * ⑤ GET  AUTH/auth/casback/loginRedirect?redirectUrl=<④的值>     → 302
 * ⑥ 逐个跟票，直到落在 VPN，从 query 的 data 参数里取出网关票据
 * ⑦ POST VPN/controller/v1/public/reportEnv {ticket,…}          → 网关会话成立
 * ⑧ GET  VPN/passport/v1/auth/authCheck                         → 按 XHR 要求补一个 Referer
 * ⑨ ehall 段（[establishEhall]）：首跳 → 从中取 service → verifyTgt → https 跟票 → GS_SESSIONID
 * ```
 *
 * ## 三处「差一步就全盘皆输」的地方
 *
 * **其一，`Accept` 必须分场合。** 对 ehall 发
 * `Accept: text/html,application/xhtml+xml,…` 会让它走**内容协商的「渲染 SPA 首页」**分支，
 * 直接 200 返回——而我们要的是 SSO 的 302。实测同一 URL 只改这一个头：宽松串 200（错，零 cookie）、
 * 通配符 302（对）。反过来，对 CAS 的回跳链又必须发宽松串，否则拿不到 HTML 里的跳转线索。
 * 故本类按用途分两套头（见 [acceptHtml] / [acceptAny]）。
 *
 * **其二，ehall 首跳不能带参数。** 带 `?EMAP_LANG=zh&THEME=` 会触发**网关拦截页**，
 * 于是建立起一个「verify 应用会话」（`sdp_app_session`），与「直连 CAS 会话」互相干扰，
 * 后续所有业务接口 403。首跳不带参数，ehall 才会干脆地 302 进 CAS。这是整条链上最难找的一个开关：
 * 现象是「每跳都 200/302、cookie 也都有，但接口 403」，且 cookie 矩阵实验（全带 / 只带 GS_SESSIONID /
 * GS+sdp_app / GS+route）**四种组合全都 403**，说明问题不在 cookie 而在会话时序状态。
 *
 * **其三，跟票前要把 scheme 换成 https。** CAS 下发的回跳地址常常是 `http://ehall.seu.edu.cn/...`，
 * 而 ehall 现在只认 https。直接请求 http 会被网关拦到一个 `location.replace("https://…")` 的
 * JS 跳转页——**JS 不会执行、query 全部丢失、票就没了**，而且它会以 200 返回，日志上看着「成功」。
 * 手动把 scheme 换掉再请求，才会命中同一个 CAS 过滤器并回 302 + `Set-Cookie GS_SESSIONID`。
 *
 * **其四，整条链必须共用一个 cookie 罐，且 ③ 换到的票必须真的被用上。** 这两条是同一类错误的两面：
 * 前者让 TGT「落了但发不出去」，后者让链「换了票却把票扔掉」。合起来的表现是网关回一个
 * **不带任何解释的 400 Bad Request**——看着像「网关不认我们的票」，实则是「我们根本没把票发过去」。
 * 后者尤其阴：日志上 ③ 那行写着「换到票了」，一切正常，票却没进 ⑤ 的请求。
 * 详见构造参数、③ 处注释与 [gatewayHandshake]。
 * 排查这类问题的唯一有效手段是把**每跳实际带出的 cookie 名**打出来（见 [hop] 的诊断行）——
 * 「服务端不认票」与「我们没带票」在响应上长得一模一样。
 *
 * **其五，`sdp_app_session*` 一旦被种下，业务接口必 403。** 它是网关拦截页的副产物：
 * ehall 段里只要有**带参数**的跳转撞上网关拦截页，这对 cookie 就会跟着 `GS_SESSIONID`
 * 一起躺在 ehall 域下，与 CAS 会话互相打架——现象是「`GS_SESSIONID` 有了、也发得出去，
 * 但 `dqxnxq.do` 就是 403」。故收尾时要把它们清掉（见 [clearGatewayAppSession]）。
 *
 * ## 因此本类的定位
 *
 * 「先试纯 HTTP，失败再退 WebView」。它不负责任何渲染，也不碰账号密码——
 * 密码只经 [CasAuthClient] 一次，且不经过本类。
 */
class GatewayAuthClient(
    /**
     * **整条链共用一个 cookie 罐**（踩过，代价是一整轮真机排查）。
     *
     * `MirroringCookieJar` 的内存部分是**实例私有**的，所以「换票用 A 客户端、
     * 跟票用 B 客户端」会让 B 看不到 A 刚拿到的 TGT。实测症状极具误导性：
     * ```text
     * GW #4 HTTP 302 auth.seu.edu.cn/auth/casback/loginRedirect
     * GW      set-cookie: JSESSIONID        ← 只有 JSESSIONID，没有 TGT
     * GW #6 HTTP 400 vpn.seu.edu.cn/passport/v1/auth/cas
     * ```
     * 缺了 TGT，`loginRedirect` 换不出票，网关直接 400——看着像「网关不认我们的票」，
     * 实则是「我们根本没把票发出去」。
     *
     * 注意 `WebViewCookieJar`（它镜像到 CookieManager）是全局的，
     * 所以才会有「A 落的 cookie B 在 CookieManager 里看得到、但 B 自己发不出去」这种错觉。
     */
    private val jar: MirroringCookieJar = MirroringCookieJar(),
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 逐跳走的客户端：关掉自动跟随，才能看见每一跳与沿途的 Set-Cookie。
     * 与 [http] 同源（`newBuilder()`），故共用 [jar]。
     */
    private val walker: OkHttpClient by lazy {
        http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * CAS 客户端，**必须与 [walker] 共用同一个 [jar]**——
     * 否则它换到的 TGT 落不进 walker 的罐子，下一步 `loginRedirect` 就发不出票（见构造参数注释）。
     */
    private val cas: CasAuthClient by lazy { CasAuthClient(jar) }

    /**
     * 走完整条链，并在 ehall 侧把课表会话建起来。
     *
     * 之所以自己逐跳走而不交给 OkHttp 自动跟随：要在每跳观察状态码与 cookie，
     * 且链中间有跨域跳转，自动跟随会丢过程信息。
     *
     * @param credentials 纯 HTTP 自助过 CAS 用的账号密码；为 null 则不自行登录
     *   （**凭据由调用方传入而非本类自己去 [CredentialStore] 取**：那个 store 要
     *   `Context` 且解密是 suspend，让本类保持「不发密码之外的任何 Android 依赖」更好测）。
     * @param casAuthenticated 是否已经在 CAS 侧认证过（表单登录刚成功即为 true）。
     *   为 true 时不再重复登录，直接进入换票握手——此时 TGT 已在 CookieManager 里。
     * @return 走到了哪一步的结果（判据见 [settled]）
     */
    suspend fun login(
        credentials: Credentials? = null,
        casAuthenticated: Boolean = false,
    ): GatewayLoginResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()

        // ① 网关 CAS 入口 → 302 到 auth SPA，从 fragment 里取出网关的回调地址
        val first = hop(walker, GATEWAY_CAS_ENTRY, steps)
            ?: return@withContext GatewayLoginResult.Failed("网关入口打不开", steps)

        val casService = extractService(first.requestUrl, first.location)

        if (casService.isNullOrBlank()) {
            // 入口没给出 CAS 回调地址，通常是「已有网关会话」。但 cookie 里的 sid 可能已失效，
            // 必须做行为验证——否则会把失效会话判成成功（见 [gatewaySessionUsable]）。
            if (gatewaySessionUsable()) {
                DebugLog.i("网关：入口未跳 CAS 且会话经行为验证可用 → 视为已登录")
            } else {
                DebugLog.w("网关：入口未跳 CAS，但会话验证为失效 → 需要重新认证")
                return@withContext GatewayLoginResult.Failed("网关会话已失效，需重新认证", steps)
            }
        } else {
            DebugLog.i("网关：CAS 回调 = ${casService.take(80)}")
        }

        // ② auth 域先有自己的会话 cookie，casLogin 才认（否则 500「登陆态已过期」）
        hop(walker, "$AUTH_BASE/dist/", steps, accept = acceptHtml())

        // ③ 无条件探一次会话。**这一步不能省，哪怕页面刚登录过**（踩过，见类注释「其四」）。
        //
        // 未登录时它回 400「not login」——那是预期内的；真正要的是它的**副作用**：
        // 服务端顺手 Set-Cookie 一个 TGT，并让 /auth/casback/ 这个路径在会话里"热"起来。
        // 后面 ⑤ 的 loginRedirect 同在 /auth/casback/ 下，若少了这一步，
        // 它那跳会只带 JSESSIONID、不带 TGT → 换不出票 → 网关回 400 Bad Request。
        //
        // **返回值必须留住**：它带的 `redirectUrl` 里有 CAS 刚签发的 ST，
        // 那才是 `loginRedirect` 要喂的东西（见 [gatewayHandshake] 的参数说明）。
        val probe = cas.verifyTgt(casService ?: GATEWAY_CAS_CALLBACK)
        // CAS 签发的带票回跳地址。③ 拿到就用 ③ 的，③ 没有则等 ④ 登录后那张。
        var ticketUrl = (probe as? ServiceTicket.Ok)?.redirectUrl
        steps += when (probe) {
            is ServiceTicket.Ok -> "③ 已有 TGT，换到带票回跳地址"
            ServiceTicket.NoSession -> "③ 无 TGT（预期）"
            is ServiceTicket.Failed -> "③ 探会话异常：${probe.reason}"
        }

        // ④ 手上确实没有 TGT（③ 已证），才需要真的登录一次。
        //    service 用**网关回调**：CAS 会据此把票发回网关，换票握手要的就是这张票。
        if (!cas.hasTgtInStore()) {
            if (credentials == null) {
                DebugLog.w("网关：auth 域无 TGT，且调用方没给凭据 → 交回调用方")
                return@withContext GatewayLoginResult.NoCasSession(steps)
            }
            when (val r = cas.login(
                credentials.username,
                credentials.password,
                casService ?: GATEWAY_CAS_CALLBACK,
            )) {
                is CasLoginResult.Success -> {
                    steps += "④ 表单登录成功（TGT 落库=${r.tgtInStore}）"
                    if (!r.tgtInStore) {
                        DebugLog.w("网关：casLogin 成功但 TGT 没落库 → 后续换票多半会失败")
                    }
                }
                CasLoginResult.CaptchaRequired -> {
                    DebugLog.w("网关：服务端要求验证码 → 交回调用方")
                    return@withContext GatewayLoginResult.NeedCaptcha(steps)
                }
                CasLoginResult.BadCredentials -> {
                    DebugLog.w("网关：学号或密码不对")
                    return@withContext GatewayLoginResult.Failed("学号或密码不对", steps)
                }
                CasLoginResult.SessionNotEstablished ->
                    return@withContext GatewayLoginResult.Failed("CAS 会话没建立起来", steps)
                is CasLoginResult.Failed ->
                    return@withContext GatewayLoginResult.Failed(r.reason, steps)
            }
            // ④ 的 casLogin 返回值里也带一个（已附 ST 的）回跳地址；③ 若已换到票则不必覆盖。
            if (ticketUrl.isNullOrBlank()) {
                ticketUrl = cas.verifyTgt(casService ?: GATEWAY_CAS_CALLBACK)
                    .let { (it as? ServiceTicket.Ok)?.redirectUrl }
            }
        }

        // ⑤⑥ 把 CAS 签发的**带票地址**交给 loginRedirect，逐个跟票直到落在网关域，
        //     再从落点 query 的 data 参数里取出网关票据。
        val gatewayTicket = gatewayHandshake(ticketUrl, casService, steps)
        if (gatewayTicket == null) {
            DebugLog.w("网关：没拿到网关票据 → 上报环境这一步做不了")
        } else {
            // ⑦ 上报浏览器环境：网关据此把这次访问登记成一个「浏览器会话」
            reportEnv(gatewayTicket, steps)
            // ⑧ 显式走一次 authCheck。HAR 里它在 reportEnv 之后，
            //    且必须带 Referer——裸调会 400（「没带完整会话」）。
            hop(
                walker,
                "$GATEWAY_BASE/passport/v1/auth/authCheck" +
                    "?clientType=SDPBrowserClient&platform=Windows&lang=zh-CN",
                steps,
            )
        }

        // ⑨ ehall 段
        establishEhall(steps)

        settled(steps)
    }

    /**
     * ehall 侧的会话建立——**这是整条链最后、也最容易翻车的一段**。
     *
     * ```
     * a. GET  EHALL:443/jwapp/sys/wdkb/<星号>default/index.do      ← 首跳！不带任何参数
     * b. 若 a 的响应体里有 `verify?t=<JWT>`，说明撞上了网关拦截页，
     *    先跟一遍那个 verify，再带 `?EMAP_LANG=zh&THEME=` 重新走 a 的地址
     * c. 从最终落在 auth SPA 的 fragment 里取 service（= 课表微应用的 CAS 服务名）
     * d. POST verifyTgt {service: c}                              → redirectUrl
     * e. 把 redirectUrl 的 scheme 由 http 换成 https，请求它      → 302 + Set-Cookie GS_SESSIONID
     * ```
     *
     * a 不带参数的理由、e 要换 scheme 的理由，都见类注释。
     * 顺带一提，c 拿到的 service 形如
     * `http://ehall.seu.edu.cn/jwapp/sys/wdkb/<星号>default/index.do?…`（`<星号>` 即字面 `*`，
     * 此处用文字写以免触发 Kotlin 的嵌套块注释），它**必须逐字符还原**
     * （`*` 不能编成 `%2A`，http 不能改 https），否则会在 d 那步拿到
     * `ticket=Unauthorized Service`——那种失败最像「网络不通」，其实一个字符都不差才行。
     */
    private suspend fun establishEhall(steps: MutableList<String>) {
        val firstPath = "$EHALL_BASE:443$EHALL_APP/*default/index.do"
        val withParams = "$EHALL_BASE$EHALL_APP/*default/index.do?EMAP_LANG=zh&THEME="

        // wantBody：要在这一跳的响应体里找 `verify?t=<JWT>`，不读 body 就找不到。
        // 首跳必须是**不带参数**的那个地址——带 `?EMAP_LANG=zh&THEME=` 会触发网关拦截页，
        // 建出一个 verify 应用会话与 CAS 会话互相干扰，后续接口全 403（见类注释「其二」）。
        val head = hop(walker, firstPath, steps, accept = acceptHtml(), wantBody = true)
            ?: return

        val verifyToken = GATEWAY_VERIFY_JWT.find(head.bodySnippet)?.groupValues?.get(1)
        if (verifyToken != null) {
            steps += "ehall 首跳撞上网关拦截页 → 跟 verify?t=…（要跟到底）"
            // **必须用 follow 而非 hop**（踩过）：verify 那条链不止一跳——
            // `verify?t=` → 302 回 ehall → 再 302 进 CAS SPA。只 hop 一跳会停在 ehall，
            // 于是下一步从落点取不到 service，ehall 段直接报废。
            follow(walker, "$GATEWAY_BASE/controller/v1/public/verify?t=$verifyToken", steps)
        } else {
            steps += "ehall 首跳未带拦截页（HTTP ${head.code}），直接试 CAS"
        }

        // 落点应是 auth SPA 的 hash 路由地址（`#/dist/main/login?service=…`），
        // service 就藏在 `#` 之后的片段里——这是 ehall 微应用的 CAS 服务名。
        val landing = follow(walker, withParams, steps)
        val service = fragmentService(landing) ?: run {
            steps += "没从落点里取到 service（落点=${mask(landing)}）→ ehall 段无法继续"
            return
        }
        steps += "ehall 服务名 = ${service.take(60)}…"

        when (val t = cas.verifyTgt(service)) {
            is ServiceTicket.Ok -> {
                // e 的关键一步：http → https
                val https = t.redirectUrl.replaceFirst("http://ehall.seu.edu.cn",
                    "https://ehall.seu.edu.cn")
                if (https == t.redirectUrl) {
                    steps += "换票回址已是 https，无需改写"
                } else {
                    steps += "换票回址把 http 改写成 https（http 直连会被 JS 跳转页吞掉 query）"
                }
                // 这一跳会带回 GS_SESSIONID —— ehall 侧会话就此成立。
                // 用 follow 收尾：中间可能有 302，停在第一个 200 会把 Set-Cookie 漏在后面。
                follow(walker, https, steps)

                // 光有 GS_SESSIONID 还不够：ehall 的应用级授权要先走一遍，
                // 业务接口（dqxnxq.do 等）才放行——否则恒 403，而 cookie 明明都在。
                // WebView 之所以没这个问题，是因为课表页自己会发这批初始化请求。
                appAuth(steps)

                // 清掉网关拦截页的副产物。顺序要紧：**换票与授权之后才清**。
                clearGatewayAppSession(steps)
            }
            ServiceTicket.NoSession -> steps += "ehall 段换票：无有效 TGT"
            is ServiceTicket.Failed -> steps += "ehall 段换票失败：${t.reason}"
        }
    }

    /**
     * 走一遍课表微应用的**应用级授权**。
     *
     * 为什么非做不可：ehall 拿到 `GS_SESSIONID` 只代表「这个人通过了统一身份认证」，
     * 不代表「这个应用对他开放」。金智 ehall 在首次访问某个微应用时要先跑一次
     * `funauthapp/api/getAppConfig` 建立应用上下文，之后该应用的业务接口才放行。
     *
     * 缺了它会怎样：`dqxnxq.do` 恒 **403**（ehall 自己的错误页 `<title>403</title>`，
     * 不是网关拦的），而 `GS_SESSIONID` 明明在、也发得出去——最难查的一类「看着都对」。
     * WebView 路径之所以没这问题，是因为课表页自己会发这批初始化请求。
     *
     * 参数字面量（`wdkb-4770397878132218` 这个 appId 与 `v=028544385712304654`）
     * 来自 HAR 实录，**不是可以随便改的常量**：appId 与微应用一一对应。
     */
    private fun appAuth(steps: MutableList<String>) {
        val url = "$EHALL_BASE/jwapp/sys/funauthapp/api/getAppConfig/wdkb-4770397878132218.do?v=028544385712304654"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$EHALL_BASE$EHALL_APP/*default/index.do")
            .get()
            .build()
        runCatching {
            walker.newCall(req).execute().use { r ->
                val head = runCatching { r.peekBody(200).string() }.getOrDefault("")
                    .replace(Regex("\\s+"), " ").trim()
                steps += "应用授权 getAppConfig HTTP ${r.code} → ${head.take(80)}"
            }
        }.onFailure { steps += "应用授权 getAppConfig 异常：${it.message}" }
    }

    /**
     * 清掉 ehall 域下网关拦截页留下的 `sdp_app_session*`。
     *
     * 它们与 CAS 会话互相干扰，会让业务接口恒 403，而 `GS_SESSIONID` 明明在、也发得出去——
     * 这种「看起来什么都对，就是 403」最难查（cookie 矩阵实验：全带 / 只带 GS_SESSIONID /
     * GS+sdp / GS+route，四种组合全 403）。
     *
     * ## 两个必须照做的细节（都踩过）
     *
     * ① **`Path` 必须用 cookie 自己的**。这些 cookie 的 path 是
     *    `/jwapp/sys/wdkb/<星号>default`（不是 `/`），写成 `Path=/` 会静默删不掉——
     *    日志上「已清」打得出来，实际一个没删。同一个坑在
     *    `LoginActivity.clearEhallCookies` 里也踩过，那里的注释有更细的说明。
     * ② **内存那份也要抹**（[MirroringCookieJar.forget]）。只删 `CookieManager` 的话，
     *    本客户端下一次请求仍会从内存把它们带上。
     *
     * @param steps 只为记录删了哪些名字（名字不是凭证，值不入日志）
     */
    private fun clearGatewayAppSession(steps: MutableList<String>) {
        val doomed = runCatching {
            jar.loadForRequest(EHALL_BASE.toHttpUrlOrNull() ?: return)
                .filter { it.name.startsWith("sdp_app_session") }
        }.getOrDefault(emptyList())

        if (doomed.isEmpty()) {
            steps += "无需清 sdp_app_session（本就没种下）"
            return
        }

        val cm = CookieManager.getInstance()
        doomed.forEach { c ->
            runCatching {
                // 关键：URL 用 EHALL_BASE，cookie 串里**只写 `Path=`（且是 cookie 自己的 path）、
                // 绝不写 `Domain=`**。写了 Domain 反而会让 CookieManager 认为「域不匹配」而静默失败
                // ——同一个坑在 LoginActivity.clearEhallCookies 里已经趟过一次，那里有详细说明。
                cm.setCookie(
                    EHALL_BASE,
                    "${c.name}=; Path=${c.path}; " +
                        "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0",
                )
            }
        }
        runCatching { cm.flush() }
        jar.forget(doomed)

        // 自查：删不掉是最容易发生的事（path/domain 任一不匹配就静默失败），故回读一遍。
        val left = runCatching {
            val raw = cm.getCookie("$EHALL_BASE$EHALL_APP/modules/jshkcb/dqxnxq.do").orEmpty()
            raw.split(';').map { it.trim().substringBefore('=') }
                .filter { it.startsWith("sdp_app_session") }
        }.getOrDefault(emptyList())

        steps += "已清 sdp_app_session：${doomed.map { it.name }.distinct().joinToString(",")}" +
            "（残留=${if (left.isEmpty()) "无" else left.joinToString(",")}）"
    }

    /**
     * 网关自己的换票握手：CAS 下发的**带票地址** → loginRedirect → 网关 → 取网关票据。
     *
     * 中间那步 `loginRedirect?redirectUrl=` **不换票**，只是把参数原样 302 回吐；
     * 正因如此，**你必须喂给它一个已经带票的地址**——票是 `verifyTgt` 早就换好的，
     * 这一步只负责把票「送」到网关域。喂裸地址（不带 `ticket=ST-…`）的结果是：
     * 网关收到一个没有任何票据的请求，回一个**不带任何解释的 400 Bad Request**。
     *
     * ## 这里踩过一个大坑（务必别退回原样）
     *
     * 原实现只收 `casService`，自己拼 `loginRedirect?redirectUrl=<裸的网关回调>`——
     * 于是 ③ 辛苦换到的票被**扔掉**，整条链在网关那跳必然 400。
     * 而日志上 ③ 那行写的是「换到票了」，看着一切正常，极难发现票没被用上。
     * 故现在改成：**必须把 ③/④ 换到的带票地址传进来**（[ticketUrl]）。
     *
     * `casService` 仍要传，仅用于 `ticketUrl` 为空时兜底拼一次（此时多半会失败，
     * 但至少能走完流程、把失败现场留在 steps 里便于排查）。
     *
     * @param ticketUrl ③/④ 由 `verifyTgt` 换到的带票回跳地址（含 `ticket=ST-…`），可为空
     * @param casService 网关的 CAS 回调地址（`casService ?: GATEWAY_CAS_CALLBACK` 的兜底来源）
     * @return 网关票据（`data` 参数里 `ticket` 字段的值），拿不到返回 null
     */
    private fun gatewayHandshake(
        ticketUrl: String?,
        casService: String?,
        steps: MutableList<String>,
    ): String? {
        val start = if (!ticketUrl.isNullOrBlank()) {
            steps += "⑤ 用 verifyTgt 换到的带票地址喂 loginRedirect"
            AUTH_LOGIN_REDIRECT + "?redirectUrl=" + escapeServiceForQuery(ticketUrl)
        } else {
            // 兜底：手上没票。HAR 里这条不会成功，留着只为把现场记进 steps。
            steps += "⑤ 手上没有带票地址（verifyTgt 未换到票）→ 用裸回调兜底"
            AUTH_LOGIN_REDIRECT + "?redirectUrl=" +
                escapeServiceForQuery(casService ?: GATEWAY_CAS_CALLBACK)
        }

        // loginRedirect 回的是 302，Location 即网关回调地址（**带着 ST**）；follow 会一路跟到落点，
        // 落点上才挂着 `data=<JSON>`（网关票据在里面）。
        val first = hop(walker, start, steps, accept = acceptHtml()) ?: return null
        if (first.code !in 300..399) {
            steps += "loginRedirect 没回 302（HTTP ${first.code}）→ 无法取网关票据"
            return null
        }
        val landed = resolve(first.requestUrl, first.location) ?: return null
        return extractGatewayTicket(follow(walker, landed, steps))
    }

    /**
     * 从落在网关域的 URL 上取出 `data` 参数里的 `ticket`。
     *
     * 网关的回跳形如 `https://vpn.seu.edu.cn/…/callback?data=<已编码 JSON>`，
     * JSON 里才有真正的 `ticket`。**取不到不致命**：后续 reportEnv 与业务接口才是判据，
     * 这里返回 null 只是让链少走两步。
     */
    private fun extractGatewayTicket(url: String): String? = runCatching {
        val encoded = url.toHttpUrlOrNull()?.queryParameter("data") ?: return null
        val json = Json.parseToJsonElement(URLDecoder.decode(encoded, "UTF-8"))
        json.jsonObject["ticket"]?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * 上报浏览器环境，把这次访问登记成「浏览器会话」。
     *
     * `deviceId` 每次随机 32 位小写字母数字——HAR 里就是个随机串，
     * 复用不会更「真」，反而会让多次登录看起来像同一台设备。
     */
    private fun reportEnv(ticket: String, steps: MutableList<String>) {
        val dev = (1..32).map { DEV_ALPHABET.random() }.joinToString("")
        val body = buildString {
            append("{\"ticket\":\"").append(ticket.replace("\"", "\\\""))
            append("\",\"deviceId\":\"").append(dev)
            append("\",\"env\":{\"endpoint\":{\"device_id\":\"").append(dev)
            append("\",\"device\":{\"type\":\"browser\"}}}}")
        }
        val req = Request.Builder()
            .url("$GATEWAY_BASE/controller/v1/public/reportEnv")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Origin", GATEWAY_BASE)
            .header("Referer", GATEWAY_BASE + "/")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        runCatching {
            walker.newCall(req).execute().use { r ->
                steps += "#reportEnv HTTP ${r.code}"
                runCatching { r.peekBody(1).close() }
            }
        }.onFailure { steps += "reportEnv 异常：${it.message}" }
    }

    /**
     * 走完链路后的统一判定：**看会话是否立起来了**。
     *
     * 之所以两个都认（网关 `sid` 或 ehall `GS_SESSIONID`）：不同路径建起的会话层级不同，
     * 而**最终判据只该有一个**——能不能读到课表。那个由调用方 [LoginActivity.probeOnce]
     * 用真实业务接口负责（本方法只做快速剪枝，避免明显没戏时还白探一次）。
     *
     * 曾经这里的判据写错过两回，都是「拿间接指标当结论」：
     *  - 只看网关 `sid` 存在 → 失效会话会被判成成功（`sid` 过期后 cookie 不会自动消失）；
     *  - 只看 ehall `GS_SESSIONID` → 「把票直接送课表页」那条错路会误判
     *    （那页是 SPA 外壳，200 却不发任何 cookie，两侧都查不到，于是成功也报失败）。
     * 教训：cookie 名只能作剪枝，**真判据必须是一个真实业务请求的结果**。
     */
    private fun settled(steps: MutableList<String>): GatewayLoginResult {
        val gatewayOk = gatewaySessionPresent() && gatewaySessionUsable()
        if (gatewayOk) {
            DebugLog.i("网关：网关会话已建立且经行为验证可用")
            return GatewayLoginResult.Success(steps)
        }
        if (ehallSessionPresent()) {
            DebugLog.i("网关：ehall 会话已建立（GS_SESSIONID 就位）")
            return GatewayLoginResult.Success(steps)
        }
        DebugLog.w(
            "网关：链路走完但会话仍未就位（gateway=${gatewaySessionPresent()} " +
                "ehall=${ehallSessionPresent()}）"
        )
        return GatewayLoginResult.Failed("走完链路仍未建立会话", steps)
    }

    /** ehall 侧会话 cookie 是否在库。名字可能随 ehall 版本变，故按前缀宽松匹配。 */
    private fun ehallSessionPresent(): Boolean = runCatching {
        val raw = CookieManager.getInstance().getCookie(EHALL_BASE) ?: return false
        raw.split(';').any { it.trim().startsWith("GS_SESSIONID") && it.trim().length > 12 }
    }.getOrDefault(false)

    /**
     * 网关会话是否**真的**可用——不能只看 cookie 里有没有 `sid`。
     *
     * 为何不信 cookie 名：`sid` 是会过期的，但过期后 cookie **不会自动消失**。
     * 只查名字会把一个失效会话判成「已登录」，于是：
     *   - 入口不再 302 到 CAS（服务端看到 cookie 就不跳），[login] 走 `Success` 分支；
     *   - 但 ehall 侧探测恒 401，最终还是退回 WebView。
     * 现象是「日志说纯 HTTP 走通了，实际什么也没解决」，并白等一次探测（约 1s）。
     *
     * 判据改为**行为验证**：拿当前 cookie 去请求网关受保护入口，
     * 若被 302 到 CAS 登录页（而非直接落到门户）即说明会话无效，需重新过 SSO。
     */
    private fun gatewaySessionUsable(): Boolean {
        val probe = hop(walker, GATEWAY_CAS_ENTRY, mutableListOf()) ?: return false
        // 会话有效时：不跳 CAS，直接 302 到门户或 200 返回页面
        // 会话失效时：同样 302，但 Location 指向 CAS 登录页（含 auth.seu.edu.cn 或 service= 参数）
        val loc = probe.location.orEmpty()
        val bouncedToCas = loc.contains(AUTH_BASE) || loc.contains("service=")
        if (bouncedToCas) {
            DebugLog.i("网关：cookie 里有 sid 但已被弹回 CAS → 会话已失效，需重新过 SSO")
            return false
        }
        return true
    }

    /** 网关会话 cookie 是否存在。**仅用于日志/快速判断**，判定可用性请用 [gatewaySessionUsable]。 */
    fun gatewaySessionPresent(): Boolean = runCatching {
        val raw = CookieManager.getInstance().getCookie(GATEWAY_BASE) ?: return false
        raw.split(';').any { it.trim().startsWith("sid=") && it.trim().length > 4 }
    }.getOrDefault(false)

    // ---------------- 内部 ----------------

    /**
     * 一跳的响应快照。只留必要字段，避免把 `Response` 传出去（易忘记关闭）。
     *
     * `bodySnippet` 只在需要「读页面里的线索」时才填（目前唯一用途是找 `verify?t=<JWT>`），
     * 且已截断到 4KB——普通跳转的 body 有几百 KB，全读进来纯属浪费。
     */
    private data class HopResult(
        val code: Int,
        val location: String?,
        val requestUrl: String,
        val bodySnippet: String = "",
    )

    /**
     * 发一跳并立即把响应读完关闭。
     *
     * 必须读+关：OkHttp 的 CookieJar 是在**响应体被消费或关闭时**才落 cookie 的，
     * 只拿到 response 不消费，网关下发的 `sid` 就永远进不了 [CookieManager]，
     * 表现为「链路每跳都 200/302，但登录死活不成功」。
     *
     * @param accept 必须按用途给（见类注释第一条）。默认 [acceptAny]：
     *   对 CAS/网关的 302 链要通配符 `星号/星号`（此处用文字写，避免 `星号/` 提前闭合块注释），
     *   对要读 HTML 线索的页面要 [acceptHtml]。
     * @param wantBody 需要从响应体里读线索时置 true（目前只有找 `verify?t=<JWT>` 用）。
     * @return 已关闭的响应快照；失败返回 null
     */
    private fun hop(
        walker: OkHttpClient,
        url: String,
        steps: MutableList<String>,
        accept: String = acceptAny(),
        wantBody: Boolean = false,
    ): HopResult? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        // 诊断：本次请求**实际带出去**的 cookie 名 + domain/path（只名字与属性，值即凭证不入日志）。
        // 这是分辨「服务端不认票」与「我们没把票发出去」的唯一直接证据——
        // 两者在响应上几乎一样（都是 400/403），没有这一行就只能猜。
        steps += "     sent-cookie: " + sentCookieNames(url)

        walker.newCall(req).execute().use { r ->
            val snippet = when {
                wantBody -> runCatching { r.peekBody(BODY_SNIFF_LIMIT).string() }.getOrDefault("")
                r.code >= 400 -> runCatching {
                    r.peekBody(512).string().replace(Regex("\\s+"), " ").trim().take(200)
                }.getOrDefault("")
                else -> ""
            }
            val snapshot = HopResult(
                code = r.code,
                location = r.header("Location"),
                requestUrl = r.request.url.toString(),
                bodySnippet = snippet,
            )
            steps += "#${steps.size + 1} HTTP ${r.code} ${mask(snapshot.requestUrl)}"
            r.headers("Set-Cookie").forEach { steps += "     set-cookie: ${maskCookie(it)}" }
            // 诊断：回跳目标也打出来（同样只留 host+path，query 里可能有票据）
            r.header("Location")?.let { steps += "     location: ${mask(resolve(snapshot.requestUrl, it) ?: it)}" }
            if (snippet.isNotEmpty() && !wantBody) steps += "     body: $snippet"
            // use{} 退出时会关闭 body；在此前读完即可触发 cookie 落库
            runCatching { r.peekBody(1).close() }
            snapshot
        }
    }.onFailure { steps += "请求异常：${it.message}" }.getOrNull()

    /**
     * 诊断用：某个 URL 上 [jar] 实际会带出哪些 cookie 名，附带各 cookie 的 domain/path。
     *
     * 有了它，「服务端不认票」与「我们没把票发出去」才能一刀切开——
     * 前者 cookie 里**有** TGT 却仍被 400，后者是 cookie 里**根本没有** TGT。
     * 只看 `CookieManager` 是分辨不出的：那是全局存储，能查到不等于本客户端发得出。
     *
     * 带上 `domain/path` 是因为「同域却取不到」这种现象最容易被误判成内存不共享，
     * 实则多半是 `Domain`/`Path` 属性与目标 URL 不匹配（`Cookie.matches` 逐条判定）。
     */
    private fun sentCookieNames(url: String): String = runCatching {
        val u = url.toHttpUrlOrNull() ?: return "<?>"
        val all = jar.loadForRequest(u)
        if (all.isEmpty()) return "<无>"
        all.joinToString(" ") { "${it.name}@${it.domain}${it.path}" }
    }.getOrDefault("<查询失败>")

    /**
     * 跟一串 30x，返回最终落点 URL。
     *
     * 与 [hop] 的区别：hop 只看一跳（要挑出「这一跳有没有给我 Location」），
     * follow 只想尽快到达目的地（读 `verify?t=` 之后的落点、拿 service 都要它）。
     */
    private fun follow(walker: OkHttpClient, start: String, steps: MutableList<String>): String {
        var url = start
        var n = 0
        while (n++ < MAX_HOPS) {
            val h = hop(walker, url, steps, accept = acceptHtml()) ?: return url
            if (h.code !in 300..399) return url
            url = resolve(h.requestUrl, h.location) ?: return url
        }
        steps += "已到跳数上限 $MAX_HOPS"
        return url
    }

    /**
     * 从 URL 的 fragment（`#` 之后）里取 `service`。
     *
     * 学校的 CAS 站点是 hash 路由 SPA，回跳地址形如
     * `https://auth.seu.edu.cn/dist/#/dist/main/login?service=…`——
     * `service` 挂在 **`#` 之后的片段**里，而不是真正的查询串。
     * `HttpUrl.queryParameter()` 只看 `?` 之前的部分，因此不能只问它。
     *
     * 手工解析而非用 `HttpUrl`：片段不是合法 URL，`HttpUrl` 无法直接吃下。
     */
    private fun fragmentService(url: String): String? {
        val fragment = url.substringAfter('#', "")
        if (fragment.isEmpty()) return null
        val query = fragment.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == "service" }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    /** 相对 Location 按当前请求地址解析 */
    private fun resolve(baseUrl: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { baseUrl.toHttpUrlOrNull()?.resolve(location)?.toString() }.getOrNull()
    }

    /** 日志里只留主机与路径，去掉可能的 ticket/redirectUrl 值 */
    private fun mask(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return "<非法 URL>"
        return "${u.host}${u.encodedPath}"
    }

    /** 只留 cookie 名与属性，值即凭证不可入日志 */
    private fun maskCookie(raw: String): String {
        val name = raw.substringBefore('=').trim()
        val attrs = raw.substringAfter(';', "").trim()
        return if (attrs.isEmpty()) "$name=<已隐藏>" else "$name=<已隐藏>; $attrs"
    }

    companion object {
        /** 网关基址 */
        const val GATEWAY_BASE = "https://vpn.seu.edu.cn"

        /**
         * 网关 CAS 登录入口。由 authConfig 下发的 `firstAuth[0]`，
         * `sfDomain=CAS-auth` 指定「校内人员」这条认证通道，勿省。
         */
        const val GATEWAY_CAS_ENTRY = "$GATEWAY_BASE/passport/v1/public/casLogin?sfDomain=CAS-auth"

        /**
         * 网关的 CAS 回调地址。`verifyTgt` 换票时的 service 就填它。
         * `:443` 是 HAR 里服务端自己下发的原样写法，**保留**——CAS 逐字符比对。
         */
        const val GATEWAY_CAS_CALLBACK =
            "$GATEWAY_BASE:443/passport/v1/auth/cas?sfDomain=CAS-auth"

        private const val MAX_HOPS = 14

        /** 只在这个上限内嗅探 body，避免为找一行线索把几百 KB 首页读进来 */
        private const val BODY_SNIFF_LIMIT = 4096L

        private const val DEV_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        private val JSON_TYPE = "application/json".toMediaType()

        /** 网关拦截页里那句 `…/verify?t=<JWT>`，JWT 字符集实测只有这几类 */
        private val GATEWAY_VERIFY_JWT = Regex("verify\\?t=([A-Za-z0-9_.\\-]+)")

        /**
         * 要浏览器来渲染的页面（CAS 跳转链、ehall 首跳）用这套头。
         * 反面教材见类注释「其一」。
         */
        private fun acceptHtml(): String =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        /** 接口、以及**所有对 ehall 的 SSO 请求**用这套。缺了它 ehall 会 200 返回 SPA 外壳。 */
        private fun acceptAny(): String = "*/*"
    }
}

/** 网关登录结果。**多分支而非 Boolean**：调用方要据此决定「退 WebView」还是「报错」。 */
sealed interface GatewayLoginResult {

    /** 网关会话已建立，ehall 应已可达 */
    data class Success(val steps: List<String>) : GatewayLoginResult

    /** CAS 侧没有可用 TGT（尚未认证）→ 应先去 [CasAuthClient.login]，或退 WebView */
    data class NoCasSession(val steps: List<String>) : GatewayLoginResult

    /** 服务端这次要验证码，纯 HTTP 给不出来，只能交还用户 */
    data class NeedCaptcha(val steps: List<String>) : GatewayLoginResult

    /** 网络 / 协议异常 */
    data class Failed(val reason: String, val steps: List<String>) : GatewayLoginResult
}

/**
 * 把 [service] 塞进 query 时的转义。
 *
 * **只转义 `?` 与 `&`**，其余一律原样——这条规则是被 CAS 逼出来的（见 [CasAuthClient] 类注释）：
 *
 *  - 不转义那两个字符 → `service` 的值会在 `?` / `&` 处被服务器当成参数分隔符截断，
 *    ehall 认不出 service，直接 200 返回首页（而不是 302 到 CAS）——
 *    现象是「链路成功但零 cookie」；
 *  - 转义过头（整体 URLEncoder）→ `*` 变 `%2A`、`:` 变 `%3A`，
 *    而 CAS 是逐字符比对注册值的，于是判成「发给别的服务的票」。
 *
 * 抽成顶层纯函数是为了能被测试钉住：这两头都踩过，且两次的现象都不像「转义问题」。
 */
internal fun escapeServiceForQuery(service: String): String =
    service.replace("?", "%3F").replace("&", "%26")

/**
 * 从网关入口的 302 响应里取出 CAS 回调地址（即 `service` 参数）。
 *
 * 抽成纯函数以便单测：这是整条链的**关键接缝**——取错则后续换票会被 CAS 判为
 * 发给别的服务而静默失败，且失败现象与「网络不通」极像，靠日志很难分辨。
 *
 * ## 必须同时看 fragment（实测踩到）
 *
 * 学校的 CAS 站点是 hash 路由的 SPA，回跳地址形如
 * `https://auth.seu.edu.cn/dist/#/dist/main/login?service=…`——
 * `service` 挂在 **`#` 之后的片段**里，而不是真正的查询串。
 * 而 `HttpUrl.queryParameter()` 只看 `?` 之前的部分，对 `#` 之后一律视为 fragment，
 * 因此不能只问它：**先试 fragment，再退回真正的 query**。
 *
 * 同理，`service` 的值自身带 `?` 与 `=`（如
 * `https://vpn.seu.edu.cn:443/passport/v1/auth/cas?sfDomain=CAS-auth`），
 * 在 Location 中整体 URL 编码，故必须按「片段里的查询串」解析而非手工切分。
 *
 * @param baseUrl 发起请求的绝对地址（用于解析相对 Location）
 * @param location 响应头 `Location`，为 null / 空 / 不含 service 时返回 null
 */
internal fun extractService(baseUrl: String, location: String?): String? {
    if (location.isNullOrBlank()) return null

    val resolved = runCatching {
        (baseUrl.toHttpUrlOrNull()?.resolve(location))?.toString()
    }.getOrNull() ?: return null

    // ① 优先在 `#` 之后的片段里找（hash 路由 SPA 的实际形态）
    val fragment = resolved.substringAfter('#', "")
    if (fragment.isNotEmpty()) {
        fragmentServiceIn(fragment)?.let { return it }
    }

    // ② 退回真正的查询串（普通 302 的情况）
    return runCatching {
        resolved.toHttpUrlOrNull()?.queryParameter("service")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * 从 `#` 之后的片段里取 `service`（顶层函数版，供 [extractService] 使用；
 * 类内那份是同逻辑的实例方法，因 [GatewayAuthClient] 要在自己链路上直接调用）。
 */
private fun fragmentServiceIn(fragment: String): String? {
    val query = fragment.substringAfter('?', "")
    if (query.isEmpty()) return null
    return query.split('&')
        .firstOrNull { it.substringBefore('=') == "service" }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}
