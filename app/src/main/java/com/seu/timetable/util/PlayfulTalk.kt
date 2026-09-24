package com.seu.timetable.util

import android.content.Context
import com.seu.timetable.R

/**
 * 空态文案：俏皮话 + 颜文字，多条轮换。
 *
 * 「轮换」用**时间分桶**而不是随机：每 [WINDOW_MINUTES] 分钟换一条。这样同一时刻
 * 今日页与桌面小组件取到的是同一条文案（两处不会各说各话），也不会因为每次重绘
 * 都随机而闪来闪去。窗口取 30 分钟，与小组件的最小刷新周期一致，
 * 保证一条文案至少能被完整看到一次。
 *
 * 适用范围仅限空态：今天没课 / 周末 / 今天的课已上完 / 还没有课表。
 * 表单校验与错误提示仍保持书面语——那里需要的是准确，不是可爱。
 *
 * 每条文案的格式是「颜文字 + 换行 + 正文」，见 res/values/strings.xml。
 */
object PlayfulTalk {

    /** 轮换窗口（分钟）。 */
    private const val WINDOW_MINUTES = 30

    enum class Mood(val arrayRes: Int) {
        /** 工作日一整天没有课 */
        NO_CLASS(R.array.playful_no_class),

        /** 周末没有课 */
        WEEKEND(R.array.playful_weekend),

        /** 今天的课已经全部上完 */
        ALL_DONE(R.array.playful_all_done),

        /** 本地还没有课表 */
        NO_BOARD(R.array.playful_no_board),
    }

    /** 当前时段应显示的整条文案（含颜文字与换行）。小组件整体居中显示，直接用这个。 */
    fun line(context: Context, mood: Mood): String {
        val pool = context.resources.getStringArray(mood.arrayRes)
        if (pool.isEmpty()) return ""
        return pool[slot(pool.size)]
    }

    /** 拆成「颜文字」与「正文」两行，供字号/颜色不同的界面分别排版（如今日页）。 */
    fun split(context: Context, mood: Mood): Pair<String, String> {
        val parts = line(context, mood).split('\n', limit = 2)
        return if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            "" to (parts.firstOrNull() ?: "")
        }
    }

    private fun slot(size: Int): Int =
        ((System.currentTimeMillis() / 60_000L / WINDOW_MINUTES) % size).toInt()
}
