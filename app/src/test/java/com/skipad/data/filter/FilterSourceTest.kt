package com.skipad.data.filter

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则源配置测试。
 *
 * 背景：真实环境中曾出现过规则源仓库已删除（404）却仍被写进配置的情况，
 * 导致每次更新都要等待超时。这里用静态检查约束配置的完整性。
 */
class FilterSourceTest {

    @Test
    fun `每个规则源至少有一个地址`() {
        FilterSource.entries.forEach { source ->
            assertTrue(
                "规则源 ${source.id} 没有配置任何地址",
                source.urls.isNotEmpty()
            )
        }
    }

    @Test
    fun `所有地址均为合法 HTTPS 链接`() {
        FilterSource.entries.forEach { source ->
            source.urls.forEach { url ->
                assertTrue(
                    "规则源 ${source.id} 的地址不是 HTTPS: $url",
                    url.startsWith("https://")
                )
                assertTrue(
                    "规则源 ${source.id} 的地址格式非法: $url",
                    url.matches(Regex("https://[\\w.\\-]+(/[\\w.\\-@%/]+)*"))
                )
            }
        }
    }

    @Test
    fun `地址中不含空格或中文`() {
        FilterSource.entries.forEach { source ->
            source.urls.forEach { url ->
                assertTrue(
                    "规则源 ${source.id} 的地址含非法字符: $url",
                    url.none { it.isWhitespace() || it.code > 127 }
                )
            }
        }
    }

    @Test
    fun `主要规则源配置了镜像备选`() {
        // 受限网络下主地址可能不可达，至少要有备选镜像
        val needMirror = setOf("steven_black", "android_ad", "dangling_dns")
        needMirror.forEach { id ->
            val source = FilterSource.fromId(id)
            assertTrue("规则源 $id 不存在", source != null)
            assertTrue(
                "规则源 $id 应配置至少 ${2} 个备选地址，实际 ${source!!.urls.size}",
                source.urls.size >= 2
            )
        }
    }

    @Test
    fun `同一规则源的地址不重复`() {
        FilterSource.entries.forEach { source ->
            assertTrue(
                "规则源 ${source.id} 存在重复地址",
                source.urls.distinct().size == source.urls.size
            )
        }
    }

    @Test
    fun `规则源 id 唯一且可反查`() {
        val ids = FilterSource.entries.map { it.id }
        assertTrue("规则源 id 存在重复", ids.size == ids.distinct().size)
        FilterSource.entries.forEach { source ->
            assertTrue(
                "规则源 ${source.id} 无法通过 id 反查",
                FilterSource.fromId(source.id) == source
            )
        }
    }

    @Test
    fun `默认启用的规则源均存在`() {
        com.skipad.data.prefs.FilterSourceDefaults.ids.forEach { id ->
            assertTrue(
                "默认启用的规则源 $id 不在枚举中",
                FilterSource.fromId(id) != null
            )
        }
    }
}
