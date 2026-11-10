package com.skipad.core.skip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击去重与冷却控制测试。
 *
 * 这是防止「反复点击同一位置」的关键保护。
 */
class ClickGuardTest {

    @Test
    fun `同一目标只允许点击一次`() {
        val guard = ClickGuard()
        assertTrue(guard.canClick("key1", "rule1", 1000L))
        guard.markClicked("key1", "rule1", 1000L)

        assertFalse(guard.canClick("key1", "rule1", 1100L))
    }

    @Test
    fun `不同目标可以分别点击`() {
        val guard = ClickGuard()
        assertTrue(guard.canClick("key1", "rule1", 1000L))
        guard.markClicked("key1", "rule1", 1000L)

        assertTrue(guard.canClick("key2", "rule1", 1000L))
    }

    @Test
    fun `冷却期内禁止再次触发同一规则`() {
        val guard = ClickGuard()
        guard.setCooldown("rule1", 3000L)

        assertTrue(guard.canClick("key1", "rule1", 1000L))
        guard.markClicked("key1", "rule1", 1000L)

        // 冷却期内，即使目标是新的也禁止
        assertFalse(guard.canClick("key2", "rule1", 2000L))
        // 冷却期过后放行
        assertTrue(guard.canClick("key2", "rule1", 5000L))
    }

    @Test
    fun `不同规则的冷却互不影响`() {
        val guard = ClickGuard()
        guard.setCooldown("rule1", 3000L)

        assertTrue(guard.canClick("key1", "rule1", 1000L))
        guard.markClicked("key1", "rule1", 1000L)

        // 另一条规则不受 rule1 的冷却限制（目标也不同）
        assertTrue(guard.canClick("key2", "rule2", 1100L))
    }

    @Test
    fun `切换应用后清空去重记录`() {
        val guard = ClickGuard()
        guard.onPackageChanged("com.example")

        assertTrue(guard.canClick("key1", "rule1", 1000L))
        guard.markClicked("key1", "rule1", 1000L)
        assertFalse(guard.canClick("key1", "rule1", 1100L))

        // 切换到另一个应用
        guard.onPackageChanged("com.other")
        assertTrue(guard.canClick("key1", "rule1", 1200L))
    }

    @Test
    fun `同一应用重复进入不重置记录`() {
        val guard = ClickGuard()
        guard.onPackageChanged("com.example")
        guard.markClicked("key1", "rule1", 1000L)

        // 相同包名再次进入，不应清空
        guard.onPackageChanged("com.example")
        assertFalse(guard.canClick("key1", "rule1", 1200L))
    }

    @Test
    fun `reset 清空全部状态`() {
        val guard = ClickGuard()
        guard.setCooldown("rule1", 3000L)
        guard.markClicked("key1", "rule1", 1000L)

        guard.reset()

        assertTrue(guard.canClick("key1", "rule1", 1100L))
    }
}
