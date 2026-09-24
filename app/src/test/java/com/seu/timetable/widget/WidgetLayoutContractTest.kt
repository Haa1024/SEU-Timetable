package com.seu.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 两个小组件布局与渲染代码之间的契约。
 *
 * 两个尺寸共用同一个渲染器（[BaseTodayWidget]），布局却是两份手写的 XML，这正是最容易
 * 悄悄错开的地方：某个 id 在其中一个布局里少写一个，渲染到那个尺寸才会炸；改了布局里的
 * 行高却忘了同步常量，行数换算就会多算一行、最后一行被裁掉。这些错误编译期毫无痕迹，
 * 只能靠这里钉住——**改布局就必须先改这些断言**。
 */
class WidgetLayoutContractTest {

    private data class Node(
        val id: String,
        val width: String,
        val height: String,
        val marginStart: String,
        val visibility: String,
    )

    private fun document(layout: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(layout))

    private fun elements(doc: Document): List<Element> {
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).mapNotNull { all.item(it) as? Element }
    }

    private fun node(el: Element): Node? {
        val id = el.getAttribute("android:id")
        if (id.isBlank()) return null
        return Node(
            id = id.removePrefix("@+id/"),
            width = el.getAttribute("android:layout_width"),
            height = el.getAttribute("android:layout_height"),
            marginStart = el.getAttribute("android:layout_marginStart"),
            visibility = el.getAttribute("android:visibility"),
        )
    }

    private fun nodesById(layout: String): Map<String, Node> =
        elements(document(layout)).mapNotNull { node(it) }.associateBy { it.id }

    /** 某一行槽位的直接子**元素**（跳过 XML 里的空白文本节点） */
    private fun rowChildren(layout: String, row: Int): List<Element> {
        val doc = document(layout)
        val rowEl = elements(doc)
            .firstOrNull { it.getAttribute("android:id") == "@+id/widget_row_$row" }
            ?: error("$layout 里找不到 widget_row_$row")
        val children = rowEl.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    private fun Node.dp(): Int =
        height.removeSuffix("dp").toIntOrNull()
            ?: error("$id 的 layout_height=\"$height\" 不是固定 dp 值，行高换算会失准")

    private fun Node.intWidth(): Int =
        width.removeSuffix("dp").toIntOrNull()
            ?: error("$id 的 layout_width=\"$width\" 不是固定 dp 值")

    private fun Node.intMarginStart(): Int =
        marginStart.removeSuffix("dp").toIntOrNull()
            ?: error("$id 没有可解析的 layout_marginStart（实际为\"$marginStart\"）")

    private fun small() = nodesById("widget_today_2x2.xml")

    private fun wide() = nodesById("widget_today_4x2.xml")

    // ---------------- id 集合 ----------------

    @Test
    fun `两个布局的id集合必须完全一致`() {
        // 渲染器对两个尺寸跑同一段代码，所有 setTextViewText / setViewVisibility 都会打到
        // 两边的同名 id 上。任何一边缺一个 id，都只在那个尺寸上出错——手写 XML 时极易漏。
        assertEquals(
            "2×2 与 4×2 的 id 集合必须一致，否则共用渲染器会在某个尺寸上崩",
            wide().keys,
            small().keys,
        )
    }

    @Test
    fun `每个行槽位都齐备时刻色条课名教室四个id`() {
        listOf("widget_today_2x2.xml", "widget_today_4x2.xml").forEach { layout ->
            val nodes = nodesById(layout)
            for (n in 1..MAX_ROWS) {
                listOf(
                    "widget_row_$n", "widget_time_$n", "widget_bar_$n",
                    "widget_name_$n", "widget_room_$n",
                ).forEach { id ->
                    assertTrue("$layout 缺少 $id", nodes.containsKey(id))
                }
            }
        }
    }

    // ---------------- 行高常量与布局必须同步 ----------------

    @Test
    fun `2x2 的行高与 ROW_HEIGHT_DP_SMALL 一致`() {
        // rowCapacity() 按「可用高度 ÷ 行高」估算能放几行。常量比布局小就会多算一行，
        // 多出来的那行被容器裁掉一半，看起来像渲染坏了。
        val layout = small()
        for (n in 1..MAX_ROWS) {
            assertEquals(
                "widget_row_$n 的高度必须等于 ROW_HEIGHT_DP_SMALL",
                ROW_HEIGHT_DP_SMALL,
                layout.getValue("widget_row_$n").dp(),
            )
        }
    }

    @Test
    fun `4x2 的行高与 ROW_HEIGHT_DP 一致`() {
        val layout = wide()
        for (n in 1..MAX_ROWS) {
            assertEquals(
                "widget_row_$n 的高度必须等于 ROW_HEIGHT_DP",
                ROW_HEIGHT_DP,
                layout.getValue("widget_row_$n").dp(),
            )
        }
    }

    @Test
    fun `id 数组长度与行槽位数一致`() {
        // 渲染器按 `for (i in 0 until MAX_ROWS)` 直接下标访问这几个数组，
        // 数组短一个就是越界崩溃。
        listOf(ROW_IDS, TIME_IDS, BAR_IDS, NAME_IDS, ROOM_IDS).forEach { ids ->
            assertEquals("id 数组长度必须等于 MAX_ROWS", MAX_ROWS, ids.size)
        }
    }

    // ---------------- 2×2 的两行排版 ----------------

    @Test
    fun `2x2 的教室行缩进对齐课名`() {
        // 教室排在第二行，缩进量必须等于「时刻列 + 色条 + 两段间距」之和，
        // 也就是课名文字的起始位置；否则两行看起来会错位。
        val layout = small()
        val expected = layout.getValue("widget_time_1").intWidth() +
            layout.getValue("widget_bar_1").intMarginStart() +
            layout.getValue("widget_bar_1").intWidth() +
            layout.getValue("widget_name_1").intMarginStart()
        for (n in 1..MAX_ROWS) {
            assertEquals(
                "widget_room_$n 的缩进必须与课名起始位置对齐",
                expected,
                layout.getValue("widget_room_$n").intMarginStart(),
            )
        }
    }

    @Test
    fun `2x2 的教室行默认隐藏`() {
        // 渲染层只在 showRoom 为真时才把它置为 VISIBLE。默认 gone 的意义是：
        // 万一某次改动漏掉了这一步，用户看到的是少一行教室，而不是一个孤零零的占位符。
        val layout = small()
        for (n in 1..MAX_ROWS) {
            assertEquals(
                "widget_room_$n 应默认 gone",
                "gone",
                layout.getValue("widget_room_$n").visibility,
            )
        }
    }

    @Test
    fun `2x2 的行高给第二行文字留足空间`() {
        // 这是一行两行的真正风险：行高是写死的 34dp，上行占 18dp，剩下的才是教室文字的空间。
        // 若哪天把行高压到 30dp 而字号没变，教室那行会被父容器裁掉半截——
        // 而 gfxinfo、截图之外没有任何东西会报错。10sp 中文一行约 13dp，这里按 14dp 要余量。
        val layout = small()
        for (n in 1..MAX_ROWS) {
            val children = rowChildren("widget_today_2x2.xml", n)
            val inner = children.getOrNull(0) ?: error("widget_row_$n 缺内层容器")
            // 内层容器没有 id（不需要被渲染层引用），所以直接读属性而不是走 node()
            val innerHeight = inner.getAttribute("android:layout_height")
            val innerDp = innerHeight.removeSuffix("dp").toIntOrNull()
                ?: error("widget_row_$n 内层容器高度 \"$innerHeight\" 不是固定 dp 值")
            val slack = layout.getValue("widget_row_$n").dp() - innerDp
            assertTrue(
                "widget_row_$n 留给教室文字的高度只有 ${slack}dp，装不下一行 10sp 文字",
                slack >= 14,
            )
        }
    }

    // ---------------- 文件定位 ----------------

    private companion object {
        /** 单测的工作目录是模块目录（app/），但为了容忍不同运行方式，向上逐级找。 */
        fun file(name: String): File {
            var dir: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
            while (dir != null) {
                val candidates = listOf("app/src/main/res/layout/$name", "src/main/res/layout/$name")
                candidates.forEach { rel ->
                    val candidate = File(dir, rel)
                    if (candidate.isFile) return candidate
                }
                dir = dir.parentFile
            }
            error("找不到布局 $name（工作目录 ${System.getProperty("user.dir")}）")
        }
    }
}
