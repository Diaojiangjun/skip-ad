package com.skipad.data.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则源的容错加载语义。
 *
 * 这条语义来自真实故障：某个规则源缓存损坏时，异常一路冒到无障碍服务的协程，
 * 把进程干掉，系统随后把无障碍服务记成「无法运行」且不再重连。
 * 因此「单个源坏掉」必须退化成「跳过该源」，而不是「整体失败」。
 */
class CollectSafelyTest {

    @Test
    fun `全部成功时按顺序收集`() {
        val result = collectSafely(items = listOf("a", "b", "c")) { listOf(it) }
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `单项失败被跳过且不影响其他项`() {
        val failed = ArrayList<String>()
        val result = collectSafely(
            items = listOf("ok1", "bad", "ok2"),
            onError = { item, _ -> failed += item },
        ) { item ->
            if (item == "bad") error("解析失败") else listOf(item)
        }
        assertEquals(listOf("ok1", "ok2"), result)
        assertEquals(listOf("bad"), failed)
    }

    @Test
    fun `全部失败时返回空列表而不是抛异常`() {
        val result = collectSafely<String, String>(items = listOf("a", "b")) {
            error("都不行")
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `单项返回空集合也算成功`() {
        val result = collectSafely(items = listOf("a", "b")) {
            if (it == "a") emptyList() else listOf(it)
        }
        assertEquals(listOf("b"), result)
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(collectSafely<String, String>(items = emptyList()) { listOf(it) }.isEmpty())
    }
}
