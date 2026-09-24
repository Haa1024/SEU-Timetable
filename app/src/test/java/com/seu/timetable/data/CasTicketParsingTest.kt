package com.seu.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换票成败判据的回归测试。
 *
 * 这里钉的是整个纯 HTTP 链上**最容易静默出错的一处**：`verifyTgt` 的成功/失败
 * 完全由「服务端给的 redirectUrl 长什么样」决定，而失败时的响应长得**极像成功**——
 * 一样是 200、一样有 `ticket=` 参数，只是值是 `Unauthorized Service` 而已。
 *
 * 判错两个方向的代价不对称，但都很贵：
 *  - 漏判成功 → 后续接口全 403，现象像网络问题，排查成本极高；
 *  - 误判成功 → 明明能登却反复退回 WebView，用户看到「登录失败」。
 */
class CasTicketParsingTest {

    /** 真实成功响应的 redirectUrl 形态（实测，2026-09）。注意是 `http://`，不是 https。 */
    @Test
    fun `带 ST 的 ehall 回跳算有票`() {
        val url = "http://ehall.seu.edu.cn/jwapp/sys/wdkb/index.do?" +
            "EMAP_LANG=zh&THEME=&ticket=ST-1234567-abcdefghijk-cas"
        assertTrue(looksLikeTicketUrl(url))
    }

    /** 网关 JWT 通道用 `t` 而非 `ticket`，同样要认——否则网关那条链会被判成没票。 */
    @Test
    fun `网关 JWT 用 t 参数也算有票`() {
        assertTrue(looksLikeTicketUrl("https://vpn.seu.edu.cn/callback?t=eyJhbGciOiJIUzI1NiJ9"))
    }

    /**
     * 本条是这组测试存在的**主要理由**：换票被拒时服务端就是这么答的。
     * 名字在、值也在，只有内容是错误文案——不专门拦一下，必然误判成功。
     */
    @Test
    fun `ticket 的值是 Unauthorized Service 时必须判为无票`() {
        val url = "http://ehall.seu.edu.cn/jwapp/index.do?ticket=Unauthorized Service"
        assertFalse(looksLikeTicketUrl(url))
    }

    @Test
    fun `ticket 的值是 Forbidden 时同样判为无票`() {
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x?ticket=Forbidden"))
    }

    /** 空值的 `ticket=` 不算票。服务端在「没登录」时会给这种。 */
    @Test
    fun `ticket 参数存在但为空不算有票`() {
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x?ticket="))
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x?ticket=   "))
    }

    @Test
    fun `没有查询串时不算有票`() {
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x"))
        assertFalse(looksLikeTicketUrl(""))
    }

    /** 参数名必须完全匹配：`ticketId`、`xticket` 之类的近似名不能算。 */
    @Test
    fun `近似的参数名不算票`() {
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x?ticketId=ST-123"))
        assertFalse(looksLikeTicketUrl("http://ehall.seu.edu.cn/x?myTicket=ST-123"))
    }

    /** 票通常排在后面，前面那些参数不能被误当成票名。 */
    @Test
    fun `票据跟在其它参数之后仍能认出来`() {
        assertTrue(looksLikeTicketUrl("http://a.b/c?a=1&b=2&ticket=ST-9-x-cas&d=4"))
    }

    /**
     * `service` 的转义规矩：只动 `?` 与 `&`，其余逐字符保留。
     *
     * 这个规则看着琐碎，但它决定了票是发给「课表微应用」还是发给「一个不存在的服务」：
     * CAS 是拿 service 字符串**逐字符**和注册值比的。把 `*` 编成 `%2A`、
     * 或把 `http` 改成 `https`，都会得到 `ticket=Unauthorized Service`。
     */
    @Test
    fun `service 转义只碰问号与和号`() {
        val service = "http://ehall.seu.edu.cn/jwapp/sys/wdkb/*default/index.do?EMAP_LANG=zh&THEME="
        val escaped = escapeServiceForQuery(service)

        assertEquals(
            "http://ehall.seu.edu.cn/jwapp/sys/wdkb/*default/index.do%3FEMAP_LANG=zh%26THEME=",
            escaped,
        )
        // 关键：星号与冒号保持字面量
        assertTrue(escaped.contains("*default"))
        assertTrue(escaped.startsWith("http://"))
        assertFalse(escaped.contains("%2A"))
        assertFalse(escaped.contains("%3A"))
    }

    @Test
    fun `service 里没有特殊字符时原样返回`() {
        val plain = "https://vpn.seu.edu.cn:443/passport/v1/auth/cas"
        assertEquals(plain, escapeServiceForQuery(plain))
    }
}
