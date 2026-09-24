package com.seu.timetable.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MirroringCookieJar] 的回归测试。
 *
 * 这个类是整条纯 HTTP 链的**cookie 中枢**，它出错的形态极难察觉：
 * 请求照发、响应照回，只是票没跟上去——外表跟「服务端不认票」一模一样。
 * 真机上为此耗过一整轮排查，故把两条关键行为钉住。
 *
 * 用注入的假 `webViewJar` 替代 `WebViewCookieJar`（后者依赖 Android `CookieManager`，
 * 单测跑不了）。构造参数支持注入正是为此。
 */
class MirroringCookieJarTest {

    /** 假的「WebView 侧」：只记不读，用来验证内存账本自己的行为。 */
    private class FakeJar : CookieJar {
        val saved = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            saved += cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private val authUrl = "https://auth.seu.edu.cn/auth/casback/verifyTgt".toHttpUrl()
    private val ehallUrl =
        "https://ehall.seu.edu.cn/jwapp/sys/wdkb/*default/modules/jshkcb/dqxnxq.do".toHttpUrl()

    private fun cookie(name: String, value: String, path: String = "/"): Cookie =
        Cookie.Builder()
            .name(name).value(value).domain("auth.seu.edu.cn").path(path)
            .build()

    private fun ehallCookie(name: String, value: String, path: String): Cookie =
        Cookie.Builder()
            .name(name).value(value).domain("ehall.seu.edu.cn").path(path)
            .build()

    /**
     * 同一客户端里落的 cookie，同一客户端要能取回。
     *
     * 这是「③ 换到的 TGT 必须能在 ⑤ 发出去」的最小前提。
     * 真机上 ③ 与 ⑤ 之间曾因为「换票用 A 客户端、跟票用 B 客户端」而断掉，
     * 症状是网关回一个不带解释的 400。
     */
    @Test
    fun `落库的 cookie 能被同一 jar 取回`() {
        val jar = MirroringCookieJar(FakeJar())
        val tgt = cookie("TGT", "jwt-value", path = "/auth/casback")

        jar.saveFromResponse(authUrl, listOf(tgt))

        val got = jar.loadForRequest(authUrl)
        assertEquals(1, got.size)
        assertEquals("TGT", got.first().name)
    }

    /**
     * [MirroringCookieJar.forget] 必须把内存那份也抹掉。
     *
     * 只清 `CookieManager` 是不够的：内存那份仍在，本客户端下一次请求照样带上。
     * 清洗 ehall 域 `sdp_app_session` 时就是靠这个方法才真正生效的。
     */
    @Test
    fun `forget 之后内存里不再取出该 cookie`() {
        val jar = MirroringCookieJar(FakeJar())
        val sdp = ehallCookie("sdp_app_session-443", "x", path = "/jwapp/sys/wdkb/*default")
        jar.saveFromResponse(ehallUrl, listOf(sdp))
        assertEquals(1, jar.loadForRequest(ehallUrl).size)

        jar.forget(listOf(sdp))

        assertTrue(jar.loadForRequest(ehallUrl).isEmpty())
    }

    /**
     * `forget` 按「名字 + 路径」精确匹配，不能误伤同名但不同路径的 cookie。
     *
     * 这条要紧：`sdp_app_session-443` 在 ehall 域下可能同时存在多个路径的副本，
     * 一刀切按名字删会把仍需要的那份也带走。
     */
    @Test
    fun `forget 只删同名同路径那一份`() {
        val jar = MirroringCookieJar(FakeJar())
        val atAppPath = ehallCookie("sdp_app_session-443", "a", path = "/jwapp/sys/wdkb/*default")
        val atJwapp = ehallCookie("sdp_app_session-443", "b", path = "/jwapp/")
        jar.saveFromResponse(ehallUrl, listOf(atAppPath, atJwapp))

        jar.forget(listOf(atAppPath))

        val left = jar.loadForRequest(ehallUrl)
        assertEquals(1, left.size)
        assertEquals("/jwapp/", left.first().path)
    }

    /** 落库时同名同路径要覆盖，不能堆成多份——否则请求上会带出重复的 cookie。 */
    @Test
    fun `同名同路径的新值覆盖旧值`() {
        val jar = MirroringCookieJar(FakeJar())
        jar.saveFromResponse(authUrl, listOf(cookie("TGT", "old", path = "/auth")))
        jar.saveFromResponse(authUrl, listOf(cookie("TGT", "new", path = "/auth")))

        val got = jar.loadForRequest(authUrl)
        assertEquals(1, got.size)
        assertEquals("new", got.first().value)
    }
}
