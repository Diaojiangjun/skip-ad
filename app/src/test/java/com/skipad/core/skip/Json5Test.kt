package com.skipad.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON5 → 严格 JSON 转换测试。
 *
 * GKD 订阅文件大量使用无引号键名、单引号字符串与注释，
 * 这个转换是能否读取订阅的前提。
 */
class Json5Test {

    @Test
    fun `无引号键名被正确加引号`() {
        val json = Json5.toStrictJson("{id:1,name:'测试',enable:true}")
        val obj = org.json.JSONObject(json)
        assertEquals(1, obj.getInt("id"))
        assertEquals("测试", obj.getString("name"))
        assertTrue(obj.getBoolean("enable"))
    }

    @Test
    fun `单引号字符串被转换为双引号`() {
        val json = Json5.toStrictJson("{a:'hello world'}")
        assertEquals("hello world", org.json.JSONObject(json).getString("a"))
    }

    @Test
    fun `单引号字符串内的双引号被转义`() {
        val source = """{matches:'[text="跳过"]'}"""
        val obj = org.json.JSONObject(Json5.toStrictJson(source))
        assertEquals("[text=\"跳过\"]", obj.getString("matches"))
    }

    @Test
    fun `行注释与块注释被移除`() {
        val source = """
            {
              // 这是行注释
              a: 1, /* 块注释 */
              b: 2
            }
        """.trimIndent()
        val obj = org.json.JSONObject(Json5.toStrictJson(source))
        assertEquals(1, obj.getInt("a"))
        assertEquals(2, obj.getInt("b"))
    }

    @Test
    fun `尾随逗号被移除`() {
        val obj = org.json.JSONObject(
            Json5.toStrictJson("{a:1,b:2,}")
        )
        assertEquals(2, obj.length())

        val array = org.json.JSONArray(Json5.toStrictJson("[1,2,3,]"))
        assertEquals(3, array.length())
    }

    @Test
    fun `数组与嵌套对象`() {
        val source = "{apps:[{id:'com.a',groups:[{key:0,rules:[{matches:'[text=\"跳过\"]'}]}]}]}"
        val root = org.json.JSONObject(Json5.toStrictJson(source))
        val apps = root.getJSONArray("apps")
        val app = apps.getJSONObject(0)
        assertEquals("com.a", app.getString("id"))
        assertEquals(
            "[text=\"跳过\"]",
            app.getJSONArray("groups").getJSONObject(0)
                .getJSONArray("rules").getJSONObject(0)
                .getString("matches")
        )
    }

    @Test
    fun `保留字符串中的注释符号与逗号`() {
        val source = """{text:'a // not comment, still string'}"""
        val obj = org.json.JSONObject(Json5.toStrictJson(source))
        assertEquals("a // not comment, still string", obj.getString("text"))
    }

    @Test
    fun `URL 中的双斜杠不被当作注释`() {
        val source = """{url:'https://example.com/a'}"""
        val obj = org.json.JSONObject(Json5.toStrictJson(source))
        assertEquals("https://example.com/a", obj.getString("url"))
    }

    @Test
    fun `单引号内的转义单引号`() {
        val source = """{t:'it\'s ok'}"""
        val obj = org.json.JSONObject(Json5.toStrictJson(source))
        assertEquals("it's ok", obj.getString("t"))
    }
}
