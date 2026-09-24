package com.seu.timetable.data

import android.webkit.CookieManager
import com.seu.timetable.domain.TermContext
import com.seu.timetable.domain.UnplacedCourse
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal const val EHALL_BASE = "https://ehall.seu.edu.cn"
internal const val EHALL_APP = "/jwapp/sys/wdkb"

/**
 * 课表微应用地址，注意 `*default` 中的字面星号。
 * 此串是 CAS 换票 `service` 参数的一部分，必须逐字符还原：不能用 URLEncoder 编码
 * （会把 `*` 编成 `%2A`），也不能改 https。服务端下发的就是 http + 字面 `*`，
 * 差一字符票据会被判为发给别的服务而静默失败。
 */
internal const val EHALL_APP_PATH = "/jwapp/sys/wdkb/*default/index.do?EMAP_LANG=zh&THEME="

internal const val CAS_SERVICE = "http://ehall.seu.edu.cn$EHALL_APP_PATH"

/**
 * 纯服务端可走完的 SSO 入口。SPA 将 service 放在 `#` 片段后（片段不发服务器，
 * 换票需页面 JS）；`/login?service=` 由服务端 302 直达，不依赖 JS，可用 OkHttp 追链。
 *
 * ## 为什么 service 必须做「部分转义」（踩过，代价是一次完整的调试轮）
 *
 * [CAS_SERVICE] 里自带 `?EMAP_LANG=zh&THEME=`。若原样拼进 query，
 * 那个 `&` 会被服务器当成 **`/login` 自己的参数分隔符**，于是 `service` 的值被
 * 截断成 `...index.do?EMAP_LANG=zh`，`THEME` 另起一个参数。
 * 后果是 ehall 认不出 service，**直接返回 200 首页而不是 302 到 CAS**——
 * 现象极具迷惑性：链路「成功」了（HTTP 200），但一个 cookie 都没有，会话根本没建。
 *
 * 但也不能整体 URLEncoder：它会把 `*` 编成 `%2A`、`:` 编成 `%3A`，
 * 而 CAS 认 service 是**逐字符比对**的（服务端下发的就是 `http://` + 字面 `*`），
 * 差一字符就判成「发给别的服务的票」而静默失败。
 *
 * 故只转义 `?` 与 `&` 这两个会破坏 query 结构的字符，其余原样保留。
 */
internal val SSO_ENTRY =
    "$EHALL_BASE/login?service=" + CAS_SERVICE.replace("?", "%3F").replace("&", "%26")

/** 换票链的最大跳数，防重定向死循环 */
private const val MAX_SSO_HOPS = 12

/**
 * OkHttp 请求 UA，与 WebView 保持一致。默认 okhttp UA 易被 WAF 拉黑，
 * 且与 WebView 非同一「用户」，服务端无法关联两者、可能拒掉接口请求。
 */
internal const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

/** 接口会多返回一堆用不上的字段，必须忽略未知键 */
private val EHALL_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * HTTP 失败状态码 → 异常。抽为纯函数以便单测；401/403 必须映射为
 * [NotLoggedInException]，否则 UI 会把「未登录」误报为「同步失败」。
 */
internal fun httpError(code: Int, message: String, path: String): TimetableException = when (code) {
    401, 403 -> NotLoggedInException("登录态已失效（HTTP $code）")
    else -> TimetableException("HTTP $code $message <- $path")
}

/**
 * 将 OkHttp 的 cookie 存取接入 WebView 的 [CookieManager]。
 * 本 App 不保存账号密码、不实现统一认证：用户在 WebView 登录一次，
 * 之后 OkHttp 经同一 CookieManager 自动带会话调用 API，账号密码不经本 App。
 */
class WebViewCookieJar : CookieJar {

