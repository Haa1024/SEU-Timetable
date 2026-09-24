package com.seu.timetable.data

import android.webkit.CookieManager

/**
 * 登录态管理。
 *
 * 刻意不保存账号密码：会话完全由 WebView 的 CookieManager 持有，
 * 本类仅负责两项职责——判断会话是否有效、以及清除会话。
 *
 * 不自行实现 cookie 存储：ehall 会话存在服务端超时，本地存储再稳定亦无法挽回；
 * 且 Android 的 CookieManager 本身即会落盘 cookie（配合 `flush()`）。
 * 因此策略为"用到才发现过期 → 引导重登"，而非"提前缓存"。
 */
object SessionManager {

    private val client = EhallClient()

    /**
     * 通过一次真实接口调用来判断登录态。
     * 不依赖"猜测某个 cookie 是否存在"——cookie 名称可变，接口结果更为可靠。
     */
    suspend fun isLoggedIn(): Boolean = client.hasSession()

    /** 退出登录，下次进入时重新弹出 WebView 登录页。 */
    fun signOut() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }
}
