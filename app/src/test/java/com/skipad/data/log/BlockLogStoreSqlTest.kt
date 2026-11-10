package com.skipad.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQL 语句回归测试。
 *
 * 背景：真实故障中，建表后的索引语句写成了
 * `CREATE INDEX idx_blocked_domains last_seen ON ...`（索引名后多了一个标识符），
 * 导致 `near "last_seen": syntax error`，数据库无法创建，
 * 进而在 ViewModel 初始化阶段抛出 FATAL EXCEPTION 导致应用无法启动。
 *
 * 本测试用真实 SQLite 引擎直接执行生产代码中的 SQL 常量，
 * 确保同类语法错误无法再进入构建产物。
 */
class BlockLogStoreSqlTest {

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:")

    /**
     * 执行生产代码中的建表与建索引语句，验证语法合法。
     */
    @Test
    fun `建表与建索引语句语法合法`() {
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(BlockLogStore.CREATE_TABLE_SQL)
                statement.executeUpdate(BlockLogStore.CREATE_INDEX_SQL)
                statement.executeUpdate(BlockLogStore.CREATE_DAILY_TABLE_SQL)
            }
        }
    }

    /**
     * 日汇总表的建表与累加语句语法合法。
     */
    @Test
    fun `日汇总表语句语法合法且可累加`() {
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(BlockLogStore.CREATE_DAILY_TABLE_SQL)
            }

            val upsert = connection.prepareStatement(BlockLogStore.UPSERT_DAILY_SQL)

            // 同一天累加两次
            upsert.setString(1, "20000")
            upsert.setString(2, "1")
            upsert.executeUpdate()
            upsert.setString(1, "20000")
            upsert.setString(2, "1")
            upsert.executeUpdate()

            connection.prepareStatement(
                "SELECT SUM(count) FROM ${BlockLogStore.DAILY_TABLE} WHERE day = ?"
            ).use { statement ->
                statement.setString(1, "20000")
                val rs = statement.executeQuery()
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
        }
    }

    /**
     * 「今日拦截」取自日汇总表，不受历史累计计数影响。
     *
     * 主表记录的是域名累计命中次数：某域名昨天命中 10 次、今天 1 次时 count = 11。
     * 若按 `last_seen >= 今日零点` 从主表求和，今日统计会错误地显示 11。
     */
    @Test
    fun `今日统计只计入当天的命中次数`() {
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(BlockLogStore.CREATE_TABLE_SQL)
                statement.executeUpdate(BlockLogStore.CREATE_DAILY_TABLE_SQL)
            }

            val dayKey = 20_000L
            val yesterdayKey = dayKey - 1

            // 昨天该域名命中 10 次
            applyDaily(connection, dayKey = yesterdayKey, increment = 10)
            // 今天命中 1 次
            applyDaily(connection, dayKey = dayKey, increment = 1)

            connection.prepareStatement(
                "SELECT SUM(count) FROM ${BlockLogStore.DAILY_TABLE} WHERE day = ?"
            ).use { statement ->
                statement.setString(1, dayKey.toString())
                val rs = statement.executeQuery()
                assertTrue(rs.next())
                assertEquals(
                    "今日统计应为 1，而不是把昨天的 10 次算进来",
                    1,
                    rs.getInt(1)
                )
            }
        }
    }

    /**
     * 索引名必须是单个标识符。
     *
     * `CREATE INDEX idx_x last_seen ON ...` 会报 `near "last_seen": syntax error`。
     */
    @Test
    fun `索引语句中索引名与表名之间只有一个空格分隔的标识符`() {
        val sql = BlockLogStore.CREATE_INDEX_SQL.trim()
        // 归一化空白后，CREATE INDEX 与 ON 之间只允许出现「索引名 ON」
        val normalized = sql.replace(Regex("\\s+"), " ")
        val prefix = "CREATE INDEX IF NOT EXISTS "
        assertTrue(
            "索引语句应以 CREATE INDEX IF NOT EXISTS 开头: $sql",
            normalized.startsWith(prefix)
        )
        val rest = normalized.removePrefix(prefix)
        val onIndex = rest.indexOf(" ON ")
        assertTrue("索引语句缺少 ON 关键字: $sql", onIndex > 0)
        val indexName = rest.substring(0, onIndex)
        assertTrue(
            "索引名不应包含空格（会导致语法错误）: $indexName",
            !indexName.contains(' ')
        )
        assertTrue(
            "索引名应以 blocked_domains 开头: $indexName",
            indexName.startsWith("idx_blocked_domains")
        )
    }

    /**
     * 首次记录后计数为 1。
     */
    @Test
    fun `首次记录计数为 1`() {
        openConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate(BlockLogStore.CREATE_TABLE_SQL) }

            val statement = connection.prepareStatement(BlockLogStore.UPDATE_COUNT_SQL)
            val insert = connection.prepareStatement(BlockLogStore.INSERT_IGNORE_SQL)

            // 域名不存在：UPDATE 影响 0 行，INSERT 插入 count = 1
            statement.setString(1, "1000")
            statement.setString(2, "ads.example.com")
            statement.executeUpdate()
            insert.setString(1, "ads.example.com")
            insert.setString(2, "1000")
            insert.executeUpdate()

            assertEquals(1, countOf(connection, "ads.example.com"))
        }
    }

    /**
     * 重复记录同一域名，计数累加而非被覆盖。
     */
    @Test
    fun `重复记录同一域名计数累加`() {
        openConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate(BlockLogStore.CREATE_TABLE_SQL) }

            val statement = connection.prepareStatement(BlockLogStore.UPDATE_COUNT_SQL)
            val insert = connection.prepareStatement(BlockLogStore.INSERT_IGNORE_SQL)

            repeat(3) { index ->
                val timestamp = (1000 + index).toString()
                statement.setString(1, timestamp)
                statement.setString(2, "ads.example.com")
                statement.executeUpdate()
                insert.setString(1, "ads.example.com")
                insert.setString(2, timestamp)
                insert.executeUpdate()
            }

            assertEquals(3, countOf(connection, "ads.example.com"))
        }
    }

    /**
     * 不同域名各自独立计数。
     */
    @Test
    fun `不同域名独立计数`() {
        openConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate(BlockLogStore.CREATE_TABLE_SQL) }

            applyRecord(connection, "a.example.com", 1000)
            applyRecord(connection, "b.example.com", 1000)
            applyRecord(connection, "a.example.com", 2000)

            assertEquals(2, countOf(connection, "a.example.com"))
            assertEquals(1, countOf(connection, "b.example.com"))
        }
    }

    /**
     * last_seen 会被更新为最新时间戳。
     */
    @Test
    fun `重复记录更新 last_seen`() {
        openConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate(BlockLogStore.CREATE_TABLE_SQL) }

            applyRecord(connection, "ads.example.com", 1000)
            applyRecord(connection, "ads.example.com", 5000)

            assertEquals(5000L, lastSeenOf(connection, "ads.example.com"))
        }
    }

    /**
     * UPDATE 先于 INSERT 是计数正确的前提。
     *
     * 若顺序颠倒，INSERT 会先写入 count=1，随后 UPDATE 又加 1，新域名计数变成 2。
     */
    @Test
    fun `先 INSERT 后 UPDATE 会导致新域名计数翻倍`() {
        openConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate(BlockLogStore.CREATE_TABLE_SQL) }

            val statement = connection.prepareStatement(BlockLogStore.UPDATE_COUNT_SQL)
            val insert = connection.prepareStatement(BlockLogStore.INSERT_IGNORE_SQL)

            // 故意颠倒顺序
            insert.setString(1, "ads.example.com")
            insert.setString(2, "1000")
            insert.executeUpdate()
            statement.setString(1, "1000")
            statement.setString(2, "ads.example.com")
            statement.executeUpdate()

            assertEquals(
                "颠倒顺序会让新域名计数变成 2，这正是必须先 UPDATE 的原因",
                2,
                countOf(connection, "ads.example.com")
            )
        }
    }

    /**
     * 查询语句语法合法且能返回预期结果。
     */
    @Test
    fun `统计查询语句语法合法`() {
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(BlockLogStore.CREATE_TABLE_SQL)
                statement.executeUpdate(BlockLogStore.CREATE_INDEX_SQL)
            }

            applyRecord(connection, "ads.example.com", 1000)
            applyRecord(connection, "ads.example.com", 2000)
            applyRecord(connection, "tracker.example.com", 3000)

            // 模拟 BlockLogStore 中的统计查询
            connection.createStatement().use { statement ->
                val total = statement.executeQuery("SELECT SUM(count) FROM blocked_domains")
                assertTrue(total.next())
                assertEquals(3, total.getInt(1))
            }

            connection.prepareStatement(
                "SELECT domain, count, last_seen FROM blocked_domains ORDER BY last_seen DESC LIMIT ?"
            ).use { statement ->
                statement.setString(1, "10")
                val rs = statement.executeQuery()
                var rows = 0
                while (rs.next()) {
                    rows++
                    assertTrue(rs.getString(1).isNotBlank())
                }
                assertEquals(2, rows)
            }

            connection.prepareStatement(
                "SELECT SUM(count) FROM blocked_domains WHERE last_seen >= ?"
            ).use { statement ->
                statement.setString(1, "1500")
                val rs = statement.executeQuery()
                assertTrue(rs.next())
                // ads(last_seen=2000, count=2) + tracker(last_seen=3000, count=1) = 3
                assertEquals(3, rs.getInt(1))
            }
        }
    }

    /**
     * 日序号必须按本地时区归一化。
     *
     * 真实故障：写入用 `timestamp / 86400000`（UTC 基准），
     * 读取用「本地零点 / 86400000」，在 UTC+8 时区两者错开一天，
     * 导致「今日拦截」永远显示 0，而「累计拦截」正常。
     */
    @Test
    fun `同一天内的时间戳归一化后日序号相同`() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        val morning = startOfDay + 1_000L
        val noon = startOfDay + 12L * 60 * 60 * 1000
        val beforeMidnight = startOfDay + 23L * 60 * 60 * 1000

        val keyOfMorning = BlockLogStore.dayKeyOf(morning)
        val keyOfNoon = BlockLogStore.dayKeyOf(noon)
        val keyOfBeforeMidnight = BlockLogStore.dayKeyOf(beforeMidnight)

        assertEquals("同一天的不同时刻应归入同一日序号", keyOfMorning, keyOfNoon)
        assertEquals("同一天的不同时刻应归入同一日序号", keyOfMorning, keyOfBeforeMidnight)
    }

    @Test
    fun `今日查询的日序号与写入时一致`() {
        val now = System.currentTimeMillis()
        val dayKeyOfNow = BlockLogStore.dayKeyOf(now)

        // 以该日序号写入汇总，再按同一序号读回，应能取到
        openConnection().use { connection ->
            connection.createStatement().use {
                it.executeUpdate(BlockLogStore.CREATE_DAILY_TABLE_SQL)
            }
            connection.prepareStatement(BlockLogStore.UPSERT_DAILY_SQL).use { statement ->
                statement.setString(1, dayKeyOfNow.toString())
                statement.setString(2, "5")
                statement.executeUpdate()
            }

            connection.prepareStatement(
                "SELECT SUM(count) FROM ${BlockLogStore.DAILY_TABLE} WHERE day = ?"
            ).use { statement ->
                statement.setString(1, dayKeyOfNow.toString())
                val rs = statement.executeQuery()
                assertTrue("今日汇总应能按同一日序号读回", rs.next())
                assertEquals(5, rs.getInt(1))
            }
        }
    }

    @Test
    fun `跨天的时间戳日序号不同`() {
        val now = System.currentTimeMillis()
        val yesterday = now - 24L * 60 * 60 * 1000
        assertTrue(
            "相隔一天的时间戳应归入不同日序号",
            BlockLogStore.dayKeyOf(now) != BlockLogStore.dayKeyOf(yesterday)
        )
    }

    // ---- 辅助方法 ----

    private fun applyRecord(connection: Connection, domain: String, timestamp: Long) {
        connection.prepareStatement(BlockLogStore.UPDATE_COUNT_SQL).use { statement ->
            statement.setString(1, timestamp.toString())
            statement.setString(2, domain)
            statement.executeUpdate()
        }
        connection.prepareStatement(BlockLogStore.INSERT_IGNORE_SQL).use { statement ->
            statement.setString(1, domain)
            statement.setString(2, timestamp.toString())
            statement.executeUpdate()
        }
    }

    private fun applyDaily(connection: Connection, dayKey: Long, increment: Int) {
        connection.prepareStatement(BlockLogStore.UPSERT_DAILY_SQL).use { statement ->
            statement.setString(1, dayKey.toString())
            statement.setString(2, increment.toString())
            statement.executeUpdate()
        }
    }

    private fun countOf(connection: Connection, domain: String): Int {
        connection.prepareStatement(
            "SELECT count FROM blocked_domains WHERE domain = ?"
        ).use { statement ->
            statement.setString(1, domain)
            val rs = statement.executeQuery()
            return if (rs.next()) rs.getInt(1) else 0
        }
    }

    private fun lastSeenOf(connection: Connection, domain: String): Long {
        connection.prepareStatement(
            "SELECT last_seen FROM blocked_domains WHERE domain = ?"
        ).use { statement ->
            statement.setString(1, domain)
            val rs = statement.executeQuery()
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }
}
