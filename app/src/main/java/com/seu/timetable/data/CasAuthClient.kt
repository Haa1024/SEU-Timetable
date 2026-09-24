package com.seu.timetable.data

import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import android.util.Base64

/**
 * 统一身份认证的服务端接口基址，与门户 [LOGIN_ENTRY_URL] 不同。
 * 此为认证 SPA 自调的 API（`auth.seu.edu.cn/auth/casback/`），取出直用以替用户填账号密码。
 */
internal const val AUTH_BASE = "https://auth.seu.edu.cn"
internal const val AUTH_API = "$AUTH_BASE/auth"

/**
 * **真正的服务端换票端点**：`POST /auth/casback/verifyTgt`。
 *
 * 请求体只有 `{"service": "<目标>"}` —— 没有 `loginType`、没有 `agentId`（实测多传无用）。
 * 身份由**服务端自己从请求 cookie 里的 TGT 读出**，我们不必也不该把票塞进 body。
 *
 * 响应（实测，2026-09 HAR + 三次复现）：
 * ```
 * 201 {"code":201,"info":"CasLoginByCookieRequest Success","success":true,
 *      "stCookie":null,"redirectUrl":"<带 ticket=ST-... 的目标地址>"}
 * 400 {"code":400,"info":"user not login","stCookie":null,"redirectUrl":null}
 * ```
 * 同时服务端会 `Set-Cookie: TGT=<JWT>`（656/686 字符）刷新票据寿命。
 *
 * ## 曾经走错的那条路，留作路标
 *
 * 一度以为换票端点是 `GET /auth/casapi/login?service=`（auth 前端 SPA 里出现过这个地址）。
 * 实测该路径**根本不存在**：`casapi/login`、`casLoginByCookie`、`verifyTgtCookie`、
 * `loginByCookie` 全部返回 Spring 的应用级 404（带 gzip/Vary 头，GET/POST 都一样）——
 * 即前端发布版本超前于后端。整条路线作废，改走本端点。
 *
 * 另：`/auth/casback/loginRedirect` 也**不换票**，它只是把 `redirectUrl` 参数原样 302 回吐。
 * 但它在这条链里仍有用——见 [AUTH_LOGIN_REDIRECT]。
 */
internal const val AUTH_VERIFY_TGT = "$AUTH_API/casback/verifyTgt"

/**
 * 纯跳转器：把 `redirectUrl` 参数原样 302 回吐，**不附任何票据**。
 *
 * 它的价值不在自己——而在于「用它跳一次、落到网关域、从而拿到网关的回调地址」。
 * 网关那条 CAS 链就是这么走的（见 [GatewayAuthClient]）：
 * 拿 casLogin 下发的 `redirectUrl` 喂给它 → 302 到网关 → 网关再 302 到 auth SPA，
 * 此时 SPA 地址里那个 `service` 才是**网关的回调地址**，拿它去 [AUTH_VERIFY_TGT] 换票。
 */
internal const val AUTH_LOGIN_REDIRECT = "$AUTH_API/casback/loginRedirect"

private val JSON_BODY = "application/json; charset=UTF-8".toMediaType()

private val CAS_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** 一次自动登录的结果。**刻意做成多分支**：不同分支要让 UI 说不同的话、做不同的事。 */
sealed interface CasLoginResult {

    /**
     * 认证通过，票据已下发（TGT 由 CookieJar 落入 CookieManager），可去开门户。
     */
    data class Success(
        val redirectUrl: String?,
        val maxAge: Long,
        val tgtInStore: Boolean,
    ) : CasLoginResult

    /** `402 用户名或密码错误` —— 存下来的凭据不对。**不要重试**，直接请用户重输。 */
    data object BadCredentials : CasLoginResult

    /** `4000 未填写验证码` —— 学校这次要验证码，程序给不出来，只能转人工。 */
    data object CaptchaRequired : CasLoginResult

    /** `500 登陆态已过期` —— 说明 `getChiperKey` 拿到的 `CHIPER_UID` 没带上，是实现的锅。 */
    data object SessionNotEstablished : CasLoginResult

    /** 网络、解析、服务端 5xx 等 */
    data class Failed(val reason: String) : CasLoginResult
}

