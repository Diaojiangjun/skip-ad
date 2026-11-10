package com.skipad.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置的读取与默认值。
 *
 * 这段逻辑原先只有「跑起来才知道对不对」这一条验证途径，
 * 而它恰恰是历史事故的高发区：配置文件写坏后每次读取都抛异常，
 * 导致应用反复崩溃、无障碍服务被系统判为「无法运行」。
 */
class SettingsReadTest {

    @Test
    fun `空配置时全部落到默认值`() {
        val s = readSettings(mutablePreferencesOf())

        assertFalse("保护默认关闭", s.protectionEnabled)
        assertTrue("开机自启默认开启", s.bootStartEnabled)
        assertTrue("跳过功能默认开启", s.skipFeatureEnabled)
        assertFalse("收紧模式默认关闭", s.skipOnlyConfiguredApps)
        assertFalse("引导默认未完成", s.onboardingDone)
        assertFalse("静默通知默认关闭", s.quietNotification)
        assertEquals(BlockMode.BLOCK, s.blockMode)
        assertEquals(UpstreamDns.ALIYUN, s.upstreamDns)
        assertEquals(0, s.loadedRuleCount)
        assertEquals("", s.skipSubscriptionUrl)
        assertTrue("白名单默认为空", s.allowList.isEmpty())
        assertTrue("排除应用默认为空", s.excludedPackages.isEmpty())
    }

    @Test
    fun `默认启用的规则源来自各自的默认值表`() {
        val s = readSettings(mutablePreferencesOf())
        // 两套规则源互不相干：DNS 拦截源与开屏跳过源
        assertEquals(FilterSourceDefaults.ids, s.enabledFilterIds)
        assertEquals(SkipSourceDefaults.ids, s.enabledSkipSourceIds)
        assertTrue("默认应至少包含一个 DNS 规则源", s.enabledFilterIds.isNotEmpty())
        assertTrue("默认应至少包含一个跳过规则源", s.enabledSkipSourceIds.isNotEmpty())
    }

    @Test
    fun `已保存的值能被正确读出`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("protection_enabled") to true,
            booleanPreferencesKey("onboarding_done") to true,
            booleanPreferencesKey("quiet_notification") to true,
            stringPreferencesKey("upstream_dns") to UpstreamDns.QUAD9,
            intPreferencesKey("loaded_rule_count") to 278033,
            stringSetPreferencesKey("allow_list") to setOf("a.example.com"),
        )
        val s = readSettings(prefs)

        assertTrue(s.protectionEnabled)
        assertTrue(s.onboardingDone)
        assertTrue(s.quietNotification)
        assertEquals(UpstreamDns.QUAD9, s.upstreamDns)
        assertEquals(278033, s.loadedRuleCount)
        assertEquals(setOf("a.example.com"), s.allowList)
    }

    @Test
    fun `非法的拦截模式回退到默认而不是抛异常`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("block_mode") to "不存在的模式",
        )
        assertEquals(BlockMode.BLOCK, readSettings(prefs).blockMode)
    }

    @Test
    fun `合法的拦截模式能被读出`() {
        listOf(BlockMode.NXDOMAIN, BlockMode.MONITOR).forEach { mode ->
            val prefs = mutablePreferencesOf(stringPreferencesKey("block_mode") to mode.name)
            assertEquals(mode, readSettings(prefs).blockMode)
        }
    }

    @Test
    fun `自定义跳过规则以字符串集合形式保存`() {
        val prefs = mutablePreferencesOf(
            stringSetPreferencesKey("custom_skip_rules") to setOf("""{"text":"跳过"}"""),
        )
        assertEquals(setOf("""{"text":"跳过"}"""), readSettings(prefs).customSkipRules)
    }
}
