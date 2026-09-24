package com.seu.timetable.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 状态码 → 异常 的映射回归测试。
 *
 * ★ 这个测试来自一次**真实的线上 bug**：未登录时 ehall 网关对 XHR 请求返回 401
 *   （不是 302 跳登录页，这一点是实测出来的），而当时的代码把 401 当普通网络错误，
 *   于是界面停在「同步失败」而不去引导登录；偏偏那个页面上唯一的出口
 *   「重新登录」又漏了 clickable。两个问题叠加 = 用户完全卡死。
 *
 *   所以这里把映射抽成纯函数 [httpError] 并用测试钉住：
 *   **401/403 必须判为「未登录」**，任何把这条改掉的重构都应该让测试变红。
 */
class EhallClientTest {

    @Test
    fun `401 与 403 必须判为未登录`() {
        listOf(401, 403).forEach { code ->
            val e = httpError(code, "Unauthorized", "/jwapp/sys/wdkb/modules/xskcb/xskcb.do")
            assertTrue(
                "HTTP $code 应当是 NotLoggedInException，实际是 ${e::class.simpleName}",
                e is NotLoggedInException,
            )
        }
    }

    @Test
    fun `其它错误码不该被误判成未登录`() {
        listOf(400, 404, 429, 500, 502, 503).forEach { code ->
            val e = httpError(code, "boom", "/x")
            assertFalse("HTTP $code 不该判成未登录", e is NotLoggedInException)
            assertTrue("错误信息里应带上状态码", e.message.orEmpty().contains(code.toString()))
        }
    }

    @Test
    fun `未登录异常是课表异常的子类，UI 才能只 catch 一个基类做分流`() {
        val e = httpError(401, "Unauthorized", "/x")
        assertTrue(e is TimetableException)
    }
}