/**
 * 「用已有 TGT 换一张指向某 service 的票」的结果。见 [CasAuthClient.verifyTgt]。
 *
 * 与 [CasLoginResult] 分开：那条路要密码、会碰风控，分支自然多；
 * 这条路只依赖手上有没有有效 TGT，只有成/无票/异常三种。
 */
sealed interface ServiceTicket {

    /** CAS 已受理，给出带票的回跳地址（本 App 自行递送，不交 WebView） */
    data class Ok(val redirectUrl: String) : ServiceTicket

    /** 手上没有有效 TGT（`code=400 user not login`），须重新走 [CasAuthClient.login] */
    data object NoSession : ServiceTicket

    /** 网络异常或 CAS 给出的响应不符合预期 */
    data class Failed(val reason: String) : ServiceTicket
}

@Serializable
private data class CasLoginResponse(
    val code: Int = 0,
    val info: String? = null,
    val success: Boolean = false,
    val redirectUrl: String? = null,
    val tgtCookie: String? = null,
    val maxAge: Long = 0,
)

@Serializable
private data class ChiperKeyResponse(
    val code: Int = 0,
    val success: Boolean = false,
    val publicKey: String? = null,
    val info: String? = null,
)

/**
 * `POST /casback/verifyTgt` 的响应。`stCookie` 实测恒为 null，这里不接——
 * 票据只在 `redirectUrl` 里，别去别处找。
 */
@Serializable
private data class ServiceTicketResponse(
    val code: Int = 0,
    val info: String? = null,
    val success: Boolean = false,
    val redirectUrl: String? = null,
)

@Serializable
private data class NeedCaptchaResponse(
    val code: Int = 0,
    val info: String? = null,
    val needStage2Validation: Boolean = false,
)

/**
 * 用账号密码走完 CAS 并拿到 TGT；再用 TGT 向任意 service 换票。
 *
 * ## 与「过网关」的分工
 *
 * 本类只解决「拿 TGT」与「用 TGT 换票」，不解决「过零信任网关」——那是 [GatewayAuthClient] 的事。
 *
 * ## 换票的正确姿势（2026-09 双重实证钉定）
 *
 * `casLogin` 成功后的响应 JSON 里会**直接下发**一个带 ST 的回跳地址，形如
 * `http://ehall.seu.edu.cn/jwapp/sys/wdkb/<星号>default/index.do?...&ticket=ST-...`
 * （路径里的 `<星号>` 就是课表微应用的 `*`，此处改用文字描述以免触发嵌套块注释）。
 * **但别拿它当终点**：那是「以登录时传的 service 为目标」换出的一张票，一次性、
 * 且目标已定死。需要在链中途另换一张指向**别的** service 的票时，必须重新换——
 * 用 [verifyTgt]。
 *
 * 曾以为换票端点是 `GET /auth/casapi/login?service=`，实测该路径 404（见 [AUTH_VERIFY_TGT]
 * 的注释）。真正的端点是 `POST /auth/casback/verifyTgt`：body 只给 `service`，
 * 身份由服务端从 cookie 里的 TGT 自己读，响应 JSON 的 `redirectUrl` 即带票地址。
 *
 * ## 三个接口的调用顺序不可改（实测改则必 500）
 *
 *   ① GET  /casback/needCaptcha  先问验证码，需要则勿白提交密码
 *   ② POST /casback/getChiperKey 取 RSA 公钥，同时 Set-Cookie CHIPER_UID（即「登陆态」）
 *   ③ POST /casback/casLogin     带上加密密码 + ② 留下的 cookie
 * `casLogin` 认 ② 的 CHIPER_UID；缺失则服务端回 500「登陆态已过期」。公钥每次全新、不许缓存，②③须紧挨。
 *
 * 密码加密复刻 JSEncrypt，标准 RSA/PKCS#1 v1.5：明文→RSA 密文→Base64 入 password。
 * 公钥为 X.509 SPKI 1024 位 RSA，Base64 用 URL-safe 变体，须归一化（+`/`、补`=`）再解码，否则 KeyFactory 抛异常。
 * `fingerPrint` 字段实测非必填，省略以减少可追踪信息。
 *
 * ## service 参数的转义规矩（容易踩）
 *
 * `service` 的值要**逐字符**符合 CAS 的注册值，故：
 *   - 只转义会破坏 query 结构的 `?` 与 `&`；
 *   - `*`、`:` 必须保持字面量（`*` 编成 `%2A` 就变成「另一个服务」了）；
 *   - `service` 必须写成 `http://ehall.seu.edu.cn/...`，**不能写成带 `:443` 的 https 形式**——
 *     后者未在 CAS 注册，换票会回 `ticket=Unauthorized Service`。
 */
