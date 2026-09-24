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

/** 认证接口的回跳入口。真实页面登录成功后就是 `location.href = 这个 + "?redirectUrl=..."`。 */
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

@Serializable
private data class NeedCaptchaResponse(
    val code: Int = 0,
    val info: String? = null,
    val needStage2Validation: Boolean = false,
)

/**
 * 用账号密码走完 CAS 并拿到 TGT。它只解决「免手打账号密码」，不解决授权：
 * 拿票后仍须 WebView 走门户（ehall 的 `_WEU` 只认从门户点入的事务），勿指望纯 HTTP。
 *
 * 三个接口调用顺序不可改（实测改则必 500）：
 *   ① GET  /casback/needCaptcha  先问验证码，需要则勿白提交密码
 *   ② POST /casback/getChiperKey 取 RSA 公钥，同时 Set-Cookie CHIPER_UID（即「登陆态」）
 *   ③ POST /casback/casLogin     带上加密密码 + ② 留下的 cookie
 * `casLogin` 认 ② 的 CHIPER_UID；缺失则服务端回 500「登陆态已过期」。公钥每次全新、不许缓存，②③须紧挨。
 *
 * 密码加密复刻 JSEncrypt，标准 RSA/PKCS#1 v1.5：明文→RSA 密文→Base64 入 password。
 * 公钥为 X.509 SPKI 1024 位 RSA，Base64 用 URL-safe 变体，须归一化（+`/`、补`=`）再解码，否则 KeyFactory 抛异常。
 * `fingerPrint` 字段实测非必填，省略以减少可追踪信息。
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
                "maxAge=${parsed.maxAge} tgt字段=${if (parsed.tgtCookie.isNullOrBlank()) "无" else "有"}"
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
}
