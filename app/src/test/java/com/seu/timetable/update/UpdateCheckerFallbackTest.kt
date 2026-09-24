package com.seu.timetable.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多源降级的行为测试。
 *
 * 「没人能保证某个域名一直可达」是本项目更新功能的**前提假设**，
 * 因此降级逻辑不是锦上添花，而是功能能不能用的关键。这里用注入的取数函数
 * 复现那些真机极难碰上的情形：首个源超时、返回半截内容、清单字段不全等。
 */
class UpdateCheckerFallbackTest {

    private val valid =
        """{"versionCode":9,"versionName":"0.9.0","apkUrl":"https://x/y.apk","notes":"n"}"""

    /** 用桩构造检查器；[responder] 按 URL 返回内容，抛异常即模拟网络失败。 */
    private fun checker(
        sources: List<String> = listOf("https://a/m.json", "https://b/m.json", "https://c/m.json"),
        responder: (String) -> String?,
    ) = UpdateChecker(sources = sources, fetchBody = { url -> responder(url) })

    @Test
    fun `首个源可用则不再访问后续源`() = runBlocking {
        val visited = mutableListOf<String>()
        val result = checker { url ->
            visited += url
            valid
        }.check(localCode = 1)

        assertTrue(result is CheckResult.Available)
        // 只应该请求一次：多请求等于白白拖长用户等待
        assertEquals(listOf("https://a/m.json"), visited)
    }

    @Test
    fun `首个源抛异常时自动降级到下一个`() = runBlocking {
        val visited = mutableListOf<String>()
        val result = checker { url ->
            visited += url
            if (url.contains("//a/")) error("timeout") else valid
        }.check(localCode = 1)

        assertTrue(result is CheckResult.Available)
        assertEquals(listOf("https://a/m.json", "https://b/m.json"), visited)
    }

    @Test
    fun `前两个源都坏时降级到第三个`() = runBlocking {
        val result = checker { url ->
            when {
                url.contains("//a/") -> error("timeout")
                url.contains("//b/") -> "这不是 JSON"
                else -> valid
            }
        }.check(localCode = 1)

        assertTrue(result is CheckResult.Available)
    }

    @Test
    fun `清单缺少 apkUrl 时继续尝试下一个源`() = runBlocking {
        // 拿到了合法 JSON 但内容不完整——不能就此断定「已是最新」，
        // 因为这只是这个源的清单有问题，别的源可能是好的。
        val result = checker { url ->
            if (url.contains("//a/")) """{"versionCode":9}""" else valid
        }.check(localCode = 1)

        assertTrue(result is CheckResult.Available)
    }

    @Test
    fun `全部源失败时返回 Failed 而不是 UpToDate`() = runBlocking {
        // 这是最容易写错、后果也最误导的一条：把网络失败当成「已是最新」，
        // 用户会以为检查成功了，之后再也不会手动去看有没有新版本。
        val result = checker { error("network down") }.check(localCode = 1)

        assertTrue("网络全挂时必须报 Failed，实际是 $result", result is CheckResult.Failed)
    }

    @Test
    fun `空源列表返回 Failed`() = runBlocking {
        val result = checker(sources = emptyList()) { valid }.check(localCode = 1)
        assertTrue(result is CheckResult.Failed)
    }

    @Test
    fun `占位地址被跳过不产生请求`() = runBlocking {
        // 仓库还没建时清单地址是占位符，此时不该真的发请求（必然失败还慢）
        val visited = mutableListOf<String>()
        val result = checker(
            sources = listOf("https://raw.githubusercontent.com/OWNER/REPO/main/update.json"),
        ) { url ->
            visited += url
            valid
        }.check(localCode = 1)

        assertTrue(result is CheckResult.Failed)
        assertTrue("占位地址不该发起请求", visited.isEmpty())
    }

    @Test
    fun `远端版本不高于本机时返回已是最新`() = runBlocking {
        val result = checker { """{"versionCode":1,"apkUrl":"https://x/y.apk"}""" }
            .check(localCode = 1)
        assertTrue(result is CheckResult.UpToDate)
    }

    @Test
    fun `远端版本更新时返回可更新并带上清单`() = runBlocking {
        val result = checker { valid }.check(localCode = 1)
        assertTrue(result is CheckResult.Available)
        assertEquals(9, (result as CheckResult.Available).info.versionCode)
    }
}