class CasAuthClient(
    private val jar: MirroringCookieJar = MirroringCookieJar(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * 探测用 URL。Cookie 按 path 匹配，须问接口地址才得真正会带上的那批；
     * 用 `toHttpUrl()` 而非 `HttpUrl.get()`（后者 Kotlin 侧解析不到）。
     */
    private val authUrl: HttpUrl = "$AUTH_BASE/".toHttpUrl()

    /** 登录并落票。
     * @param service 换票目标服务，默认课表微应用（[CAS_SERVICE] 服务端必然认）。
     * @param rememberMe 建议恒为 true：拉长 TGT 寿命，静默续期依赖其存活。 */
    suspend fun login(
        username: String,
        password: String,
        service: String = CAS_SERVICE,
        rememberMe: Boolean = true,
    ): CasLoginResult = withContext(Dispatchers.IO) {
        // 逻辑放在具名函数而非 lambda 主体：lambda 返回类型靠末表达式反推，
        // 叠加多处 return@withContext 会让类型推断极脆，显式返回类型一次钉死。
        loginInternal(username.trim(), password, service, rememberMe)
    }

    private fun loginInternal(
        user: String,
        password: String,
        service: String,
        rememberMe: Boolean,
    ): CasLoginResult {
        if (user.isEmpty() || password.isEmpty()) {
            return CasLoginResult.Failed("账号或密码为空")
        }

        // ① 先问验证码，需要则勿提交——失败提交会触发风控，且自动重试能力有限。
        when (needCaptcha()) {
            NeedCaptcha.YES -> {
                DebugLog.w("自动登录：服务端要求验证码 → 直接转人工")
                return CasLoginResult.CaptchaRequired
            }
            NeedCaptcha.UNKNOWN -> DebugLog.w("自动登录：needCaptcha 没问出来，继续尝试提交")
            NeedCaptcha.NO -> Unit
        }

        // ② 取公钥（顺带拿到 CHIPER_UID，见类注释）
        val publicKey = fetchPublicKey()
        if (publicKey == null) return CasLoginResult.Failed("拿不到加密公钥")

        val cipherText = try {
            rsaEncrypt(password, publicKey)
        } catch (e: Exception) {
            return CasLoginResult.Failed("密码加密失败：${e.message}")
        }

        // ③ 提交
        val body = buildString {
            append("{\"service\":").append(quote(service))
            append(",\"username\":").append(quote(user))
            append(",\"password\":").append(quote(cipherText))
            append(",\"captcha\":\"\"")
            append(",\"rememberMe\":").append(rememberMe)
            append(",\"loginType\":\"account\"")
            append(",\"wxcode\":null,\"wxBinded\":false,\"agentId\":null")
            append(",\"mobilePhoneNum\":\"\",\"mobileVerifyCode\":\"\"}")
        }

        val resp = post("$AUTH_API/casback/casLogin", body)
        if (resp == null) return CasLoginResult.Failed("登录请求发不出去")

        val parsed = runCatching {
            CAS_JSON.decodeFromString(CasLoginResponse.serializer(), resp.body)
        }.getOrNull()
        if (parsed == null) {
            return CasLoginResult.Failed("登录响应看不懂：${resp.body.take(120)}")
        }

        DebugLog.i(
            "casLogin → code=${parsed.code} info=${parsed.info} " +
                "maxAge=${parsed.maxAge} tgt字段=${if (parsed.tgtCookie.isNullOrBlank()) "无" else "有"} " +
                "回跳=${if (parsed.redirectUrl.isNullOrBlank()) "无" else "已给出"}"
        )

        return when (parsed.code) {
            200 -> {
                // 票是否真的落进 CookieManager，直接问一遍——别假设。
                val inStore = hasTgtInStore()
                if (inStore) {
                    DebugLog.i("TGT 已落入 CookieManager（auth 域），可以开门户了")
                } else {
                    DebugLog.w(
                        "casLogin 成功但 CookieManager 里没看到 TGT → " +
                            "后续很可能退回登录页；本次 Set-Cookie 名字：${resp.setCookieNames}"
                    )
                }
                CasLoginResult.Success(parsed.redirectUrl, parsed.maxAge, inStore)
            }
            402 -> CasLoginResult.BadCredentials
            4000 -> CasLoginResult.CaptchaRequired
            500 -> if (parsed.info?.contains("登陆态已过期") == true) {
                CasLoginResult.SessionNotEstablished
            } else {
                CasLoginResult.Failed(parsed.info ?: "HTTP 500")
            }
            else -> CasLoginResult.Failed("code=${parsed.code} ${parsed.info.orEmpty()}")
        }
    }

    /** 认证域里是否已经有 TGT（"这个人已通过认证"的凭证）。只看名字，不看值。 */
    fun hasTgtInStore(): Boolean = runCatching {
        jar.loadForRequest(authUrl).any { it.name == "TGT" && it.value.isNotBlank() }
    }.getOrDefault(false)

    /**
     * 用**已有的 TGT** 换一张指向 [service] 的票——不接触账号密码。
     *
     * 实测（HAR + 三次复现）：`POST /auth/casback/verifyTgt`，body 只有 `{"service": …}`。
     * 成功回 `code=201` + `redirectUrl`（带 `ticket=ST-...`），失败回 `code=400 user not login`。
     *
     * ## 拿到 `redirectUrl` 之后还要做一件事
     *
     * 服务端给的 `redirectUrl` 里**常常是 `http://` 开头**（`http://ehall.seu.edu.cn/...`），
     * 而 ehall 现在只认 https。直接请求 http 会被网关拦到一个带
     * `location.replace("https://...")` 的 JS 跳转页，**query 全部丢失、票就没了**。
     * 故调用方必须把 scheme 手动换成 https 再递（见 [GatewayAuthClient] 里的注释）。
     *
     * @return [ServiceTicket.Ok] 表示拿到了**确实带票**的回跳地址；
     *   [ServiceTicket.NoSession] 表示手上没有有效 TGT（须重新 [login] 或转人工）。
     */
    suspend fun verifyTgt(service: String): ServiceTicket = withContext(Dispatchers.IO) {
        if (!hasTgtInStore()) {
            DebugLog.w("verifyTgt：auth 域没有 TGT → 无法换票")
            return@withContext ServiceTicket.NoSession
        }

        val body = "{\"service\":${quote(service)}}"
        val req = Request.Builder()
            .url(AUTH_VERIFY_TGT)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Origin", AUTH_BASE)
            .header("Referer", "$AUTH_BASE/dist/")
            .post(body.toRequestBody(JSON_BODY))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val parsed = runCatching {
                    CAS_JSON.decodeFromString(ServiceTicketResponse.serializer(), text)
                }.getOrNull()

                DebugLog.i(
                    "verifyTgt → HTTP ${r.code} code=${parsed?.code} info=${parsed?.info}"
                )

                if (parsed == null) {
                    return@use ServiceTicket.Failed("换票响应看不懂：${text.take(120)}")
                }

                val url = parsed.redirectUrl
                when {
                    // 201 + success + 带票的 redirectUrl：唯一判据，缺一不可
                    parsed.success && !url.isNullOrBlank() && hasTicketParam(url) ->
                        ServiceTicket.Ok(url)

                    // 400 / user not login：手上那张 TGT 服务端不认了
                    parsed.code == 400 || parsed.info?.contains("not login") == true -> {
                        DebugLog.w("verifyTgt：TGT 已失效（${parsed.info}）")
                        ServiceTicket.NoSession
                    }

                    // 最隐蔽的失败：200 但 ticket=Unauthorized Service。
                    // 这不是「没登录」，而是「service 字符串跟 CAS 注册值对不上」，
                    // 重登一百次也没用，必须去查 service 的拼写/转义。
                    url?.contains("ticket=Unauthorized") == true -> {
                        DebugLog.w("verifyTgt：service 未在 CAS 注册 → ${maskTicket(url)}")
                        ServiceTicket.Failed("service 未在 CAS 注册（不是登录态问题）")
                    }

                    else -> ServiceTicket.Failed(
                        "换票未成功（code=${parsed.code} ${parsed.info.orEmpty()}）"
                    )
                }
            }
        } catch (e: Exception) {
            DebugLog.w("verifyTgt 异常：${e.message}")
            ServiceTicket.Failed(e.message ?: "网络异常")
        }
    }

    /**
     * 地址里是否**真的附了票据**。
     *
     * CAS 的票参数名是 `ticket`；网关自身那条 JWT 通道用的是 `t`。两者都认，
     * 但**必须有非空值**——`verifyTgt` 的失败分支会给回一个
     * `...&ticket=Unauthorized Service`，那种「名字在、值是错误提示」的不算。
     */
    private fun hasTicketParam(loc: String): Boolean = looksLikeTicketUrl(loc)

    /** 票据是一次性凭证，不能进日志 */
    private fun maskTicket(url: String): String =
        url.replace(Regex("ticket=[^&]*"), "ticket=<已隐藏>")

    // ---------------------------------------------------------------- 内部

    private enum class NeedCaptcha { YES, NO, UNKNOWN }

    private fun needCaptcha(): NeedCaptcha = try {
        val resp = get("$AUTH_API/casback/needCaptcha") ?: return NeedCaptcha.UNKNOWN
        val parsed = CAS_JSON.decodeFromString(NeedCaptchaResponse.serializer(), resp)
        // 200 = 不需要；4000 = 需要（与 casLogin 的错误码同一套语义）
        when (parsed.code) {
            200 -> NeedCaptcha.NO
            4000 -> NeedCaptcha.YES
            else -> NeedCaptcha.UNKNOWN
        }
    } catch (e: Exception) {
        DebugLog.w("needCaptcha 探测异常：${e.message}")
        NeedCaptcha.UNKNOWN
    }

    private fun fetchPublicKey(): String? {
        val resp = post("$AUTH_API/casback/getChiperKey", "{}") ?: return null
        val parsed = runCatching {
            CAS_JSON.decodeFromString(ChiperKeyResponse.serializer(), resp.body)
        }.getOrNull()
        if (parsed == null || !parsed.success || parsed.publicKey.isNullOrBlank()) {
            DebugLog.w("getChiperKey 异常：${resp.body.take(160)}")
            return null
        }
        return parsed.publicKey
    }

    /**
     * RSA/PKCS#1 v1.5 加密，输出标准 Base64。
     * 公钥为 URL-safe Base64，解码前须换回 +/`/` 并补 `=`；填充须 PKCS1Padding（v1.5），勿用 OAEP。
     */
    private fun rsaEncrypt(plain: String, publicKeyB64: String): String {
        val der = Base64.decode(b64UrlToStandard(publicKeyB64), Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** URL-safe Base64 → 标准 Base64。少了这一步，KeyFactory 会直接抛 InvalidKeySpecException。 */
    private fun b64UrlToStandard(s: String): String {
        val swapped = s.replace('-', '+').replace('_', '/')
        val pad = (4 - swapped.length % 4) % 4
        return swapped + "=".repeat(pad)
    }

    /** 极简 JSON 字符串转义。报文里只有 service / username 是我们拼的，够用。 */
    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private class Reply(val body: String, val setCookieNames: String)

    private fun post(url: String, body: String): Reply? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Origin", AUTH_BASE)
            .header("Referer", "$AUTH_BASE/dist/")
            .post(body.toRequestBody(JSON_BODY))
            .build()
        http.newCall(req).execute().use { r ->
            val names = r.headers("Set-Cookie")
                .joinToString(",") { it.substringBefore('=').trim() }   // 只留名字，值就是凭证
            DebugLog.i("POST ${url.removePrefix(AUTH_BASE)} → HTTP ${r.code} set-cookie=[$names]")
            Reply(r.body?.string().orEmpty(), names)
        }
    } catch (e: Exception) {
        DebugLog.w("POST $url 异常：${e.message}")
        null
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$AUTH_BASE/dist/")
            .get()
            .build()
        http.newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) {
        DebugLog.w("GET $url 异常：${e.message}")
        null
    }
}

