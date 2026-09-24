package com.seu.timetable.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 更新清单。
 *
 * 设计取向：**不用 GitHub Releases API 直接解析**，而是要求发布方维护一个极小的 JSON。
 * 理由有三：
 *  1. **托管无关**——换成 Gitee、对象存储、任意静态站点，App 侧一行代码都不用改；
 *     若直接吃 GitHub API 的返回结构，换源就等于重写解析层。
 *  2. **省流量**——Release API 的 JSON 有几十 KB 且字段随 GitHub 升级增删；
 *     本清单只有几百字节，且结构完全由我们掌握，不会被上游改坏。
 *  3. **可控**——可以在清单里直接写明分组名、更新说明、是否强制，不必迁就平台字段。
 *
 * 字段全部带默认值：发布方漏填某一项时解析仍能成功，只影响展示，不至于让检查更新整条断掉。
 */
@Serializable
data class UpdateInfo(
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("versionName") val versionName: String = "",
    /** APK 直链。必须是免登录可直接 GET 的地址。 */
    @SerialName("apkUrl") val apkUrl: String = "",
    /** 更新说明，用 \n 分段。 */
    @SerialName("notes") val notes: String = "",
    /**
     * 是否强制更新。仅在需要阻断旧版本继续使用时才置 true——
     * 本项目是自用工具，通常留 false，让用户自己决定什么时候装。
     */
    @SerialName("mandatory") val mandatory: Boolean = false,
) {
    /**
     * 清单是否可用于下载。
     *
     * 三重校验，缺一不可：
     *  - 地址非空；
     *  - **地址是可下载的 http(s) 链接**——JSON 里写数字会被宽松解析强转成字符串
     *    （实测 `"apkUrl": 123` 会得到 `apkUrl="123"`），只查非空会让这种坏地址过关，
     *    用户点下载才发现打不开；
     *  - versionCode 有效，否则无法与本机比较。
     */
    val usable: Boolean get() = isDownloadableUrl(apkUrl) && versionCode > 0
}

/**
 * 是否是可用于下载的 http(s) 绝对地址。
 *
 * 放在顶层而非 data class 的伴生对象里：`usable` 是实例属性，
 * 访问 private companion 会编译不过；放这里对两类调用方都可见且不必放宽可见性。
 */
private fun isDownloadableUrl(url: String): Boolean {
    val u = url.trim()
    if (!u.startsWith("https://") && !u.startsWith("http://")) return false
    // 排除 "https://" 之后就没了主机名的情况
    return u.substringAfter("://").substringBefore('/').isNotBlank()
}

/**
 * 清单解析器。
 *
 * 宽松解析：忽略未知字段、容忍非严格 JSON。发布方（未来的自己）多写一个字段、
 * 或把布尔值写成字符串，都不该让用户的检查更新直接失败。
 */
internal object UpdateManifest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 解析失败返回 null；由调用方决定要不要落到下一个源。 */
    fun parse(body: String?): UpdateInfo? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 静态托管的原始文件偶尔会被包上一层，取第一个 { 到最后一个 } 更稳
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString(UpdateInfo.serializer(), text.substring(start, end + 1))
        }.getOrNull()
    }
}
