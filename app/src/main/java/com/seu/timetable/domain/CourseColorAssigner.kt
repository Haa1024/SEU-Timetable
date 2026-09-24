package com.seu.timetable.domain

/**
 * 课程配色槽位分配（仅产出槽位序号，不含颜色本身——颜色由 UI 负责）。
 *
 * 为何不采用设计规范中的「5 组色 + `已分配课数 % 5` + 必须持久化」：
 *
 * 1. 5 色不足。规范自身的示例即有 9 门课，`% 5` 必然重复；渲染出的设计稿即为佐证——
 *    第 3-4 节那行连续三个绿块（计算机网络 / 数据结构与算法 / 英语视听说），
 *    完全无法区分是不同的课程。
 * 2. `% 5` 依赖"分配顺序"，因此规范才不得不要求持久化 `colorIndex`。
 *    顺序一旦变化，颜色即全部改变，清除数据或更换设备也会重新排布。
 *    设计稿本身即违反了自定规则：「数据结构与算法」在周一列为蓝、周三列为绿。
 *
 * 本实现采用：16 色 + 按课程名散列后线性探测。
 * - 课程种类 ≤ 16 时，保证不同课程互不撞色；
 * - 结果仅取决于"课程名集合"，与上游返回顺序无关 → 无需持久化亦保持不变；
 * - 用户手动指定的 [Course.colorOverride] 优先，且先占住槽位，
 *   这样用户固定的颜色不会被自动分配抢占。
 */
class CourseColorAssigner(courses: List<Course>) {

    private val slots: Map<String, Int> = buildMap {
        val used = HashSet<Int>()

        // 1) 用户钉死的先占位
        courses.forEach { c ->
            val override = c.colorOverride ?: return@forEach
            val slot = override.mod(PALETTE_SIZE)
            used += slot
            put(c.id, slot)
        }

        // 2) 其余按课程名排序后依次探测（排序保证与上游顺序无关）
        courses.asSequence()
            .filter { it.colorOverride == null }
            .sortedWith(compareBy({ it.name }, { it.id }))
            .forEach { c ->
                var slot = startSlot(c.name)
                var probe = 0
                while (slot in used && probe < PALETTE_SIZE) {
                    slot = (slot + 1) % PALETTE_SIZE
                    probe++
                }
                used += slot
                put(c.id, slot)
            }
    }

    /** 课程 → 槽位（0..15）。查不到时退回 0，不抛异常。 */
    operator fun get(courseId: String): Int = slots[courseId] ?: 0

    /** 槽位是否互不重复（≤ 16 门课时应恒为 true，测试会查） */
    fun hasCollision(): Boolean = slots.values.toSet().size != slots.size

    /**
     * 下一个未被占用的槽位。供"新增课程"作为默认颜色使用。
     *
     * 为何需要此方法：新增课程时尚无 id，[get] 无法查得（会回退为 0），
     * 但编辑页仍需先显示一个已选中的颜色圆点。选择"第一个空槽"而非固定为 0，
     * 是为了使预览色接近保存后将分配到的颜色——避免出现
     * "编辑页显示红色、保存后变为蓝色"这种令用户误以为未保存的错觉。
     *
     * 此值仅为预览：用户未手动点击颜色时保存的是 `colorOverride = null`，
     * 最终颜色仍由本类按课程名重新分配。故此处不追求逐位一致。
     */
    fun nextFreeSlot(): Int {
        val used = slots.values.toSet()
        for (i in 0 until PALETTE_SIZE) if (i !in used) return i
        return slots.size % PALETTE_SIZE
    }

    /** 已分配槽位的数量（UI 需按槽位做处理时使用）。 */
    fun slotCount(): Int = slots.size

    companion object {
        const val PALETTE_SIZE = 16

        private fun startSlot(name: String): Int {
            var h = 0
            for (ch in name) h = h * 31 + ch.code
            return h.mod(PALETTE_SIZE)
        }
    }
}
