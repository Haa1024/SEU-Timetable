package com.seu.timetable.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较与清单解析的护栏测试。
 *
 * 这两块是纯逻辑，没有真机也能完全验证，而且**一旦出错后果隐蔽**：
 *  - 版本比较写反 → 永远不提示更新，或一直提示「有新版本」但装不了；
 *  - 清单解析太严 → 发布方多写一个字段就让所有用户的检查更新失效。
 * 所以把边界钉死在这里。
 */
class UpdateLogicTest {

    // ---------------- 版本比较 ----------------

    @Test
    fun `远端更高才提示更新`() {
        assertTrue(AppVersion.shouldUpdate(localCode = 1, remoteCode = 2))
        assertTrue(AppVersion.shouldUpdate(localCode = 1, remoteCode = 100))
    }

    @Test
    fun `版本相同不提示更新`() {
        // 安装器同样会拒绝同版本包，所以这里必须为 false，否则会给出一个点不动的提示
        assertFalse(AppVersion.shouldUpdate(localCode = 5, remoteCode = 5))
    }

    @Test
    fun `远端更旧不提示更新`() {
        // 用户手动装了内测包、或发布方回滚版本时会出现。
        // 若这里为 true，用户点了「立即更新」会因降级安装被系统拒绝，表现为莫名其妙的失败。
        assertFalse(AppVersion.shouldUpdate(localCode = 10, remoteCode = 9))
    }

    @Test
    fun `versionCode 解析容忍常见写法`() {
        assertEquals(3, AppVersion.parseCode("3"))
        assertEquals(3, AppVersion.parseCode(" 3 "))
        assertEquals(3, AppVersion.parseCode("v3"))
        assertEquals(3, AppVersion.parseCode("V3"))
        // 带点的语义化版本：取第一段数字，至少能比较出「3.x 比 2.x 新」
        assertEquals(3, AppVersion.parseCode("3.1.4"))
        assertEquals(3, AppVersion.parseCode("v3.1.4"))
    }

    @Test
    fun `无法解析的 versionCode 返回 null`() {
        assertNull(AppVersion.parseCode(null))
        assertNull(AppVersion.parseCode(""))
        assertNull(AppVersion.parseCode("   "))
        assertNull(AppVersion.parseCode("abc"))
        assertNull(AppVersion.parseCode("v"))
    }

    @Test
    fun `展示名称为空时回落成版本号`() {
        assertEquals("版本 7", AppVersion.displayName("", 7))
        assertEquals("版本 7", AppVersion.displayName("   ", 7))
        assertEquals("版本 7", AppVersion.displayName(null, 7))
        assertEquals("1.2.0", AppVersion.displayName("1.2.0", 7))
    }

    // ---------------- 清单解析 ----------------

    @Test
    fun `完整清单能正确解析`() {
        val info = UpdateManifest.parse(
            """
            {
              "versionCode": 2,
              "versionName": "0.1.1",
              "apkUrl": "https://example.com/app.apk",
              "notes": "修了小组件行数",
              "mandatory": false
            }
            """.trimIndent()
        )
        assertEquals(2, info?.versionCode)
        assertEquals("0.1.1", info?.versionName)
        assertEquals("https://example.com/app.apk", info?.apkUrl)
        assertEquals("修了小组件行数", info?.notes)
    }

    @Test
    fun `未知字段不影响解析`() {
        // 发布方（未来的自己）多写字段是常态，不能让整个检查更新因此失败
        val info = UpdateManifest.parse(
            """{"versionCode":3,"apkUrl":"https://x/y.apk","newField":"ignored","nested":{"a":1}}"""
        )
        assertEquals(3, info?.versionCode)
    }

    @Test
    fun `缺少可选字段用默认值`() {
        val info = UpdateManifest.parse("""{"versionCode":2,"apkUrl":"https://x/y.apk"}""")
        assertEquals("", info?.versionName)
        assertEquals("", info?.notes)
        assertFalse(info?.mandatory ?: true)
    }

