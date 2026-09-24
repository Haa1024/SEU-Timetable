package com.seu.timetable.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ehall 的响应外壳：`{"datas": {"<接口名>": {"rows": [...]}}, "code": "0"}`
 *
 * `datas` 中的键名随接口变化（xskcb / cxjcs / xswpkc / dqxnxq …），
 *   故以 [JsonObject] 接收并按接口名取值，免去为每个接口定义外壳类。
 */
@Serializable
data class EhallEnvelope(
    val code: String = "",
    val datas: JsonObject = JsonObject(emptyMap()),
)

/** `{"totalSize": n, "rows": [...]}` */
@Serializable
data class EhallRows<T>(
    val totalSize: Int = 0,
    val rows: List<T> = emptyList(),
)

/**
 * ehall 的字段类型**不统一**，同一语义有时为字符串有时为数字：
 *  - `xskcb.do` 的 `KSJC` / `JSJC` / `SKXQ` 为 **字符串** `"5"`
 *  - `cxjcs.do` 的 `ZZC` / `SFJJ` … 为 **数字** `18`
 *  - `xswpkc.do` 的 `XF` / `XS` 为 **数字** `1` / `8`
 *
 * 因此凡具"数值语义"的字段统一以 [JsonElement] 接收，再经 [asIntOrNull] 转换。
 *   若以 `String?` 或 `Int?` 直接接收，将在某接口上解析失败。
 */
private typealias NumField = JsonElement?

/** 字符串语义的字段：取原文 */
internal fun JsonElement?.asTextOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

/** 数值语义字段：兼容字符串 `"5"` 与数字 `5`，并容忍 `"5.0"` */
internal fun JsonElement?.asIntOrNull(): Int? =
    asTextOrNull()?.trim()?.toDoubleOrNull()?.toInt()

internal fun JsonElement?.asDoubleOrNull(): Double? =
    asTextOrNull()?.trim()?.toDoubleOrNull()

/** `POST modules/xskcb/xskcb.do` 的一行 = **课表上一块**（不是一门课） */
@Serializable
data class XskcbRow(
    @SerialName("KCM") val courseName: String? = null,
    @SerialName("KCH") val courseCode: String? = null,
    @SerialName("KXH") val classNo: String? = null,
    @SerialName("SKJS") val teacher: String? = null,
    @SerialName("JASMC") val roomName: String? = null,      // 实验课常为 null
    @SerialName("SKXQ") val dayOfWeek: NumField = null,     // 1=周一 … 7=周日
    @SerialName("KSJC") val startPeriod: NumField = null,
    @SerialName("JSJC") val endPeriod: NumField = null,
    @SerialName("SKZC") val weekBitmap: String? = null,     // 0/1 周次位图
    @SerialName("ZCMC") val weekText: String? = null,       // 仅展示
    @SerialName("XNXQDM") val termCode: String? = null,
    @SerialName("JXBID") val classId: String? = null,   // 教学班号 → 课程分组键
    @SerialName("KBID") val sessionId: String? = null,  // 课表行 ID → 每个时段都不同
    /** 原始时间地点文字，仅用于展示，绝不用于生成课表块（详见 EhallMapper 要点 1）。 */
    @SerialName("YPSJDD") val rawTimePlace: String? = null,
)

/** `POST modules/jshkcb/cxjcs.do` 的一行 = 学期上下文 */
@Serializable
data class CxjcsRow(
    @SerialName("XQKSRQ") val termStartDate: String? = null,   // "2026-09-21 00:00:00"
    @SerialName("ZZC") val totalWeeks: NumField = null,        // 18
    @SerialName("ZJXZC") val lastTeachingWeek: NumField = null,// 16
    @SerialName("SFJJ") val morningPeriods: NumField = null,   // 上午 5 节
    @SerialName("XFJJ") val afternoonPeriods: NumField = null, // 下午 5 节
    @SerialName("WSJJ") val eveningPeriods: NumField = null,   // 晚上 3 节
)

/** `POST modules/jshkcb/dqxnxq.do` 的一行 = 当前学年学期 */
@Serializable
data class DqxnxqRow(
    @SerialName("DM") val termCode: String? = null,      // "2026-2027-2"
    @SerialName("MC") val termName: String? = null,      // "2026-2027学年秋季学期"
    @SerialName("XNDM") val yearCode: String? = null,    // "2026-2027"
    @SerialName("XQDM") val termParam: String? = null,   // "2"
)

/**
 * `POST modules/xskcb/xswpkc.do` 的一行 = 未排课课程。
 *
 * 注意此处 `SKZC` 为**文本**（`"7-14周"`），非位图——
 *   与 `xskcb.do` 中同名字段含义不同，混用必错。
 */
@Serializable
data class XswpkcRow(
    @SerialName("KCM") val courseName: String? = null,
    @SerialName("KCH") val courseCode: String? = null,
    @SerialName("SKJS") val teacher: String? = null,
    @SerialName("XF") val credit: NumField = null,       // 学分
    @SerialName("XS") val hours: NumField = null,        // 学时
    @SerialName("SKZC") val weeksText: String? = null,   // 文本周次，不是位图
)

/** `POST modules/jshkcb/dqzc.do` 的一行 = 某日期属于第几周（服务端权威值） */
@Serializable
data class DqzcRow(
    @SerialName("RQ") val date: String? = null,
    @SerialName("XQJ") val dayOfWeek: NumField = null,
    @SerialName("ZC") val week: NumField = null,
)
