package com.seu.timetable.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 更新流程的状态机。
 *
 * 为什么单独抽出来而不是散在 Composable 里：
 * 更新有「检查 → 下载 → 授权 → 安装」四个阶段，其中两步是异步的（网络、系统服务），
 * 一步要跳出应用再回来（授权页）。状态散落在多个 remember 里极易出现
 * 「下载完了但界面还停在检查中」这类不一致。集中成一个对象后，
 * 任何时刻界面都能只根据一个状态渲染。
 */
class UpdateUiState {

    /**
     * 界面当前应该显示什么。
     *
     * [Idle] 表示「什么都还没发生」——首次进入设置页时应停在这里，
     * 不自动检查：更新检查要走网络，用户没点就不该消耗流量。
     */
    sealed interface Phase {

        /** 尚未检查。 */
        data object Idle : Phase

        data object Checking : Phase

        /** 已是最新。 */
        data class UpToDate(val localCode: Int, val remoteName: String) : Phase

        /** 检查失败（全部源不可用）。与「已是最新」严格区分。 */
        data class Failed(val reason: String) : Phase

        data class Available(val info: UpdateInfo) : Phase

        /** 已提交下载，等待系统完成。 */
        data class Downloading(val info: UpdateInfo, val downloadId: Long) : Phase

        /** 下载完成、等待安装。 */
        data class ReadyToInstall(val info: UpdateInfo) : Phase
    }

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    /**
     * 更新的可用性检查是否正在进行。
     *
     * 独立于 [phase]：检查期间用户可能已经点开了详情弹层，
     * 此时 [phase] 应保持 Available 不被「Checking」覆盖。
     */
    var busy: Boolean by mutableStateOf(false)
        private set

    fun toChecking() {
        busy = true
        phase = Phase.Checking
    }

    fun idle() {
        busy = false
        phase = Phase.Idle
    }

    fun apply(result: CheckResult) {
        busy = false
        phase = when (result) {
            is CheckResult.UpToDate -> Phase.UpToDate(result.localCode, result.remoteName)
            is CheckResult.Failed -> Phase.Failed(result.reason)
            is CheckResult.Available -> Phase.Available(result.info)
        }
    }

    fun toDownloading(info: UpdateInfo, downloadId: Long) {
        busy = false
        phase = Phase.Downloading(info, downloadId)
    }

    fun toReady(info: UpdateInfo) {
        busy = false
        phase = Phase.ReadyToInstall(info)
    }

    fun toFailedDownload(reason: String) {
        busy = false
        phase = Phase.Failed(reason)
    }

    /** 当前若处在新版本可安装/可下载状态，取出来；否则 null。 */
    val pendingInfo: UpdateInfo?
        get() = when (val p = phase) {
            is Phase.Available -> p.info
            is Phase.Downloading -> p.info
            is Phase.ReadyToInstall -> p.info
            else -> null
        }
}
