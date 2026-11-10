package com.skipad.data.filter

import android.content.Context
import android.util.Log
import com.skipad.core.blocklist.Rule
import com.skipad.core.blocklist.RuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 规则源的下载结果。
 */
sealed interface FetchResult {
    data class Success(val source: FilterSource, val ruleCount: Int, val bytes: Int) : FetchResult
    data class Failure(val source: FilterSource, val reason: String) : FetchResult
}

/**
 * 规则文本的本地存储。
 */
class RuleStore(context: Context) {

    private val dir = File(context.filesDir, "rules").apply { mkdirs() }

    fun fileFor(source: FilterSource): File = File(dir, "${source.id}.txt")

    fun exists(source: FilterSource): Boolean = fileFor(source).let { it.exists() && it.length() > 0 }

    fun read(source: FilterSource): String? {
        val file = fileFor(source)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun write(source: FilterSource, content: String) {
        val file = fileFor(source)
        val temp = File(dir, "${source.id}.tmp")
        temp.writeText(content)
        if (file.exists()) file.delete()
        temp.renameTo(file)
    }

    fun delete(source: FilterSource) {
        fileFor(source).delete()
    }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/**
 * 规则源管理器：负责下载、解析与装载。
 */
class FilterManager(
    private val context: Context,
    private val store: RuleStore = RuleStore(context),
) {

    /**
     * 常规客户端：用于体积较小的规则源。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 大文件客户端：部分规则源体积可达 10MB 以上，慢速网络下需要更长超时。
     *
     * 不设 callTimeout（总时长上限），只约束连接与单次读取超时，
     * 避免大文件在下载途中被总超时强制中断。
     */
    private val largeFileClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 体积超过该阈值的规则源使用大文件客户端。
     */
    private val largeFileThreshold = 5L * 1024 * 1024

    /**
     * 下载单个规则源并保存到本地。
     *
     * 按 [FilterSource.urls] 依次尝试，首个成功的即返回。
     * 全部失败时返回最后一个错误信息。
     */
    suspend fun fetch(source: FilterSource): FetchResult = withContext(Dispatchers.IO) {
        var lastError = "无可用地址"

        for (url in source.urls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SkipAd/1.0 (Android)")
                .build()

            val outcome = runCatching {
                // 首次请求用常规客户端探路，拿到 Content-Length 后判断是否为大文件
                val probe = client.newCall(request).execute()
                if (!probe.isSuccessful) {
                    probe.close()
                    return@withContext FetchResult.Failure(source, "HTTP ${probe.code}")
                }
                val contentLength = probe.header("Content-Length")?.toLongOrNull() ?: 0L
                if (contentLength > largeFileThreshold) {
                    // 大文件改用长超时客户端重新下载
                    probe.close()
                    Log.d(TAG, "规则源 ${source.id} 体积 ${contentLength / 1024 / 1024}MB，使用大文件客户端")
                    fetchWithLargeClient(source, url)
                } else {
                    probe.use { response ->
                        val body = response.body
                            ?: return@withContext FetchResult.Failure(source, "响应为空")
                        val content = body.string()
                        if (content.isBlank()) {
                            return@withContext FetchResult.Failure(source, "内容为空")
                        }
                        store.write(source, content)
                        val ruleCount = RuleParser.parse(content, source.id).size
                        FetchResult.Success(source, ruleCount, content.toByteArray().size)
                    }
                }
            }.getOrElse { error ->
                FetchResult.Failure(source, error.message ?: error.javaClass.simpleName)
            }

            if (outcome is FetchResult.Success) {
                return@withContext outcome
            }
            lastError = (outcome as FetchResult.Failure).reason
            Log.d(TAG, "规则源 ${source.id} 从 $url 获取失败: $lastError，尝试下一个镜像")
        }

        FetchResult.Failure(source, lastError)
    }

    /**
     * 用长超时客户端下载规则源。
     */
    private fun fetchWithLargeClient(source: FilterSource, url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SkipAd/1.0 (Android)")
            .build()

        return largeFileClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use FetchResult.Failure(source, "HTTP ${response.code}")
            }
            val body = response.body ?: return@use FetchResult.Failure(source, "响应为空")
            val content = body.string()
            if (content.isBlank()) {
                return@use FetchResult.Failure(source, "内容为空")
            }
            store.write(source, content)
            val ruleCount = RuleParser.parse(content, source.id).size
            FetchResult.Success(source, ruleCount, content.toByteArray().size)
        }
    }

    /**
     * 并发下载多个规则源。
     */
    suspend fun fetchAll(sources: List<FilterSource>): List<FetchResult> = coroutineScope {
        sources.map { source -> async { fetch(source) } }.awaitAll()
    }

    /**
     * 加载所有已启用规则源的规则。
     *
     * 若某个规则源本地没有缓存，会尝试先下载。
     */
    suspend fun loadRules(
        sourceIds: Set<String>,
        customDomains: Collection<String> = emptyList(),
    ): List<Rule> = withContext(Dispatchers.IO) {
        val sources = sourceIds.mapNotNull { FilterSource.fromId(it) }
        val rules = ArrayList<Rule>(100_000)

        for (source in sources) {
            var content = store.read(source)
            if (content == null) {
                // 本地无缓存，尝试下载一次
                val result = fetch(source)
                if (result is FetchResult.Success) {
                    content = store.read(source)
                }
            }
            if (content == null) continue

            RuleParser.resetBudget()
            rules += RuleParser.parse(content, source.id)
        }

        // 用户自定义的拦截域名：语义与 hosts 规则一致（同时覆盖子域）
        rules += customDomainRules(customDomains)
        rules
    }

    /**
     * 找出哪些本地规则源包含指定域名。
     *
     * 用于「域名查询」功能的来源追查：直接对缓存文本做子串匹配，
     * 比重新解析整份列表快得多（10MB 文本约几十毫秒）。
     */
    suspend fun findSourcesContaining(
        domain: String,
        sourceIds: Set<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val target = domain.trim().lowercase()
        if (target.isEmpty()) return@withContext emptyList()
        sourceIds.mapNotNull { FilterSource.fromId(it) }
            .filter { it.id != "custom" }
            .filter { source ->
                val content = store.read(source) ?: return@filter false
                content.contains(target, ignoreCase = true)
            }
            .map { it.displayName }
    }

    /**
     * 统计各规则源的本地缓存状态。
     */
    fun cachedStatus(): Map<FilterSource, Long> = FilterSource.entries.associateWith { source ->
        val file = store.fileFor(source)
        if (file.exists()) file.length() else 0L
    }

    companion object {
        private const val TAG = "SkipAdFilter"
    }

}

/**
 * 把用户自定义的域名转成拦截规则。
 *
 * 语义与 hosts 规则一致：命中该域名**及其子域**。
 * 非法域名（不含点、含非法字符）直接丢弃，不影响其余。
 */
internal fun customDomainRules(domains: Collection<String>): List<Rule> {
    val result = ArrayList<Rule>(domains.size)
    domains.forEach { raw ->
        val domain = com.skipad.core.blocklist.RuleParser.normalizeDomain(raw) ?: return@forEach
        result += Rule(
            action = com.skipad.core.blocklist.RuleAction.BLOCK,
            domain = domain,
            subdomains = true,
            source = "custom",
        )
    }
    return result
}
