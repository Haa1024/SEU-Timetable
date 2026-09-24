package com.seu.timetable.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商分诊与失败引导的护栏测试。
 *
 * 这些规则全是「看厂商名与系统版本做决定」，没有对应真机就摸不着——而一旦判错，用户看到的
 * 是一段指向错误路径的引导（例如让小米用户去 ColorOS 的「插件」分组里找），比不提示更糟。
 * 所以把它们钉在这里：**改判定就必须先改这些断言**。
 */
class WidgetVendorTest {

    // ---------------- 厂商识别 ----------------

    @Test
    fun `小米的短品牌名Mi只做精确匹配`() {
        assertEquals(WidgetVendor.XIAOMI, WidgetVendor.detect("Xiaomi", "Mi"))
        // `mi` 作为子串会误伤跑着接近原生桌面的厂商，把它们当小米会给出完全错误的引导。
        assertEquals(WidgetVendor.OTHER, WidgetVendor.detect("Micromax", "Micromax"))
        assertEquals(WidgetVendor.OTHER, WidgetVendor.detect("Microsoft", "Microsoft"))
    }

    @Test
    fun `各家的品牌名都归到正确的厂商族`() {
        assertEquals(WidgetVendor.XIAOMI, WidgetVendor.detect("Xiaomi", "Redmi"))
        assertEquals(WidgetVendor.XIAOMI, WidgetVendor.detect("Xiaomi", "POCO"))
        assertEquals(WidgetVendor.OPPO, WidgetVendor.detect("OPPO", "realme"))
        assertEquals(WidgetVendor.OPPO, WidgetVendor.detect("OnePlus", "OnePlus"))
        assertEquals(WidgetVendor.VIVO, WidgetVendor.detect("vivo", "iQOO"))
        assertEquals(WidgetVendor.HONOR, WidgetVendor.detect("HONOR", "HONOR"))
        // 华为现役机型跑的是鸿蒙，走不到这里；能走到的是 EMUI 老系统，按通用引导处理。
        assertEquals(WidgetVendor.OTHER, WidgetVendor.detect("HUAWEI", "HUAWEI"))
    }

    @Test
    fun `大小写与首尾空白不影响识别`() {
        assertEquals(WidgetVendor.OPPO, WidgetVendor.detect("  rEaLmE ", null))
    }

    @Test
    fun `manufacturer与brand同时命中时按声明顺序取先声明的那家`() {
        assertEquals(WidgetVendor.XIAOMI, WidgetVendor.detect("Xiaomi", "OPPO"))
    }

    @Test
    fun `字段为空或厂商未知都归入通用引导`() {
        assertEquals(WidgetVendor.OTHER, WidgetVendor.detect(null, null))
        assertEquals(WidgetVendor.OTHER, WidgetVendor.detect("", "   "))
    }

    // ---------------- 小米详情页 extras 的判定 ----------------

    @Test
    fun `详情页extras必须三个条件同时成立`() {
        assertTrue(
            WidgetVendor.usesDetailPageExtras(
                WidgetVendor.XIAOMI, modern = true, detailPageSupported = true,
            )
        )
        assertFalse(
            WidgetVendor.usesDetailPageExtras(
                WidgetVendor.XIAOMI, modern = false, detailPageSupported = true,
            )
        )
        assertFalse(
            WidgetVendor.usesDetailPageExtras(
                WidgetVendor.XIAOMI, modern = true, detailPageSupported = false,
            )
        )
    }

    @Test
    fun `别家即便条件都成立也不能带上小米那组extras`() {
        listOf(WidgetVendor.OPPO, WidgetVendor.VIVO, WidgetVendor.HONOR, WidgetVendor.OTHER)
            .forEach { vendor ->
                assertFalse(
                    vendor.name,
                    WidgetVendor.usesDetailPageExtras(vendor, modern = true, detailPageSupported = true),
                )
            }
    }

    @Test
    fun `最新系统的门槛落在Android14`() {
        assertFalse(WidgetVendor.isModern(33))
        assertTrue(WidgetVendor.isModern(WidgetVendor.MODERN_SDK_FLOOR))
        assertTrue(WidgetVendor.isModern(35))
    }

    @Test
    fun `厂商专属入口各自归位`() {
        assertTrue(WidgetVendor.XIAOMI.showsShortcutHint)
        assertFalse(WidgetVendor.OPPO.showsShortcutHint)
        assertTrue(WidgetVendor.VIVO.showsGalleryJump)
        assertFalse(WidgetVendor.XIAOMI.showsGalleryJump)
        assertFalse(WidgetVendor.OTHER.showsShortcutHint)
        assertFalse(WidgetVendor.OTHER.showsGalleryJump)
    }

    // ---------------- 失败引导的文案 ----------------

    @Test
    fun `只有OPPO提插件分组只有小米提权限`() {
        assertTrue(WidgetGuidance.manualSteps(WidgetVendor.OPPO).any { it.contains("插件") })
        assertFalse(WidgetGuidance.manualSteps(WidgetVendor.XIAOMI).any { it.contains("插件") })
        assertTrue(
            WidgetGuidance.manualSteps(WidgetVendor.XIAOMI).any { it.contains("创建桌面快捷方式") }
        )
        assertFalse(
            WidgetGuidance.manualSteps(WidgetVendor.OPPO).any { it.contains("创建桌面快捷方式") }
        )
    }

    @Test
    fun `vivo的步骤说的是原子组件库`() {
        assertTrue(WidgetGuidance.manualSteps(WidgetVendor.VIVO).any { it.contains("原子组件") })
        assertFalse(WidgetGuidance.manualSteps(WidgetVendor.OPPO).any { it.contains("原子组件") })
    }

    @Test
    fun `步骤数不超过圈码容量`() {
        // 圈码只有 6 个，超了就会退化成「·」。步骤变多说明这段引导该拆了，
        // 不该在界面上悄悄降级——所以把它钉住。
        WidgetVendor.entries.forEach { vendor ->
            assertTrue(vendor.name, WidgetGuidance.manualSteps(vendor).size <= 6)
        }
    }

    @Test
    fun `每家的正文都把该家的步骤一条不落地写进去`() {
        WidgetVendor.entries.forEach { vendor ->
            val body = WidgetGuidance.manualBody(vendor)
            WidgetGuidance.manualSteps(vendor).forEach { step ->
                assertTrue("$vendor 的引导正文漏了：$step", body.contains(step))
            }
        }
    }

    @Test
    fun `没有可靠原因的厂商不写原因也不摘别家的说法`() {
        val body = WidgetGuidance.manualBody(WidgetVendor.HONOR)
        assertFalse(body.contains("原子组件"))
        assertFalse(body.contains("插件"))
        assertFalse(body.contains("创建桌面快捷方式"))
    }

    // ---------------- vivo 组件库跳转 ----------------

    @Test
    fun `vivo组件库跳转URI带上包名与组件类名且不做编码`() {
        val uri = WidgetPin.galleryUri(
            "com.seu.timetable",
            "com.seu.timetable.widget.TodayWidgetSmall",
        )
        assertTrue(uri, uri.startsWith("vivo://com.bbk.launcher2/origin"))
        assertTrue(uri, uri.contains("pkg=com.seu.timetable"))
        assertTrue(uri, uri.contains("classname=com.seu.timetable.widget.TodayWidgetSmall"))
        // 包名与类名只含字母数字与点下划线，编码反而可能让对方解析失败。
        assertFalse(uri, uri.contains("%"))
    }
}
