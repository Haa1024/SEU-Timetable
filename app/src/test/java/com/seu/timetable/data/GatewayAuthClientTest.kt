package com.seu.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网关 CAS 回调地址（`service` 参数）的解析回归测试。
 *
 * 这一处之所以值得钉住：`service` 的值**本身带 `?` 和 `=`**
 * （`https://vpn.seu.edu.cn:443/passport/v1/auth/cas?sfDomain=CAS-auth`），
 * 在 `Location` 里是已 URL 编码后的一整段。若用字符串切分去取，稍有不慎就会
 * 截成前半截，于是 CAS 把票发给「另一个服务」，验票失败——而失败现象与网络不通
 * 几乎一样，很难从日志看出，故用测试把正确行为固定下来。
 */
class GatewayAuthClientTest {

    @Test
    fun `从 location 里还原出已编码的 service 原值`() {
        // 实测抓到的真实 Location：service 挂在 `#` 之后的片段里（CAS 是 hash 路由 SPA）
        val base = "https://vpn.seu.edu.cn/passport/v1/public/casLogin?sfDomain=CAS-auth"
        val loc =
            "https://auth.seu.edu.cn/dist/#/dist/main/login?service=" +
                "https%3A%2F%2Fvpn.seu.edu.cn%3A443%2Fpassport%2Fv1%2Fauth%2Fcas%3FsfDomain%3DCAS-auth"

        val service = extractService(base, loc)

        assertEquals(
            "https://vpn.seu.edu.cn:443/passport/v1/auth/cas?sfDomain=CAS-auth",
            service,
        )
    }

    @Test
    fun `普通查询串里的 service 同样能取到`() {
        val base = "https://vpn.seu.edu.cn/x"
        val loc = "https://auth.seu.edu.cn/login?service=" +
            "https%3A%2F%2Fvpn.seu.edu.cn%3A443%2Fpassport%2Fv1%2Fauth%2Fcas"

        assertEquals("https://vpn.seu.edu.cn:443/passport/v1/auth/cas", extractService(base, loc))
    }

    @Test
    fun `service 里自身的查询参数不能被截断`() {
        val base = "https://vpn.seu.edu.cn/passport/v1/public/casLogin?sfDomain=CAS-auth"
        val loc = "https://auth.seu.edu.cn/x?service=" +
            "https%3A%2F%2Fvpn.seu.edu.cn%3A443%2Fpassport%2Fv1%2Fauth%2Fcas%3FsfDomain%3DCAS-auth"

        val service = extractService(base, loc).orEmpty()

        // 若实现是在第一个 ? 处截断，这里就会丢掉 sfDomain=CAS-auth
        assertEquals(true, service.contains("sfDomain=CAS-auth"))
    }

    @Test
    fun `相对 location 按 base 解析`() {
        val base = "https://vpn.seu.edu.cn/passport/v1/public/casLogin"
        val service = extractService(base, "/dist/main/login?service=https%3A%2F%2Fa.example%2Fx")
        assertEquals("https://a.example/x", service)
    }

    @Test
    fun `没有 location 或没有 service 时返回 null`() {
        assertNull(extractService("https://vpn.seu.edu.cn/x", null))
        assertNull(extractService("https://vpn.seu.edu.cn/x", ""))
        assertNull(extractService("https://vpn.seu.edu.cn/x", "https://auth.seu.edu.cn/a?other=1"))
        // service 为空串同样视为没给
        assertNull(extractService("https://vpn.seu.edu.cn/x", "https://auth.seu.edu.cn/a?service="))
    }

    @Test
    fun `base 不是合法 URL 时不崩，返回 null`() {
        assertNull(extractService("not a url", "https://a.example/x?service=y"))
    }
}