    private val manager: CookieManager? get() = runCatching { CookieManager.getInstance() }.getOrNull()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager ?: return
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = manager ?: return emptyList()
        val header = cm.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

/**
 * ehall 课表微应用 API 客户端。业务接口均为 POST + form-urlencoded，
 * 响应外壳统一为 `{"datas": {"<接口名>": {"rows": [...]}}, "code": "0"}`。
 */
class EhallClient(
    cookieJar: CookieJar = WebViewCookieJar(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    // ---------------- 端点 ----------------

    /** 当前学年学期。用于首次启动自动选中学期。 */
    suspend fun currentTerm(): DqxnxqRow? =
        rowsOf(
            post("$EHALL_APP/modules/jshkcb/dqxnxq.do", ""),
            "dqxnxq",
            EhallRows.serializer(DqxnxqRow.serializer()),
        ).firstOrNull()

    /** 学期上下文：第一周周一、总周数、上午/下午/晚上各几节 */
    suspend fun termContext(termCode: String): TermContext {
        val tc = TermCode.parse(termCode)
        val row = rowsOf(
            post("$EHALL_APP/modules/jshkcb/cxjcs.do", "XN=${tc.year}&XQ=${tc.term}"),
            "cxjcs",
            EhallRows.serializer(CxjcsRow.serializer()),
        ).firstOrNull() ?: throw TimetableException("cxjcs 没返回数据，学期代码可能不对：$termCode")

        return EhallMapper.toTermContext(row, termCode)
    }

    /**
     * 主课表原始行（一行 = 一个时间块），由 [EhallTimetableSource] 组装成两层模型。
     * 注意不带 `SKZC` 参数（服务端按周过滤）；实测带 `SKZC=1` 仅返回 2 行，全量 7 行。
     */
    internal suspend fun fetchTimetableRows(termCode: String): List<XskcbRow> =
        rowsOf(
            post("$EHALL_APP/modules/xskcb/xskcb.do", "*order=%2BKSJC&XNXQDM=$termCode"),
            "xskcb",
            EhallRows.serializer(XskcbRow.serializer()),
        )

    /**
     * 未排课课程（含学分/学时）。带 `SKZC=1` 照原始请求筛选「当前有效」，
     * 返回的 `SKZC` 为文本而非位图。
     */
    suspend fun unplacedCourses(termCode: String): List<UnplacedCourse> =
        EhallMapper.toUnplaced(
            rowsOf(
                post("$EHALL_APP/modules/xskcb/xswpkc.do", "*order=%2BKSJC&XNXQDM=$termCode&SKZC=1"),
                "xswpkc",
                EhallRows.serializer(XswpkcRow.serializer()),
            )
        )

    /** 某日期是第几周。服务端权威值，能处理调课/假日，比本地算更准。 */
    suspend fun weekOf(termCode: String, date: LocalDate): Int? {
        val tc = TermCode.parse(termCode)
        return rowsOf(
            post("$EHALL_APP/modules/jshkcb/dqzc.do", "XN=${tc.year}&XQ=${tc.term}&RQ=$date"),
            "dqzc",
            EhallRows.serializer(DqzcRow.serializer()),
        ).firstOrNull()?.week?.asIntOrNull()
    }

    /**
     * 探测会话状态，返回三段结果而非 Boolean：「未登录」与「接口出错」需分别引导登录/重试，
     * 否则未登录会被报成同步失败。判定依据：网关对未登录 XHR 直接回 HTTP 401（非 302），
     * 故「能拿到合法 JSON 外壳（code=0）」即会话有效，不依赖 rows 是否非空。
     */
    suspend fun probeSession(): SessionProbe = try {
        val rows = rowsOf(
            post("$EHALL_APP/modules/jshkcb/dqxnxq.do", ""),
            "dqxnxq",
            EhallRows.serializer(DqxnxqRow.serializer()),
        )
        val code = rows.firstOrNull()?.termCode
        SessionProbe.LoggedIn(
            if (code.isNullOrBlank()) "接口已放行（学期列表为空）" else "当前学期 $code"
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        SessionProbe.NotLoggedIn(e.message ?: "会话无效")
    } catch (e: Exception) {
        SessionProbe.Failed(e.message ?: e::class.simpleName ?: "未知错误")
    }

    /** 只关心"能不能用"时的简写 */
    suspend fun hasSession(): Boolean = probeSession() is SessionProbe.LoggedIn

    // ---------------- SSO：借统一身份认证给 ehall 建会话 ----------------

    /**
     * 用 OkHttp 一跳一跳走完重定向链，每跳记录状态码 / Location / Set-Cookie 名字与属性。
     * 换票是服务端 302 链，WebView 只提交最后一跳、不触发中间回调，故必须自己走才能观察全过程；
     * 走完后沿途 Set-Cookie 经 [WebViewCookieJar] 落入 CookieManager——换票即建会话。
     * @param start 链起点；诊断用 [SSO_ENTRY]，登录时是 auth 回跳的带票 URL。
     * @param trace 是否将每跳写入日志。
     */
    suspend fun walkChain(start: String, trace: Boolean = true): List<String> =
        withContext(Dispatchers.IO) {
            // 关掉自动跟随：要看见每一跳，就必须自己一步一步走
            val walker = http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val steps = mutableListOf<String>()
            var url: String = start
            var hop = 0

            while (hop++ < MAX_SSO_HOPS) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()

                val resp = try {
                    walker.newCall(request).execute()
                } catch (e: Exception) {
                    steps += "#$hop 请求异常：${e.message}"
                    break
                }

                val next = resp.use { r ->
                    steps += "#$hop HTTP ${r.code} ${maskTicket(r.request.url.toString())}"
                    r.headers("Set-Cookie").forEach {
                        steps += "     set-cookie: ${maskCookie(it)}"
                    }

                    val loc = r.header("Location")
                    if (loc != null) {
                        steps += "     location: ${maskTicket(loc)}"
                    } else if (r.code >= 400) {
                        // 4xx/5xx 时把 body 前一段带上——是 ehall 的错误页还是网关/WAF 的，一看便知
                        val body = runCatching { r.peekBody(400).string() }.getOrDefault("")
                        steps += "     body: ${body.replace(Regex("\\s+"), " ")}"
                    }
                    loc?.let { r.request.url.resolve(it)?.toString() }
                }

                if (next == null) break
                url = next
            }

            if (trace) steps.forEach { DebugLog.i("SSO $it") }
            steps
        }

    /**
     * 将 CAS 票据递给 ehall 并走完——票据由 App 用 OkHttp 自递，不交 WebView。
     * 好处：每跳进日志；且不被 `shouldOverrideUrlLoading` 的 http→https 改写 service，
     * 避免票据绑定 http 却验票失配。
     */
    suspend fun redeemTicket(ticketUrl: String): List<String> {
        val steps = walkChain(ticketUrl, trace = true)
        val last = steps.lastOrNull() ?: ""
        DebugLog.i("票据递送结束：末跳为 ${last.take(120)}")
        return steps
    }

    /**
     * 去除 cookie 值，只留名字与属性。值是会话凭证不可入日志；
     * 判断问题（如 Secure cookie 落在 http 响应是否被丢弃）只需名字与属性。
     */
    private fun maskCookie(raw: String): String {
        val name = raw.substringBefore('=').trim()
        val attrs = raw.substringAfter(';', "").trim()
        return if (attrs.isEmpty()) "$name=<已隐藏>" else "$name=<已隐藏>; $attrs"
    }

    /** 票据是一次性凭证，同样不能进日志 */
    private fun maskTicket(url: String): String =
        url.replace(Regex("ticket=[^&]*"), "ticket=<已隐藏>")

    // ---------------- 内部 ----------------

    private suspend fun post(path: String, body: String): EhallEnvelope = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(EHALL_BASE + path)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", EHALL_BASE)
            .header("Referer", "$EHALL_BASE$EHALL_APP/*default/index.do")
            .post(body.toRequestBody(FORM))
            .build()

        http.newCall(request).execute().use { resp ->
            DebugLog.i("POST $path → HTTP ${resp.code}（${resp.headers("Set-Cookie").size} 个 Set-Cookie）")
            // 未登录时网关对带 X-Requested-With 的 AJAX 直接回 401+HTML（非 302）；
            // 状态码映射见 [httpError]，不要在此内联判断。
            if (!resp.isSuccessful) {
                // 把错误响应体也记下来：403 到底是谁发的（ehall 错误页 / 网关 / WAF），
                // 看 body 前几个字符就能分辨，不用再猜。
                val errBody = runCatching { resp.body?.string()?.take(600) }.getOrNull() ?: ""
                DebugLog.w(
                    "接口被拒：HTTP ${resp.code} $path " +
                        "body前600=${errBody.replace(Regex("\\s+"), " ")}"
                )
                throw httpError(resp.code, resp.message, path)
            }
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) throw TimetableException("空响应 <- $path")
            // 登录态失效时会被重定向到统一身份认证并回一段 HTML，
            // 而 OkHttp 默认跟随重定向，所以以"不是 JSON"来识别。
            if (!text.trimStart().startsWith("{")) {
                throw NotLoggedInException("返回的不是 JSON，登录态可能已失效（被重定向到统一身份认证）")
            }
            val env = EHALL_JSON.decodeFromString(EhallEnvelope.serializer(), text)
            if (env.code != "0") throw TimetableException("接口返回 code=${env.code} <- $path")
            env
        }
    }