/**
 * CookieJar：内存存一份，并尽力镜像给 WebView 的 CookieManager。
 * 不能只用 [WebViewCookieJar]：① 可靠性——CHIPER_UID 须活到 casLogin，
 *   CookieManager 可能因域/路径取舍或 WebView 未初始化而拿不到，内存存一份则链内不丢；
 *   ② 可观测——留 [hasTgt] 可查 TGT 是否落入（CookieManager 只给拼接串）。
 * 镜像为顺带收益：TGT 入 CookieManager 后 WebView 开门户自带登录态。
 */
class MirroringCookieJar(
    private val webViewJar: CookieJar = WebViewCookieJar(),
) : CookieJar {

    private val memory = linkedMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(memory) {
            cookies.forEach { cookie ->
                val list = memory.getOrPut(cookie.domain) { mutableListOf() }
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list += cookie
            }
        }
        // 镜像失败不算错：内存那份才是本流程的权威
        runCatching { webViewJar.saveFromResponse(url, cookies) }
            .onFailure { DebugLog.w("cookie 镜像到 CookieManager 失败：${it.message}") }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val merged = linkedMapOf<String, Cookie>()
        // 先放 CookieManager 的（可能含用户上次登录留下的），再让内存的覆盖同名同路径
        runCatching { webViewJar.loadForRequest(url) }.getOrNull()
            ?.forEach { merged["${it.name}|${it.path}"] = it }
        synchronized(memory) {
            memory.values.flatten().filter { it.matches(url) }
                .forEach { merged["${it.name}|${it.path}"] = it }
        }
        return merged.values.toList()
    }

    /**
     * 从内存账本里抹掉若干个 cookie（按名字+域+路径精确匹配）。
     *
     * 用于「cookie 本身要把请求做坏」的场景——此时只清 `CookieManager` 不够：
     * 内存那份仍在，本客户端下一次请求照样带上。已在 [GatewayAuthClient] 清
     * `sdp_app_session` 时踩到（见那里的注释）。
     *
     * 只动内存，不动 `CookieManager`：调用方通常另有一套删 `CookieManager` 的逻辑，
     * 且那边的路径推导规则（见 `EHALL_COOKIE_PATH_CANDIDATES`）跟这里不同，混在一起会互相打架。
     */
    fun forget(cookies: List<Cookie>) {
        synchronized(memory) {
            cookies.forEach { victim ->
                memory[victim.domain]?.removeAll {
                    it.name == victim.name && it.path == victim.path
                }
            }
        }
    }
}

