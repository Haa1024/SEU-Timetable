package com.seu.timetable.widget

import android.content.Context
import android.net.Uri

/**
 * 小米官方「系统能力探测」接口的薄封装：问系统「这台机器有没有小部件中心详情页」。
 *
 * 官方文档明确写着「部分机型支持小米 Widget（含曝光刷新等特性），但**不支持调起小米 Widget
 * 详情页**；这部分机型添加小部件的方式与旧版系统一致」。既然系统愿意回答，就不该拍脑袋决定
 * 要不要给 `requestPinAppWidget` 带上 `addType` 那组 extras。带上却不被支持，最坏结果是
 * 退回标准确认框（故不会更差），但既然能问，就问。
 *
 * 三条硬约束：
 *  1. **只在小米机型上发起调用**：别家没有这个 Provider，跨进程 call 白花代价还可能触发未知行为；
 *  2. **失败一律当「不支持」**：Provider 不存在、抛异常、返回 null、键缺失，全部按不支持处理，
 *     探测失败绝不能让 pin 崩掉或卡住；
 *  3. **只缓存成功的回答**：一次异常（例如进程刚起、Provider 尚未就绪）不该被记成「永远不支持」。
 */
object WidgetVendorProbe {

    /** 小米小部件系统能力的 ContentProvider 授权名（官方文档）。 */
    private const val AUTHORITY = "content://com.miui.personalassistant.widget.external"

    /** 问「这台机器是否支持调起小米 Widget 详情页」——决定 pin 带不带那组 extras 的就是它。 */
    private const val METHOD_DETAIL_PAGE_SUPPORTED = "isMiuiWidgetDetailPageSupported"

    @Volatile
    private var detailPageSupported: Boolean? = null

    /** 这台机器能不能调起「小部件中心详情页」。非小米、探测失败一律 `false`。 */
    fun detailPageSupported(context: Context, vendor: WidgetVendor): Boolean {
        if (vendor != WidgetVendor.XIAOMI) return false
        detailPageSupported?.let { return it }
        val answer = probe(context, METHOD_DETAIL_PAGE_SUPPORTED) ?: return false
        detailPageSupported = answer
        return answer
    }

    /** @return 系统给出的回答；**没有回答**（异常 / 返回 null / 键缺失）时返回 `null`。 */
    private fun probe(context: Context, method: String): Boolean? = runCatching {
        val result = context.contentResolver.call(Uri.parse(AUTHORITY), method, null, null)
        if (result == null || !result.containsKey(method)) null else result.getBoolean(method, false)
    }.getOrNull()
}
