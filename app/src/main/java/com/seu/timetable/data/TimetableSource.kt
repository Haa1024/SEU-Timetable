package com.seu.timetable.data

import com.seu.timetable.domain.Timetable

/**
 * 课表数据源接口。当前数据来自 ehall「我的课表」微应用，
 *   未来亦可切换其他教务接口，上层无需感知接口形态。
 */
interface TimetableSource {

    /**
     * 加载一次课表。
     * @param termCode 学期代码如 `2026-2027-2`；传 null 表示「当前学期」。
     */
    suspend fun load(termCode: String? = null): Timetable
}

open class TimetableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 登录态失效。
 *
 * 单独成类以便 UI 区分"需重新登录"与"网络/数据出错"——
 *   前者弹出 WebView 登录页（规格第 8 章「同步失败」态），后者提示重试。
 */
class NotLoggedInException(message: String) : TimetableException(message)
