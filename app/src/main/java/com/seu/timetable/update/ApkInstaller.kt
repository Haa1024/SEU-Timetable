package com.seu.timetable.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK 下载与安装。
 *
 * 为什么用系统 [DownloadManager] 而不是 OkHttp 自己写：
 *  - 断网自动重试、跨进程存活（用户退到后台甚至杀掉 App 也能下完）；
 *  - 自带通知栏进度，省掉自己画进度条；
 *  - 下载目标落在公共目录，安装器能直接读。
 *
 * 体积代价为零（DownloadManager 是系统服务），可靠性却比自己写高得多。
 */
object ApkInstaller {

    /** 已下载文件的 MIME 类型，安装器靠它识别。 */
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 下载到公共的 Downloads 目录，用户能在文件管理器里看到、也能自己手动装。 */
    private const val APK_FILE_NAME = "SEU课表-update.apk"

    /**
     * 提交下载任务，返回系统分配的下载 id；提交失败返回 -1。
     *
     * 注意这里**只是提交**，不代表下载完成——完成与否要靠后续查
     * [DownloadManager.Query] 的 STATUS 字段，与小组件「requestPinAppWidget
     * 返回 true 不代表真加上」是同一类坑。
     */
    fun enqueue(context: Context, info: UpdateInfo): Long {
        if (info.apkUrl.isBlank()) return -1L
        // API 28 及以下：往公共 Downloads 目录写文件必须先有写外部存储权限，
        // 否则 enqueue 会抛 SecurityException（不是静默失败，是崩溃级异常）。
        if (!hasStoragePermission(context)) {
            DebugLog.w("更新：缺少写外部存储权限（API ${Build.VERSION.SDK_INT}），无法下载")
            return -1L
        }
        return runCatching {
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("SEU 课表更新")
                .setDescription("正在下载 ${AppVersion.displayName(info.versionName, info.versionCode)}")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                // 允许在计费网络下下载：APK 只有 1.6MB，为它拦一道「等 Wi-Fi」不划算
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        }.onFailure {
            DebugLog.e("更新：提交下载任务失败：${it.message}", it)
        }.getOrDefault(-1L)
    }

    /**
     * 下载是否已完成。
     *
     * 只有 `STATUS_SUCCESSFUL` 才算完成；`STATUS_FAILED`/`STATUS_PAUSED`
     * 一律按未完成处理，由调用方决定是否提示重试。
     */
    fun isDownloaded(context: Context, downloadId: Long): Boolean {
        if (downloadId < 0) return false
        return runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return false
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                status == DownloadManager.STATUS_SUCCESSFUL
            }
        }.getOrDefault(false)
    }

    /** 下载失败（含被用户取消）时的原因，仅用于日志。 */
    fun failureReason(context: Context, downloadId: Long): String? {
        if (downloadId < 0) return null
        return runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_FAILED) return null
                val code = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                "status=FAILED reason=$code"
            }
        }.getOrNull()
    }

    /**
     * 写外部存储权限是否已具备。
     *
     * 只在 API 28 及以下需要——API 29 起分区存储生效，应用写公共媒体目录不再要权限
     * （清单里那条也带了 maxSdkVersion=28 与之对应）。API 29+ 直接返回 true，
     * 避免去申请一个系统已经不再检查的权限。
     */
    fun hasStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 本应用是否被允许安装未知来源的应用。
     *
     * 这是**特殊权限**，不是运行时弹窗：用户必须在系统设置里手动打开开关。
     * 未授权时调用安装会被系统直接拒绝且不报错，所以调用前必须先查这里。
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            // API 26 以下的开关在「安全」设置里，无法直接查询；交给安装器自己判断。
            // 本项目 minSdk=26，此分支实际不会走到，留着只为语义完整。
            true
        }
    }

    /** 跳转到「安装未知应用」授权页，让用户手动打开开关。 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 少数 ROM 没有这个 Activity，退回应用详情页
                DebugLog.w("更新：授权页不可用，退回应用详情页")
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    /**
     * 拉起系统安装器。
     *
     * **必须经 FileProvider**：自 Android 7 起，把 `file://` 路径跨进程传给安装器
     * 会抛 `FileUriExposedException`（StrictMode 拦截），安装界面根本不会出现。
     * FileProvider 把文件以 `content://` 暴露，并凭 `<grantUriPermissions>`
     * 临时授权给安装器进程，是唯一被允许的做法。
     *
     * @return 是否成功拉起
     */
    fun install(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile) {
            DebugLog.e("更新：APK 不存在 ${apkFile.absolutePath}")
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure {
            DebugLog.e("更新：拉起安装器失败：${it.message}", it)
        }.getOrDefault(false)
    }

    /** 下载后的 APK 路径（与 [enqueue] 的目标保持一致）。 */
    fun downloadedFile(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        APK_FILE_NAME,
    )

    private fun authority(context: Context) = "${context.packageName}.fileprovider"
}
