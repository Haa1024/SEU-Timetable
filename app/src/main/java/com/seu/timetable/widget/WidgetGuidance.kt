package com.seu.timetable.widget

/**
 * 「一键添加」失败后的手动引导文案。
 *
 * 独立成一个纯函数对象，有两条理由：
 *  1. 文案要按厂商分岔，而分岔规则必须与 [WidgetPin] 的行为判定同源，放在一起才看得见；
 *  2. 它是纯函数，可以被单测钉住——「荣耀不该出现小米的权限提示」这类不变量值得有断言。
 *
 * 措辞纪律：只有 ColorOS 的分组路径（两指捏合 → 卡片 → 全部卡片 → 最底部「插件」）是本机
 * 实测过的；其余厂商只给各家一致的通称（小部件中心 / 原子组件库），不编造未经验证的层级路径。
 */
object WidgetGuidance {

    /** 圈码序号。步骤数不应超过它的长度，见 [manualSteps]。 */
    private val STEP_MARKS = listOf("①", "②", "③", "④", "⑤", "⑥")

    /** vivo 手动引导里的跳转按钮文案。 */
    const val GALLERY_ACTION = "去组件库添加"

    /**
     * 手动添加的步骤。**只有** [WidgetVendor.OPPO] 那条写到了「插件」这一层，
     * 因为那是本机看过界面的；别家写到「进入组件中心」为止，剩下的交给系统自己的列表。
     */
    fun manualSteps(vendor: WidgetVendor): List<String> = buildList {
        if (vendor.showsShortcutHint) {
            add("先看一眼权限：系统设置 → 应用 → SEU 课表 → 权限，确认「创建桌面快捷方式」是开着的")
        }
        add("在桌面用两指捏合（或长按空白处），进入编辑状态")
        add(
            when (vendor) {
                WidgetVendor.VIVO -> "点「原子组件」，进入组件库"
                WidgetVendor.XIAOMI -> "点「小部件」，进入小部件中心"
                else -> "点「卡片」或「小部件」，进入组件中心"
            }
        )
        if (vendor == WidgetVendor.OPPO) {
            add("切到「全部卡片」，一直滑到最底部，点「插件」")
        }
        add("在列表里找到「SEU 课表」")
        add("选择想要的大小，把它拖到桌面上")
    }

    /** 失败弹层的正文：一句结论 + 步骤，最后一句说明这家为什么会被拒（有据可查时才写）。 */
    fun manualBody(vendor: WidgetVendor): String = buildString {
        append("这个桌面没有接受应用主动添加，请手动添加：\n\n")
        manualSteps(vendor).forEachIndexed { index, step ->
            append(STEP_MARKS.getOrElse(index) { "·" })
            append(" ")
            append(step)
            append("\n")
        }
        note(vendor)?.let {
            append("\n")
            append(it)
        }
    }

    /**
     * 该厂商被拒的已知原因，**各限一句**。
     *
     * 弹层里正文是要滚动的，写长了一屏放不下、关键步骤反而被挤到看不见的地方；
     * 展开的解释留在「使用帮助 → 桌面小组件」里，这里只点到为止。
     * 没有可靠说法的厂商返回 `null`——宁可不写，也不猜。
     */
    private fun note(vendor: WidgetVendor): String? = when (vendor) {
        WidgetVendor.OPPO ->
            "「插件」分组是所有第三方应用共用的位置，与本应用无关。"
        WidgetVendor.VIVO ->
            "vivo 桌面只接受接入其原子组件平台的应用主动添加，本应用未接入。"
        WidgetVendor.XIAOMI ->
            "它多半是「创建桌面快捷方式」权限被关掉了，打开后一键添加即可用。"
        // 荣耀与未知厂商：没查到可靠原因。空白比编造一段「可能因为…」更诚实。
        WidgetVendor.HONOR, WidgetVendor.OTHER -> null
    }
}
