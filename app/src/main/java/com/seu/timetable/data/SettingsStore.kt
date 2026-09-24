package com.seu.timetable.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "seu_timetable")

/**
 * 本地设置。
 *
 * 这些值均**不属于教务数据**（教务侧仅含课表本身），故仅能存储于本地。
 *
 * 课表改为本地资产后，此类已大幅精简：原先按"启动即拉取"设计的
 *   「自动同步开关」「上次同步时间」等字段均已移除，仅保留与显示、提醒相关的偏好，
 *   与课表数据本身无关。
 */
class SettingsStore(private val context: Context) {

    /**
     * 已手动关闭「未排课」提示条的学期代码。
     *
     * 记录的是**学期**而非布尔值：换学期后提示应重新出现，
     *   否则新学期新增课程将永远不再提示。
     */
    val dismissedUnplacedTerm: Flow<String?> =
        context.settingsDataStore.data.map { it[KEY_DISMISSED_UNPLACED] }

    /** 可选值 SYSTEM / LIGHT / DARK。以字符串存储，便于后续扩展模式而无需变更存储格式。 */
    val themeMode: Flow<String> =
        context.settingsDataStore.data.map { it[KEY_THEME_MODE] ?: "SYSTEM" }

    val reminderEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_REMINDER_ENABLED] ?: false }

    /** 提前几分钟提醒，默认 15 */
    val reminderMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_REMINDER_MINUTES] ?: 15 }

    /**
     * 实操引导是否已经看过。
     *
     * 只在**真正完整走过一遍**（或主动跳过）后置真。默认 false——
     * 首次安装的用户值即为"没看过"，回主页时自动开始引导。
     *
     * 为什么不复用"是否有课表"来判断：那只能表达"用户建过课表"，
     * 无法表达"用户已经知道各功能在哪"。老用户升级到带引导的版本、
     * 或清过一次数据，都会退化成"又被引导一遍"。
     */
    val guideSeen: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_GUIDE_SEEN] ?: false }

    suspend fun markGuideSeen() =
        context.settingsDataStore.edit { it[KEY_GUIDE_SEEN] = true }

    /** 供「重看新手指引」使用：把标记清掉，回主页时即会重新开始。 */
    suspend fun resetGuideSeen() =
        context.settingsDataStore.edit { it.remove(KEY_GUIDE_SEEN) }

    suspend fun dismissUnplaced(termCode: String) =
        context.settingsDataStore.edit { it[KEY_DISMISSED_UNPLACED] = termCode }

    suspend fun restoreUnplacedBar() =
        context.settingsDataStore.edit { it.remove(KEY_DISMISSED_UNPLACED) }

    suspend fun setThemeMode(mode: String) =
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode }

    suspend fun setReminderEnabled(value: Boolean) =
        context.settingsDataStore.edit { it[KEY_REMINDER_ENABLED] = value }

    suspend fun setReminderMinutes(value: Int) =
        context.settingsDataStore.edit { it[KEY_REMINDER_MINUTES] = value }

    /**
     * 此处**刻意不提供** `autoSync` / `lastSyncAt`。
     *
     * 课表为本机资产：启动应用不会向教务拉取任何数据，用户未点击「同步」则数据
     *   永不被覆盖。因此"上次同步时间"类字段无存在意义——它只会产生虚假的
     *   "自动同步"概念，使用户误以为后台在持续运行。
     * 真正需展示的是**该课表最后一次导入的时刻**，属每张课表自身属性，
     *   存储于 `BoardMeta.lastImportAt`，与全局设置无关。
     *
     * 「待同步标记」「同步失败重试」同理：无自动同步则无相关状态需记录。
     */
    private companion object {
        val KEY_DISMISSED_UNPLACED = stringPreferencesKey("unplaced_bar_dismissed_term")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val KEY_GUIDE_SEEN = booleanPreferencesKey("guide_seen")
    }
}