/**
 * 这个地址看起来「真的带了票」吗？
 *
 * 抽成顶层纯函数是为了可测——这条判断是 [CasAuthClient.verifyTgt] 的**唯一成功判据**，
 * 判错的后果两极：把「没票」当成功，会让整条链在后续被静默拒绝（现象像网络问题）；
 * 把「有票」当失败，则会在明明能登的时候反复退回 WebView。
 *
 * 三个必须同时成立的条件的：
 *  ① 参数名是 `ticket`（CAS 的票）或 `t`（网关 JWT 通道）；
 *  ② 值非空；
 *  ③ 值不是错误提示。第 ③ 条最反直觉但最要紧：换票被拒时服务端回的是
 *     `...&ticket=Unauthorized Service` —— **名字在、值也在**，只有内容是错误文案。
 *     若漏了这一条，一次失败的换票会被判成成功。
 */
internal fun looksLikeTicketUrl(loc: String): Boolean {
    val query = loc.substringAfter('?', "")
    if (query.isEmpty()) return false
    return query.split('&').any { pair ->
        val k = pair.substringBefore('=')
        val v = pair.substringAfter('=', "").trim()
        (k == "ticket" || k == "t") &&
            v.isNotBlank() &&
            !v.startsWith("Unauthorized") &&
            !v.startsWith("Forbidden")
    }
}
