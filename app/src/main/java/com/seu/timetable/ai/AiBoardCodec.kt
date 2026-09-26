package com.seu.timetable.ai

import com.seu.timetable.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDate

/** Lossless adapter for the shared, tested JS engine. No model output writes disk directly. */
object AiBoardCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun version(meta: BoardMeta, content: BoardContent): String = MessageDigest.getInstance("SHA-256")
        .digest((json.encodeToString(meta) + "\n" + json.encodeToString(content)).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun snapshot(meta: BoardMeta, content: BoardContent, viewedWeek: Int, today: LocalDate = LocalDate.now()): JsonObject = buildJsonObject {
        put("token", version(meta, content))
        put("date", today.toString()); put("viewedWeek", viewedWeek)
        put("periods", json.encodeToJsonElement(meta.periodSchedule.times))
        put("board", buildJsonObject {
            put("id", meta.id); put("name", meta.name)
            put("term", buildJsonObject {
                put("firstMonday", meta.term.firstMonday.toString()); put("totalWeeks", meta.term.totalWeeks)
                put("lastTeachingWeek", meta.term.lastTeachingWeek)
            })
            put("courses", JsonArray(content.courses.map { c -> buildJsonObject {
                put("id", c.id); put("name", c.name); put("teacher", c.teacher)
                put("credit", c.credit?.let(::JsonPrimitive) ?: JsonPrimitive(""))
                put("note", c.note); put("code", c.courseCode); put("classNo", c.classNo)
                put("colorOverride", c.colorOverride?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("sessions", JsonArray(content.sessions.map { s -> buildJsonObject {
                put("id", s.id); put("courseId", s.courseId); put("day", s.dayOfWeek)
                put("start", s.startPeriod); put("end", s.endPeriod)
                put("weeks", JsonArray(s.weeks.sorted().map(::JsonPrimitive))); put("room", s.room)
            } }))
            put("unplaced", JsonArray(content.unplaced.mapIndexed { i, c -> JsonObject(
                json.encodeToJsonElement(c).jsonObject + ("id" to JsonPrimitive("native-unplaced-$i"))) }))
            put("aiRequestIds", content.aiState?.get("aiRequestIds") ?: JsonArray(emptyList()))
            put("aiLastOperation", content.aiState?.get("aiLastOperation") ?: JsonNull)
        })
    }

    fun content(board: JsonObject, meta: BoardMeta): BoardContent {
        require(board["id"]?.jsonPrimitive?.content == meta.id) { "操作不能写入其他课表" }
        val term = board.getValue("term").jsonObject
        require(term["firstMonday"]?.jsonPrimitive?.content == meta.term.firstMonday.toString() &&
            term["totalWeeks"]?.jsonPrimitive?.int == meta.term.totalWeeks) { "AI 不能改写学期设置" }
        fun JsonObject.str(key: String, max: Int = 2000): String {
            val value = get(key)?.jsonPrimitive?.contentOrNull ?: ""
            require(value.length <= max) { "课程字段过长" }; return value
        }
        val courses = board.getValue("courses").jsonArray.map { element ->
            val c = element.jsonObject
            val credit = c["credit"]?.jsonPrimitive?.let { if (it.contentOrNull.isNullOrEmpty()) null else it.double }
            require(credit == null || (credit.isFinite() && credit in 0.0..100.0)) { "学分无效" }
            Course(c.str("id"), c.str("name", 200), c.str("teacher", 200), c.str("code"), c.str("classNo"),
                credit, c.str("note"), c["colorOverride"]?.jsonPrimitive?.intOrNull).also {
                require(it.id.isNotBlank() && it.name.isNotBlank()) { "课程名和标识不能为空" }
                require(it.colorOverride == null || it.colorOverride in 0..15) { "课程颜色无效" }
            }
        }
        require(courses.size <= 500 && courses.map { it.id }.distinct().size == courses.size) { "课程过多或标识重复" }
        val ids = courses.map { it.id }.toSet()
        val sessions = board.getValue("sessions").jsonArray.map { element ->
            val s = element.jsonObject
            CourseSession(s.str("id"), s.str("courseId"), s.getValue("day").jsonPrimitive.int,
                s.getValue("start").jsonPrimitive.int, s.getValue("end").jsonPrimitive.int,
                s.getValue("weeks").jsonArray.map { it.jsonPrimitive.int }.toSet(), s.str("room", 200)).also {
                require(it.id.isNotBlank() && it.courseId in ids && it.dayOfWeek in 1..7 &&
                    it.startPeriod in 1..meta.periodSchedule.times.size && it.endPeriod in it.startPeriod..meta.periodSchedule.times.size &&
                    it.weeks.isNotEmpty() && it.weeks.all { w -> w in 1..meta.term.totalWeeks }) { "上课时间无效，本次未保存" }
            }
        }
        require(sessions.size <= 2000 && sessions.map { it.id }.distinct().size == sessions.size) { "时间段过多或标识重复" }
        val unplaced = board.getValue("unplaced").jsonArray.map { json.decodeFromJsonElement<UnplacedCourse>(it) }
        require(unplaced.size <= 500) { "未排课数量过多" }
        val audit = buildJsonObject {
            put("aiRequestIds", board["aiRequestIds"] ?: JsonArray(emptyList()))
            put("aiLastOperation", board["aiLastOperation"] ?: JsonNull)
        }
        require(audit.getValue("aiRequestIds").jsonArray.size <= 100 && audit.toString().length <= 2_000_000) { "操作记录过大" }
        return BoardContent(courses, sessions, unplaced, audit)
    }
}
