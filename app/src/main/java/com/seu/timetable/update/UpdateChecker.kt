package com.seu.timetable.update

import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新源。
 *
 * 做成有序列表而非单一 URL，是因为**没有任何一个域名能保证一直可达**：
 * GitHub Release 的资产会重定向到 `release-assets.githubusercontent.com`，
 * 国内可达性时好时坏；镜像站可能停服；对象存储可能欠费。
 * 多源依次尝试，只要其中任意一个活着，更新功能就还在。
 *
 * 这里的地址是**默认值**，正式使用时按实际仓库替换（见 [UpdateSources.MANIFEST_URLS]）。
 */
object UpdateSources {

    /**
     * 清单地址，按优先级排列，依次降级。
     *
     * 目前填的是占位仓库名，等你建好仓库后替换成真实地址即可——
     * 这是**唯一**需要在换托管时改动的地方，检查逻辑本身与域名无关。
     *
     * 约定：仓库里放一个 `update.json`（内容见 [UpdateInfo]），
     * APK 作为 Release 资产上传，`apkUrl` 填其直链。
     */
    val MANIFEST_URLS: List<String> = listOf(
        // 首选：raw 直链，跳数最少、响应最快
        "https://raw.githubusercontent.com/OWNER/REPO/main/update.json",
        // 兜底：jsDelivr 的 GitHub 镜像，国内可达性通常优于 raw
        "https://cdn.jsdelivr.net/gh/OWNER/REPO@main/update.json",
    )
}

/**
 * 检查更新的结果。
 *
 * 用密封类而非「可空 + 布尔」，是为了让调用方必须显式处理每种情况：
 * 尤其是 [Failed] 绝不能当成「已是最新」——那会让用户以为检查成功了，
 * 实际上根本没连上。
 */
sealed interface CheckResult {

    /** 有新版本可用 */
    data class Available(val info: UpdateInfo) : CheckResult

    /** 已是最新（至少有一个源成功响应，且版本不高于本机） */
    data class UpToDate(val localCode: Int, val remoteName: String) : CheckResult

    /**
     * 全部源都失败。
     *
     * [reason] 只用于日志与排查，不给用户看技术细节。
     */
    data class Failed(val reason: String) : CheckResult
}

/**
 * 更新检查器。
 *
 * 行为约定（重要）：
 *  - **永不抛异常**。所有网络错误都被收敛成 [CheckResult.Failed]，
 *    因为「检查更新失败」不该打断用户任何操作。
 *  - **短超时**。检查一次最多几秒，不能因为某个源挂起而让界面一直转圈。
 *  - **不重试**。失败就降级到下一个源，重试只会拖长等待。
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val sources: List<String> = UpdateSources.MANIFEST_URLS,
    /**
     * 取回清单正文。抽成可注入的函数，是为了让「多源降级」这条核心保证能被单元测试
     * 真正覆盖——否则只能靠真机连网络碰运气，而源不可用恰恰是最难复现的场景。
     * 默认实现走 [client]。
     */
    private val fetchBody: suspend (String) -> String? = { url -> httpGet(client, url) },
) {

    /**
     * 拉取清单并与本机版本比对。
     *
     * @param localCode 本机 versionCode，取自 BuildConfig，避免手写常量与构建配置脱节。
     */
    suspend fun check(localCode: Int): CheckResult = withContext(Dispatchers.IO) {
        var lastReason = "未配置更新源"

        for (url in sources) {
            // 占位地址直接跳过，省一次必然失败的请求
            if (url.contains("OWNER/REPO")) {
                lastReason = "更新源尚未配置"
                continue
            }

            val body = runCatching { fetchBody(url) }.getOrElse { e ->
                lastReason = "获取失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"
                DebugLog.w("更新：源不可用 ${DebugLog.url("", url)} → $lastReason")
                null
            }

            val info = UpdateManifest.parse(body)
            if (info == null) {
                // 拿到了响应但不是合法清单：继续试下一个源，不中断
                lastReason = "清单解析失败"
                DebugLog.w("更新：清单解析失败 ${DebugLog.url("", url)}")
                continue
            }
            if (!info.usable) {
                lastReason = "清单缺少 apkUrl 或 versionCode"
                DebugLog.w("更新：清单不可用（$lastReason）")
                continue
            }

            DebugLog.i("更新：清单来自 ${DebugLog.url("", url)} versionCode=${info.versionCode}")
            return@withContext if (AppVersion.shouldUpdate(localCode, info.versionCode)) {
                CheckResult.Available(info)
            } else {
                CheckResult.UpToDate(localCode, info.versionName)
            }
        }

        CheckResult.Failed(lastReason)
    }

    companion object {
        /** 检查用的短超时客户端：等待上限 8 秒，超过就认为该源不可用。 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/** 请求所用的 UA。GitHub raw 会拒绝无 UA 的请求，带上也顺便避免被当作爬虫。 */
private const val USER_AGENT = "SEU-Timetable-Android"

/**
 * 拉取一个清单地址。
 *
 * 放在顶层而非 [UpdateChecker] 的成员方法，是因为它要被用作构造参数的默认值——
 * 默认值在实例尚未构造完成时求值，引用实例方法编译不过。
 *
 * 非 2xx 一律抛异常，由调用方降级到下一个源。
 */
private suspend fun httpGet(client: OkHttpClient, url: String): String? =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            // 清单只有几百字节，禁掉缓存，避免拿到旧版本导致「检查了却没提示」
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            response.body?.string()
        }
    }