    @Test
    fun `非法 JSON 解析为 null 而不是抛异常`() {
        // 返回 null 让调用方降级到下一个源；抛异常会一路冒到界面
        assertNull(UpdateManifest.parse(null))
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("   "))
        assertNull(UpdateManifest.parse("not json at all"))
        assertNull(UpdateManifest.parse("[]"))
        assertNull(UpdateManifest.parse("{"))
    }

    @Test
    fun `versionCode 类型写错会让整个解析失败`() {
        // 实测确认：coerceInputValues 只处理「缺失/null 走默认值」，不做字符串→整数强转。
        // 所以 versionCode 写成 "abc"、true、5.9 都会让解析失败（返回 null），
        // 调用方据此降级到下一个源——比「解析成功但值变成 0」安全得多。
        assertNull(UpdateManifest.parse("""{"versionCode":"abc","apkUrl":"https://x/y.apk"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":true,"apkUrl":"https://x/y.apk"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":5.9,"apkUrl":"https://x/y.apk"}"""))
    }

    @Test
    fun `apkUrl 不是合法链接时判为不可用`() {
        // 实测确认的坑：字符串字段会把 JSON 数字强转成字符串，
        // `"apkUrl": 123` 得到的是 apkUrl="123"——只查非空会让它过关，
        // 用户点了下载才发现打不开。所以 usable 必须校验协议与主机名。
        val numeric = UpdateManifest.parse("""{"versionCode":5,"apkUrl":123}""")
        assertEquals("123", numeric?.apkUrl)
        assertFalse("数字型 apkUrl 必须被判为不可用", numeric!!.usable)

        // 各种不完整/不支持的写法同样拦下
        assertFalse(UpdateInfo(versionCode = 1, apkUrl = "ftp://x/y.apk").usable)
        assertFalse(UpdateInfo(versionCode = 1, apkUrl = "https://").usable)
        assertFalse(UpdateInfo(versionCode = 1, apkUrl = "/local/path.apk").usable)
        // 正常地址放行
        assertTrue(UpdateInfo(versionCode = 1, apkUrl = "https://x/y.apk").usable)
        assertTrue(UpdateInfo(versionCode = 1, apkUrl = "http://x/y.apk").usable)
    }

    @Test
    fun `被包了一层的清单也能解析`() {
        // 某些静态托管会在原始文件外面加壳，取首尾花括号之间的内容更稳
        val info = UpdateManifest.parse(
            "prefix noise\n{\"versionCode\":4,\"apkUrl\":\"https://x/y.apk\"}\nsuffix"
        )
        assertEquals(4, info?.versionCode)
    }

    @Test
    fun `usable 判定要求地址合法且版本号有效`() {
        assertTrue(UpdateInfo(versionCode = 1, apkUrl = "https://x/y.apk").usable)
        // 地址空：点不了下载，视为不可用
        assertFalse(UpdateInfo(versionCode = 1, apkUrl = "").usable)
        // 版本号 0：无法与任何本机版本比较，视为不可用
        assertFalse(UpdateInfo(versionCode = 0, apkUrl = "https://x/y.apk").usable)
    }

    // ---------------- 检查结果与状态机 ----------------

    @Test
    fun `检查结果三态互不混淆`() {
        // 「失败」绝不能与「已是最新」混为一谈：前者必须让用户知道是网络问题，
        // 后者才是真的没有新版本。用密封类就是为了让调用方必须分开处理。
        val upToDate = CheckResult.UpToDate(localCode = 1, remoteName = "0.1.0")
        val failed = CheckResult.Failed("网络不可达")
        assertTrue(upToDate is CheckResult.UpToDate)
        assertTrue(failed is CheckResult.Failed)
    }

    @Test
    fun `状态机只在可更新时给出待安装信息`() {
        val state = UpdateUiState()
        assertNull(state.pendingInfo)

        // 已是最新 → 没有可安装的东西
        state.apply(CheckResult.UpToDate(1, "0.1.0"))
        assertNull(state.pendingInfo)

        // 检查失败 → 同样没有
        state.apply(CheckResult.Failed("timeout"))
        assertNull(state.pendingInfo)

        // 有新版本 → 能取出来
        val info = UpdateInfo(versionCode = 2, apkUrl = "https://x/y.apk")
        state.apply(CheckResult.Available(info))
        assertEquals(2, state.pendingInfo?.versionCode)

        // 下载中、下载完成都要能取到（否则弹层会在下载途中突然失去内容）
        state.toDownloading(info, downloadId = 42)
        assertEquals(2, state.pendingInfo?.versionCode)
        state.toReady(info)
        assertEquals(2, state.pendingInfo?.versionCode)

        // 回到空闲后清空
        state.idle()
        assertNull(state.pendingInfo)
    }

    @Test
    fun `检查期间 busy 为真且结束后复位`() {
        val state = UpdateUiState()
        assertFalse(state.busy)
        state.toChecking()
        assertTrue(state.busy)
        state.apply(CheckResult.Failed("x"))
        assertFalse(state.busy)
    }
}
