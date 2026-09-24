package com.seu.timetable.update

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.seu.timetable.BuildConfig
import com.seu.timetable.R
import com.seu.timetable.ui.components.MessageDialog
import com.seu.timetable.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 应用内更新的全部交互，收在一个 Composable 里。
 *
 * 调用方（设置页）只需在「检查更新」被点击时置 `start = true`，
 * 剩下的检查、下载、授权、安装与各阶段的提示都由这里负责。
 * 这样做的好处是设置页不必了解更新流程的四个阶段，也不必持有下载状态。
 */
@Composable
fun UpdateFlow(
    start: Boolean,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val checker = remember { UpdateChecker() }
    val state = remember { UpdateUiState() }

    // 是否要弹「发现新版本」的弹层
    var detailOpen by remember { mutableStateOf(false) }
    // 是否要弹「需要授权安装」的弹层
    var permissionOpen by remember { mutableStateOf(false) }

    // 待下载的清单：存储权限是异步申请的，授权回来后才继续下载。
    // 不存这个引用就会丢掉「用户点的是哪个版本」这一信息。
    var pendingAfterPermission by remember { mutableStateOf<UpdateInfo?>(null) }

    /**
     * 写外部存储权限申请（仅 API 26..28 会走到）。
     *
     * 用 rememberLauncherForActivityResult 而非自己构造 startActivityForResult：
     * 前者在配置变更（旋屏）后仍能收到回调并持有正确的结果，手写版本极容易在这里丢结果。
     */
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val info = pendingAfterPermission
        pendingAfterPermission = null
        if (info == null) return@rememberLauncherForActivityResult
        if (granted) {
            startDownload(context, state, info)
        } else {
            // 拒绝就明确告知，不做「其实我们还能换个方式下载」的暗示——API 28 及以下确实不行
            DebugLog.w("更新：用户拒绝了存储权限，无法下载")
            state.toFailedDownload("storage:denied")
            toast(context, context.getString(R.string.update_download_failed))
        }
    }

    // 点击「检查更新」→ 走一遍检查
    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        state.toChecking()
        val result = checker.check(BuildConfig.VERSION_CODE)
        state.apply(result)

        when (result) {
            is CheckResult.Available -> detailOpen = true
            is CheckResult.UpToDate -> toast(context, context.getString(R.string.update_uptodate))
            is CheckResult.Failed -> {
                DebugLog.w("更新：检查失败 → ${result.reason}")
                toast(context, context.getString(R.string.update_failed))
            }
        }
        onFinished()
    }

    // 下载轮询：DownloadManager 只是提交任务，完成与否得自己查
    val downloading = state.phase as? UpdateUiState.Phase.Downloading
    if (downloading != null) {
        LaunchedEffect(downloading.downloadId) {
            val id = downloading.downloadId
            repeat(DOWNLOAD_POLL_MAX) {
                delay(DOWNLOAD_POLL_INTERVAL_MS)
                if (withContext(Dispatchers.IO) { ApkInstaller.isDownloaded(context, id) }) {
                    state.toReady(downloading.info)
                    DebugLog.i("更新：下载完成，等待安装")
                    return@LaunchedEffect
                }
                val reason = withContext(Dispatchers.IO) { ApkInstaller.failureReason(context, id) }
                if (reason != null) {
                    // 下载失败（含用户主动取消）→ 回到可重试状态，不弹「已是最新」误导用户
                    DebugLog.w("更新：下载失败 $reason")
                    state.toFailedDownload("download:$reason")
                    toast(context, context.getString(R.string.update_download_failed))
                    return@LaunchedEffect
                }
            }
            // 轮询超时：任务可能还在后台跑，不武断报错，只记日志。
            // 用户下次进来再点一次即可，DownloadManager 自身有断点续传。
            DebugLog.w("更新：等待下载完成超时（任务可能仍在后台）")
            state.toFailedDownload("download:timeout")
        }
    }

    // 下载完成 → 若已有安装权限就直接拉起安装器，否则引导去授权
    val ready = state.phase as? UpdateUiState.Phase.ReadyToInstall
    if (ready != null) {
        LaunchedEffect(ready.info.versionCode) {
            if (ApkInstaller.canInstall(context)) {
                if (!ApkInstaller.install(context, ApkInstaller.downloadedFile())) {
                    toast(context, context.getString(R.string.update_download_failed))
                    state.toFailedDownload("install:launch-failed")
                }
            } else {
                // 未授权时不静默失败，明确告诉用户为什么要去开这个开关
                permissionOpen = true
            }
        }
    }

    if (detailOpen) {
        val info = state.pendingInfo
        if (info == null) {
            detailOpen = false
        } else {
            val downloadingNow = state.phase is UpdateUiState.Phase.Downloading
            MessageDialog(
                title = context.getString(
                    R.string.update_available,
                    AppVersion.displayName(info.versionName, info.versionCode),
                ),
                body = buildString {
                    append("当前版本 ${AppVersion.displayName(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)}")
                    if (info.notes.isNotBlank()) {
                        append("\n\n")
                        append(info.notes.trim())
                    }
                },
                // MessageDialog 的语义：confirmText 是左侧文字按钮，actionText 是右侧主色按钮。
                // 下载中不给任何操作（文案即状态）；未下载时左侧「稍后」、右侧主色「立即更新」。
                confirmText = if (downloadingNow) {
                    context.getString(R.string.update_downloading)
                } else {
                    context.getString(R.string.update_skip)
                },
                actionText = if (downloadingNow) null else context.getString(R.string.update_install_now),
                // 主色按钮点击=关掉弹层并开始下载（必要时先要存储权限）
                onAction = if (downloadingNow) {
                    null
                } else {
                    {
                        detailOpen = false
                        if (ApkInstaller.hasStoragePermission(context)) {
                            startDownload(context, state, info)
                        } else {
                            // API 26..28：先把权限要来，授权回调里再接着下载
                            pendingAfterPermission = info
                            storageLauncher.launch(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        }
                    }
                },
                onDismiss = {
                    // 左侧「稍后」或点外部关闭：单纯关掉。
                    // 未开始下载时把状态清回 Idle，避免下次进入设置页又自动弹同一个版本。
                    detailOpen = false
                    if (!downloadingNow) state.idle()
                },
            )
        }
    }

    if (permissionOpen) {
        MessageDialog(
            title = context.getString(R.string.update_need_permission),
            body = context.getString(R.string.update_need_permission_body),
            confirmText = context.getString(R.string.update_go_settings),
            actionText = context.getString(R.string.update_skip),
            onAction = { permissionOpen = false },
            onDismiss = {
                permissionOpen = false
                ApkInstaller.openInstallPermissionSettings(context)
            },
        )
    }
}

/**
 * 提交下载并切到 Downloading。
 *
 * 注意 `enqueue` 返回 -1 表示**提交失败**（如下载服务不可用、URL 非法），
 * 与「提交成功但后续下载失败」是两回事，这里要分开处理。
 */
private fun startDownload(
    context: Context,
    state: UpdateUiState,
    info: UpdateInfo,
) {
    val id = ApkInstaller.enqueue(context, info)
    if (id < 0) {
        toast(context, context.getString(R.string.update_download_failed))
        state.toFailedDownload("enqueue:rejected")
        return
    }
    state.toDownloading(info, id)
    DebugLog.i("更新：已提交下载任务 id=$id")
}

/** 下载轮询节奏：1.5 秒一次、最多 80 次（约 2 分钟）。1.6MB 的包足够。 */
private const val DOWNLOAD_POLL_INTERVAL_MS = 1_500L
private const val DOWNLOAD_POLL_MAX = 80

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
