package com.skipad.data.log

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 一条拦截记录。
 */
data class BlockedEntry(
    val domain: String,
    val count: Int,
    val lastSeen: Long,
)

/**
 * 一个应用的拦截统计。
 */
data class AppBlockStat(
    val appPackage: String,
    val count: Int,
    val domainCount: Int,
)

/**
 * 拦截日志与统计的存储。
 *
 * 采用 SQLite 存储聚合结果（域名 + 次数），避免为每次拦截写一行造成性能问题。
 */
class BlockLogStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SQL)
        db.execSQL(CREATE_INDEX_SQL)
        db.execSQL(CREATE_DAILY_TABLE_SQL)
        db.execSQL(CREATE_APP_TABLE_SQL)
        db.execSQL(CREATE_APP_INDEX_SQL)
    }

    /**
     * 升级时**只补建缺失的表**，不删除已有数据。
     *
     * 早期实现是「全部 DROP 再重建」，每次升级都会清空用户的拦截历史，
     * 属于明显的数据丢失缺陷。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(CREATE_TABLE_SQL)
        db.execSQL(CREATE_INDEX_SQL)
        db.execSQL(CREATE_DAILY_TABLE_SQL)
        if (oldVersion < 3) {
            db.execSQL(CREATE_APP_TABLE_SQL)
            db.execSQL(CREATE_APP_INDEX_SQL)
        }
    }

    /**
     * 记录一次拦截。
     *
     * 使用 UPSERT 累加次数：同一域名重复命中时 count +1，而不是被覆盖。
     */
    fun record(
        domain: String,
        timestamp: Long = System.currentTimeMillis(),
        appPackage: String? = null,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            applyCount(db, domain, timestamp)
            applyDailyCount(db, timestamp, 1)
            appPackage?.let { applyAppCount(db, it, domain, timestamp) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 批量记录。
     */
    fun recordAll(
        domains: Collection<String>,
        timestamp: Long = System.currentTimeMillis(),
        appPackage: String? = null,
    ) {
        if (domains.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            domains.forEach { domain ->
                applyCount(db, domain, timestamp)
            }
            // 日汇总按实际命中域名数累加
            applyDailyCount(db, timestamp, domains.size)
            appPackage?.let { pkg ->
                domains.forEach { domain -> applyAppCount(db, pkg, domain, timestamp) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 按应用统计拦截量。
     *
     * 归因依据是「拦截发生时处于前台的应用」，属于**近似**：
     * 后台应用自己发起的请求可能被记到当时在前台的应用头上。
     */
    fun topApps(limit: Int = 10): List<AppBlockStat> {
        val result = ArrayList<AppBlockStat>(limit)
        readableDatabase.rawQuery(
            "SELECT app_package, SUM(count) AS total, COUNT(DISTINCT domain) AS domains " +
                "FROM $APP_TABLE GROUP BY app_package ORDER BY total DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AppBlockStat(
                    appPackage = cursor.getString(0),
                    count = cursor.getInt(1),
                    domainCount = cursor.getInt(2),
                )
            }
        }
        return result
    }

    /**
     * 取最近的拦截记录。
     */
    fun recent(limit: Int = 200): List<BlockedEntry> {
        val result = ArrayList<BlockedEntry>(limit)
        readableDatabase.rawQuery(
            "SELECT domain, count, last_seen FROM $TABLE ORDER BY last_seen DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlockedEntry(
                    domain = cursor.getString(0),
                    count = cursor.getInt(1),
                    lastSeen = cursor.getLong(2),
                )
            }
        }
        return result
    }

    /**
     * 取被拦截次数最多的域名。
     */
    fun topDomains(limit: Int = 20): List<BlockedEntry> {
        val result = ArrayList<BlockedEntry>(limit)
        readableDatabase.rawQuery(
            "SELECT domain, count, last_seen FROM $TABLE ORDER BY count DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlockedEntry(
                    domain = cursor.getString(0),
                    count = cursor.getInt(1),
                    lastSeen = cursor.getLong(2),
                )
            }
        }
        return result
    }

    /**
     * 拦截总次数。
     */
    fun totalBlocked(): Long {
        var total = 0L
        readableDatabase.rawQuery("SELECT SUM(count) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) total = cursor.getLong(0)
        }
        return total
    }

    /**
     * 被拦截的不同域名数量。
     */
    fun uniqueDomains(): Long {
        var total = 0L
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) total = cursor.getLong(0)
        }
        return total
    }

    /**
     * 今日拦截次数（按本地时区 0 点起算）。
     *
     * 取自按天汇总表，而非从主表推断 —— 主表记录的是域名**累计**命中次数，
     * 若某域名昨天命中 10 次、今天命中 1 次，直接按 `last_seen >= 今日零点` 求和
     * 会得到 11，把昨天的计数也算进今天。
     */
    fun todayBlocked(): Long {
        val today = todayKey()
        var total = 0L
        readableDatabase.rawQuery(
            "SELECT SUM(count) FROM $DAILY_TABLE WHERE day = ?",
            arrayOf(today.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) total = cursor.getLong(0)
        }
        return total
    }

    /**
     * 今日 0 点对应的天数序号。
     */
    private fun todayKey(): Long = dayKeyOf(System.currentTimeMillis())

    /**
     * 累加某一天的汇总计数。
     */
    private fun applyDailyCount(db: SQLiteDatabase, timestamp: Long, increment: Int) {
        if (increment <= 0) return
        val day = dayKeyOf(timestamp)
        db.execSQL(UPSERT_DAILY_SQL, arrayOf(day.toString(), increment.toString()))
    }

    /**
     * 清空日志。
     */
    fun clear() {
        writableDatabase.delete(TABLE, null, null)
        writableDatabase.delete(DAILY_TABLE, null, null)
    }

    /**
     * 清理超过指定天数的旧记录。
     */
    fun pruneOlderThan(days: Int) {
        val threshold = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        writableDatabase.delete(TABLE, "last_seen < ?", arrayOf(threshold.toString()))
        // 日汇总表按本地日序号存储，保留最近 days 天即可
        val minDay = dayKeyOf(threshold)
        writableDatabase.delete(DAILY_TABLE, "day < ?", arrayOf(minDay.toString()))
    }

    companion object {
        private const val DB_NAME = "block_log.db"
        private const val DB_VERSION = 3
        const val TABLE = "blocked_domains"

        /** 按天汇总表。 */
        const val DAILY_TABLE = "blocked_daily"

        /** 按应用归因的拦截表。 */
        const val APP_TABLE = "blocked_by_app"

        /**
         * 按应用归因的建表语句。
         *
         * 单独建表而不是给主表加列：加列需要迁移既有数据，
         * 而这张表只承载新增的统计维度，互不影响。
         */
        const val CREATE_APP_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS blocked_by_app (
                app_package TEXT NOT NULL,
                domain TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 1,
                last_seen INTEGER NOT NULL,
                PRIMARY KEY(app_package, domain)
            )
        """

        /** 按应用表的索引。 */
        const val CREATE_APP_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_blocked_by_app_last_seen " +
                "ON blocked_by_app(last_seen DESC)"

        /**
         * 建表语句。
         *
         * 单独抽为常量，便于在单元测试中用真实 SQLite 引擎执行验证，
         * 避免语法错误直到真机运行才暴露。
         */
        const val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS blocked_domains (
                domain TEXT PRIMARY KEY,
                count INTEGER NOT NULL DEFAULT 1,
                last_seen INTEGER NOT NULL
            )
        """

        /**
         * 按天汇总表。
         *
         * 主表只记录域名的**累计**命中次数，无法回答「今天拦了多少次」——
         * 昨天命中 10 次、今天 1 次的域名，其 count 为 11。
         * 因此单独维护按天汇总，「今日拦截」才有准确值。
         */
        const val CREATE_DAILY_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS blocked_daily (
                day INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(day)
            )
        """

        /**
         * 索引语句。
         *
         * 索引名必须是**单个**标识符，写成 `idx_blocked_domains last_seen` 会导致
         * `near "last_seen": syntax error` 而使数据库无法创建、进而应用崩溃。
         */
        const val CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_blocked_domains_last_seen " +
                "ON blocked_domains(last_seen DESC)"

        /**
         * 累加已有域名的计数。
         *
         * 参数顺序：last_seen、domain。
         */
        const val UPDATE_COUNT_SQL =
            "UPDATE blocked_domains SET count = count + 1, last_seen = ? WHERE domain = ?"

        /**
         * 插入新域名。已存在时忽略。
         *
         * 参数顺序：domain、last_seen。
         */
        const val INSERT_IGNORE_SQL =
            "INSERT OR IGNORE INTO blocked_domains(domain, count, last_seen) VALUES(?, 1, ?)"

        /**
         * 累加某一天的汇总计数。
         *
         * 参数顺序：day、count。
         */
        const val UPSERT_DAILY_SQL =
            "INSERT INTO blocked_daily(day, count) VALUES(?, ?) " +
                "ON CONFLICT(day) DO UPDATE SET count = count + excluded.count"

        /**
         * 时间戳所属的「本地日」序号。
         *
         * 写入与读取必须使用**同一基准**：均按本地时区零点归一化后再取天数。
         * 若写入用 `timestamp / 86400000`（UTC 基准）、读取用「本地零点 / 86400000」，
         * 在非 UTC 时区两者会错开一天，导致「今日拦截」永远显示 0。
         */
        fun dayKeyOf(timestamp: Long): Long {
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = timestamp
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            return calendar.timeInMillis / 86_400_000L
        }

        /**
         * 累加计数并记录时间戳。
         *
         * 刻意**不使用** `ON CONFLICT ... DO UPDATE` 语法于主表：该语法需要 SQLite 3.24+
         * （Android 9 / API 28），而本应用 minSdk 为 26（Android 8.0，SQLite 3.18）。
         * 主表因此采用「先 UPDATE 再 INSERT OR IGNORE」。
         *
         * 执行顺序必须是「先 UPDATE 再 INSERT OR IGNORE」：
         * - 域名已存在 → UPDATE 使 count +1，INSERT 被忽略
         * - 域名不存在 → UPDATE 影响 0 行，INSERT 插入 count = 1
         * 若顺序颠倒，新插入的行会先被 INSERT 置为 1、再被 UPDATE 加 1，计数翻倍。
         */
        fun applyCount(db: SQLiteDatabase, domain: String, timestamp: Long) {
            db.execSQL(UPDATE_COUNT_SQL, arrayOf(timestamp.toString(), domain))
            db.execSQL(INSERT_IGNORE_SQL, arrayOf(domain, timestamp.toString()))
        }

        /**
         * 累加「某应用 + 某域名」的计数。
         *
         * 与主表同样的「先 UPDATE 再 INSERT OR IGNORE」策略，
         * 保证首次写入为 1、重复命中才累加。
         */
        fun applyAppCount(
            db: SQLiteDatabase,
            appPackage: String,
            domain: String,
            timestamp: Long,
        ) {
            db.execSQL(
                UPDATE_APP_COUNT_SQL,
                arrayOf(timestamp.toString(), appPackage, domain),
            )
            db.execSQL(
                INSERT_APP_IGNORE_SQL,
                arrayOf(appPackage, domain, timestamp.toString()),
            )
        }

        /** 按应用表的计数累加。 */
        const val UPDATE_APP_COUNT_SQL =
            "UPDATE blocked_by_app SET count = count + 1, last_seen = ? " +
                "WHERE app_package = ? AND domain = ?"

        /** 按应用表的首次写入。 */
        const val INSERT_APP_IGNORE_SQL =
            "INSERT OR IGNORE INTO blocked_by_app(app_package, domain, count, last_seen) " +
                "VALUES(?, ?, 1, ?)"
    }
}
