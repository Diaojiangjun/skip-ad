package com.skipad.data.skip

import android.content.Context
import com.skipad.core.skip.BuiltInSkipRules
import com.skipad.core.skip.GkdSubscription
import com.skipad.core.skip.SkipRule
import com.skipad.core.skip.SkipRuleCodec
import com.skipad.core.skip.SkipRuleIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用级规则的多源下载与合并。
 *
 * 每个订阅源单独缓存为一个文件，互不影响：
 * 某个源下载失败或被关闭，其余源仍然生效。
 */
class SkipRuleRepository(context: Context) {

    private val dir = File(context.filesDir, "skiprules").apply { mkdirs() }

    /**
     * 刻意**不设 callTimeout**：GKD 订阅超过 1MB，
     * 总时长上限在慢速网络下会导致必然超时。只约束连接与单次读取。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 单个源的加载结果。
     *
     * @param rules 可用规则
     * @param skipped 因语法不支持被跳过的规则数
     */
    data class Parsed(
        val rules: List<SkipRule>,
        val skipped: Int = 0,
    )

    /**
     * 源的本地状态，供界面展示。
     */
    data class SourceStats(
        val ruleCount: Int,
        val skipped: Int,
        val updatedAt: Long,
        /** 是否已有本地缓存 */
        val cached: Boolean,
    )

    /**
     * 更新一个订阅源。
     *
     * 按 [SkipSource.urls] 顺序尝试镜像，首个成功的即采用并写入缓存。
     * 解析不到任何规则视为失败，**不覆盖已有缓存**。
     */
    suspend fun update(source: SkipSource): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (!source.requiresDownload) {
                return@runCatching BuiltInSkipRules.rules.size
            }

            var lastError: Throwable = IllegalStateException("无可用镜像地址")
            for (url in source.urls) {
                val attempt = runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "SkipAd/1.0 (Android)")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) error("内容为空")

                        val parsed = parse(source, body)
                        if (parsed.rules.isEmpty()) error("未解析到可用规则")

                        cacheFile(source).writeText(body)
                        parsed.rules.size
                    }
                }
                if (attempt.isSuccess) {
                    return@runCatching attempt.getOrThrow()
                }
                lastError = attempt.exceptionOrNull() ?: lastError
            }
            throw lastError
        }
    }

    /**
     * 读取某个源的本地产出。
     */
    fun load(source: SkipSource): Parsed {
        if (source == SkipSource.BUILTIN) {
            return Parsed(BuiltInSkipRules.rules)
        }
        val file = cacheFile(source)
        if (!file.exists()) return Parsed(emptyList())
        val content = runCatching { file.readText() }.getOrNull() ?: return Parsed(emptyList())
        return parse(source, content)
    }

    /**
     * 合并所有启用源 + 自定义规则的规则集合。
     */
    fun loadAll(enabledSourceIds: Set<String>, customRules: Collection<String>): List<SkipRule> {
        val result = ArrayList<SkipRule>(1024)
        result += collectSafely(
            items = SkipSource.entries.filter { it.id in enabledSourceIds },
            onError = { source, error ->
                android.util.Log.w("SkipRuleRepository", "规则源 ${source.id} 解析失败", error)
            },
        ) { source -> load(source).rules }

        return buildRuleSet(enabledSourceIds, result, customRules)
    }

    /**
     * 构造规则索引。
     */
    fun buildIndex(enabledSourceIds: Set<String>, customRules: Collection<String>): SkipRuleIndex =
        SkipRuleIndex(loadAll(enabledSourceIds, customRules))

    /**
     * 读取各源的本地统计，用于界面展示。
     */
    fun stats(source: SkipSource): SourceStats {
        if (source == SkipSource.BUILTIN) {
            return SourceStats(BuiltInSkipRules.rules.size, 0, 0L, true)
        }
        val file = cacheFile(source)
        if (!file.exists()) return SourceStats(0, 0, 0L, false)
        val parsed = runCatching { load(source) }.getOrNull()
            ?: return SourceStats(0, 0, file.lastModified(), true)
        return SourceStats(parsed.rules.size, parsed.skipped, file.lastModified(), true)
    }

    /**
     * 清空某个源的缓存。
     */
    fun clear(source: SkipSource) {
        runCatching { cacheFile(source).delete() }
    }

    /**
     * 按格式解析文本。
     */
    private fun parse(source: SkipSource, text: String): Parsed = when (source.format) {
        SkipSourceFormat.GKD -> {
            val result = GkdSubscription.parse(text, source.id)
            Parsed(result.rules, result.skipped)
        }
        SkipSourceFormat.SKIP,
        SkipSourceFormat.NATIVE,
        -> Parsed(SkipRuleCodec.parseList(text))
    }

    private fun cacheFile(source: SkipSource): File = File(dir, "${source.id}.json")

}

/**
 * 逐项收集结果，**单项失败不影响整体**。
 *
 * 这段逻辑是从真实故障里抽出来的：某个规则源的缓存损坏（下载被截断、格式变动）时，
 * 异常原先会一路冒到无障碍服务的协程里，把进程干掉，系统随后把无障碍服务
 * 记成「无法运行」并且不再重连。
 *
 * 抽成独立函数是为了能在不依赖 Android 的前提下直接单测这条语义。
 */
internal fun <T, R> collectSafely(
    items: List<T>,
    onError: (T, Throwable) -> Unit = { _, _ -> },
    block: (T) -> Collection<R>,
): List<R> {
    val result = ArrayList<R>(items.size)
    items.forEach { item ->
        runCatching { block(item) }
            .onSuccess { result += it }
            .onFailure { onError(item, it) }
    }
    return result

}

/**
 * 把「各源规则 + 内置兜底 + 用户自定义规则」合并成最终规则集。
 *
 * 抽成独立函数是为了直接单测这几条约定：
 * - 内置规则**始终**生效，即便用户把「内置精选」源关掉
 * - 用户自定义的规则 JSON 解析失败时只跳过这一条，不影响其余
 */
internal fun buildRuleSet(
    enabledSourceIds: Set<String>,
    sourceRules: List<com.skipad.core.skip.SkipRule>,
    customJson: Collection<String>,
): List<com.skipad.core.skip.SkipRule> {
    val result = ArrayList<com.skipad.core.skip.SkipRule>(sourceRules.size + 32)

    // 内置规则始终生效：若对应源已被用户关闭，这里补回来
    if (SkipSource.BUILTIN.id !in enabledSourceIds) {
        result += com.skipad.core.skip.BuiltInSkipRules.rules
    }
    result += sourceRules

    customJson.forEach { json ->
        runCatching {
            SkipRuleCodec.parseRule(org.json.JSONObject(json), "custom")
        }.getOrNull()?.let { result += it }
    }
    return result
}