    private fun <T> rowsOf(
        envelope: EhallEnvelope,
        key: String,
        deserializer: DeserializationStrategy<EhallRows<T>>,
    ): List<T> {
        val obj = envelope.datas[key] ?: return emptyList()
        return EHALL_JSON.decodeFromJsonElement(deserializer, obj).rows
    }
}

/** `"2026-2027-2"` → 年 `2026-2027`、学期 `2` */
internal data class TermCode(val year: String, val term: String) {
    companion object {
        fun parse(code: String): TermCode {
            val parts = code.split("-")
            if (parts.size < 3) throw TimetableException("学期代码格式不对：$code")
            return TermCode("${parts[0]}-${parts[1]}", parts[2])
        }
    }
}

/**
 * 会话探测结果。
 *
 * 三段而不是 Boolean 的理由见 [EhallClient.probeSession]。
 * UI 按它分流：LoggedIn → 放行；NotLoggedIn → 弹登录页；Failed → 提示重试。
 */
sealed interface SessionProbe {
    /** 网关放行了（HTTP 2xx 且 code=0），会话有效 */
    data class LoggedIn(val detail: String) : SessionProbe

    /** 明确是没登录 / 会话过期 */
    data class NotLoggedIn(val reason: String) : SessionProbe

    /** 其它错误：网络不通、5xx、解析失败等 */
    data class Failed(val reason: String) : SessionProbe
}
