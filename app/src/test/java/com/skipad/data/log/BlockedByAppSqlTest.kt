package com.skipad.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 「按应用归因」表的 SQL 回归测试。
 *
 * 与 [BlockLogStoreSqlTest] 同理：这类语句一旦写错，只会在真机上暴露为
 * 数据库异常，而异常发生在拦截落库的路径上，会直接影响服务稳定性。
 */
class BlockedByAppSqlTest {

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun createTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(BlockLogStore.CREATE_APP_TABLE_SQL)
            statement.executeUpdate(BlockLogStore.CREATE_APP_INDEX_SQL)
        }
    }

    @Test
    fun `建表与建索引语句语法合法`() {
        openConnection().use { connection ->
            createTable(connection)
        }
    }

    @Test
    fun `同一应用同一域名重复命中时计数累加`() {
        openConnection().use { connection ->
            createTable(connection)
            val upsert = connection.prepareStatement(BlockLogStore.UPDATE_APP_COUNT_SQL)
            val insert = connection.prepareStatement(BlockLogStore.INSERT_APP_IGNORE_SQL)

            fun hit(pkg: String, domain: String) {
                upsert.setString(1, "1000")
                upsert.setString(2, pkg)
                upsert.setString(3, domain)
                upsert.executeUpdate()

                insert.setString(1, pkg)
                insert.setString(2, domain)
                insert.setString(3, "1000")
                insert.executeUpdate()
            }

            hit("com.a", "ads.example.com")
            hit("com.a", "ads.example.com")
            hit("com.a", "ads.example.com")

            connection.prepareStatement(
                "SELECT count FROM ${BlockLogStore.APP_TABLE} " +
                    "WHERE app_package = ? AND domain = ?"
            ).use { query ->
                query.setString(1, "com.a")
                query.setString(2, "ads.example.com")
                query.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("三次命中应累计为 3", 3, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `不同应用命中同一域名互不影响`() {
        openConnection().use { connection ->
            createTable(connection)
            val upsert = connection.prepareStatement(BlockLogStore.UPDATE_APP_COUNT_SQL)
            val insert = connection.prepareStatement(BlockLogStore.INSERT_APP_IGNORE_SQL)

            listOf("com.a", "com.b").forEach { pkg ->
                upsert.setString(1, "1000")
                upsert.setString(2, pkg)
                upsert.setString(3, "ads.example.com")
                upsert.executeUpdate()

                insert.setString(1, pkg)
                insert.setString(2, "ads.example.com")
                insert.setString(3, "1000")
                insert.executeUpdate()
            }

            connection.prepareStatement(
                "SELECT COUNT(*) FROM ${BlockLogStore.APP_TABLE}"
            ).use { query ->
                query.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("两个应用应各自一行", 2, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `按应用聚合的统计语句可用`() {
        openConnection().use { connection ->
            createTable(connection)
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO ${BlockLogStore.APP_TABLE}(app_package, domain, count, last_seen) " +
                        "VALUES('com.a', 'a1.com', 5, 1000)"
                )
                statement.executeUpdate(
                    "INSERT INTO ${BlockLogStore.APP_TABLE}(app_package, domain, count, last_seen) " +
                        "VALUES('com.a', 'a2.com', 3, 1000)"
                )
                statement.executeUpdate(
                    "INSERT INTO ${BlockLogStore.APP_TABLE}(app_package, domain, count, last_seen) " +
                        "VALUES('com.b', 'b1.com', 9, 1000)"
                )
            }

            connection.prepareStatement(
                "SELECT app_package, SUM(count) AS total, COUNT(DISTINCT domain) AS domains " +
                    "FROM ${BlockLogStore.APP_TABLE} GROUP BY app_package ORDER BY total DESC"
            ).use { query ->
                query.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("com.b", rs.getString(1))
                    assertEquals(9, rs.getInt(2))
                    assertEquals(1, rs.getInt(3))

                    assertTrue(rs.next())
                    assertEquals("com.a", rs.getString(1))
                    assertEquals("同应用多域名应合并", 8, rs.getInt(2))
                    assertEquals(2, rs.getInt(3))
                }
            }
        }
    }
}
