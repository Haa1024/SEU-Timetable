package com.seu.timetable.widget

import android.os.Build
import java.util.Locale

/**
 * 设备所属的厂商族。
 *
 * **它只允许影响两件事**：降级引导的文案与专属入口（哪家的路径叫什么、有没有可跳转的按钮），
 * 以及要不要带厂商专属的跳转参数。**不得**用它断言「这台机器支持 / 不支持一键添加」——
 * 那是行为探测的结论（见 [WidgetPin.boundCount] 与 [WidgetPin.pinnedCount]）。
 *
 * 之所以要立这条规矩，有设备级证据：同为 ColorOS，社区口径是「会弹确认框」，而本机实测是
 * 「确认页起了却从不置前、也不发回调」；同一家不同版本的行为都会漂移，厂商名只能用来选文案。
 */
enum class WidgetVendor {

    /** 小米 / 红米 / POCO（MIUI、HyperOS）。 */
    XIAOMI,

    /** OPPO / 一加 / realme（ColorOS）。 */
    OPPO,

    /** vivo / iQOO（OriginOS、Funtouch OS）。 */
    VIVO,

    /** 荣耀（MagicOS、MagicUI）。 */
    HONOR,

    /**
     * 未识别、空串或 `null`。**华为现役机型也归这里**：能走到本段逻辑的华为设备是鸿蒙 5.0
     * 以下的 EMUI 老系统，按「不适配老系统」的口径走通用引导，是有意归类而非遗漏。
     */
    OTHER;

    /** vivo 桌面提供了原子组件库的跳转入口。 */
    val showsGalleryJump: Boolean get() = this == VIVO

    /** 小米存在「创建桌面快捷方式」权限开关，关着时 pin 会静默失败（社区实测口径，非官方文档）。 */
    val showsShortcutHint: Boolean get() = this == XIAOMI

    companion object {

        /** 识别失败时的归属。 */
        val FALLBACK = OTHER

        /**
         * 算作「最新系统」的 SDK 下限：Android 14（API 34）。
         *
         * 依据是各家的产品线对应关系：HyperOS 1 / ColorOS 14 / OriginOS 4 / MagicOS 8 起才是
         * Android 14+；MIUI 13–14（A12/A13）、realme UI 3–4 这类老系统不做厂商专属适配，
         * 一律落在通用引导上。写成常量是为了将来上调时只改一处。
         */
        const val MODERN_SDK_FLOOR = 34

        /** 按当前设备识别。 */
        fun current(): WidgetVendor = detect(Build.MANUFACTURER, Build.BRAND)

        /** [sdkInt] 是否达到「最新系统」门槛。 */
        fun isModern(sdkInt: Int): Boolean = sdkInt >= MODERN_SDK_FLOOR

        /**
         * 小米「打开小部件中心详情页」那组 extras 的唯一判定出处。
         *
         * 单独抽出来是为了让「荣耀一条专属能力都不该触发」这类不变量可以被断言，
         * 也避免判断散落到请求与文案两处后各自漂移。
         */
        fun usesDetailPageExtras(
            vendor: WidgetVendor,
            modern: Boolean,
            detailPageSupported: Boolean,
        ): Boolean = vendor == XIAOMI && modern && detailPageSupported

        /**
         * 识别厂商族。
         *
         * 厂商名要分「精确」与「包含」两档匹配：小米的旧品牌名就是两个字 `Mi`，只能用精确匹配
         * 才安全——`mi` 作为子串会误伤 `Micromax`、`Microsoft` 这类跑着接近原生桌面的厂商，
         * 把它们当成小米会给出完全错误的引导。`Xiaomi` / `Redmi` / `POCO` 足够独特，才用包含匹配。
         *
         * @param manufacturer `Build.MANUFACTURER`，允许 `null`。
         * @param brand `Build.BRAND`，允许 `null`。
         */
        fun detect(manufacturer: String?, brand: String?): WidgetVendor {
            val fields = listOf(normalize(manufacturer), normalize(brand))
            // 顺序即优先级：两个字段同时命中不同厂商时按声明顺序取第一个。
            // 实测不会发生，但把规则写死比"看运气"好。
            for (vendor in listOf(XIAOMI, OPPO, VIVO, HONOR)) {
                if (fields.any { vendor.matches(it) }) return vendor
            }
            return FALLBACK
        }

        private fun normalize(value: String?): String =
            value?.trim()?.lowercase(Locale.ROOT) ?: ""
    }

    /** 只用于**精确**匹配的整串品牌名：短到会误伤别家的那些。 */
    private fun exactTokens(): List<String> = if (this == XIAOMI) listOf("mi") else emptyList()

    /** 用于**包含**匹配的品牌名：都足够独特，不会出现在别家厂商名里。 */
    private fun containsTokens(): List<String> = when (this) {
        XIAOMI -> listOf("xiaomi", "redmi", "poco")
        OPPO -> listOf("oppo", "oneplus", "realme")
        VIVO -> listOf("vivo", "iqoo")
        HONOR -> listOf("honor", "hihonor")
        OTHER -> emptyList()
    }

    private fun matches(value: String): Boolean {
        if (value.isEmpty()) return false
        return value in exactTokens() || containsTokens().any { value.contains(it) }
    }
}
