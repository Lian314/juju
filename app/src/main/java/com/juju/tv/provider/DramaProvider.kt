package com.juju.tv.provider

import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.CommentItem
import com.juju.tv.model.DanmakuItem
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 短剧站源统一接口（对应原版 Go `provider_*.go` 抽象）
 */
interface DramaProvider {
    val sourceId: String
    val sourceName: String
    val defaultBaseUrl: String

    fun fetchHomeRails(baseUrlOverride: String? = null): List<RailFeed>
    fun fetchCategories(baseUrlOverride: String? = null): List<CategoryNode>
    fun fetchCatalog(category: String, page: Int = 1, baseUrlOverride: String? = null): List<DramaCard>
    fun fetchRankings(subRank: String = "热播榜", page: Int = 1, baseUrlOverride: String? = null): List<DramaCard>
    fun fetchCalendar(dateStr: String = "", baseUrlOverride: String? = null): List<DramaCard>
    fun browse(query: BrowseQuery, baseUrlOverride: String? = null): List<DramaCard>
    fun search(keyword: String, page: Int = 1, baseUrlOverride: String? = null): List<DramaCard>
    fun fetchDetail(dramaId: String, baseUrlOverride: String? = null): DramaDetail?
    fun resolveEpisodeStream(dramaId: String, episodeIndex: Int, baseUrlOverride: String? = null): EpisodeItem?
    fun fetchComments(dramaId: String, episodeIndex: Int = 1, baseUrlOverride: String? = null): List<CommentItem> = emptyList()
    fun fetchDanmaku(dramaId: String, episodeIndex: Int = 1, baseUrlOverride: String? = null): List<DanmakuItem> = emptyList()
}

/**
 * 站源健康度拨测与自动熔断器（对应原版 Go `app_source_health.go`）
 *
 * 当某个第三方站源连续失败达到 [circuitBreakThreshold] 次时，自动进入 [cooldownMs] 熔断冷却期，
 * 避免在 2GB 电视上因超时重试阻塞协程池与 UI 响应。
 */
class SourceHealthTracker(
    private val circuitBreakThreshold: Int = 3,
    private val cooldownMs: Long = 60_000L
) {
    data class HealthRecord(
        val sourceId: String,
        val avgLatencyMs: Long = 120L,
        val consecutiveFailures: Int = 0,
        val totalSuccess: Int = 0,
        val totalFailures: Int = 0,
        val circuitOpenUntil: Long = 0L,
        val lastError: String = ""
    ) {
        val isHealthy: Boolean
            get() = System.currentTimeMillis() >= circuitOpenUntil
    }

    private val map = ConcurrentHashMap<String, HealthRecord>()

    fun isAvailable(sourceId: String): Boolean {
        val rec = map[sourceId] ?: return true
        return System.currentTimeMillis() >= rec.circuitOpenUntil
    }

    fun recordSuccess(sourceId: String, latencyMs: Long) {
        map.compute(sourceId) { _, old ->
            val prev = old ?: HealthRecord(sourceId = sourceId, avgLatencyMs = latencyMs)
            val ema = ((prev.avgLatencyMs * 7) + (latencyMs.coerceAtLeast(1L) * 3)) / 10
            prev.copy(
                avgLatencyMs = ema,
                consecutiveFailures = 0,
                totalSuccess = prev.totalSuccess + 1,
                circuitOpenUntil = 0L,
                lastError = ""
            )
        }
    }

    fun recordFailure(sourceId: String, errorMsg: String) {
        val now = System.currentTimeMillis()
        map.compute(sourceId) { _, old ->
            val prev = old ?: HealthRecord(sourceId = sourceId)
            val fails = prev.consecutiveFailures + 1
            val openUntil = if (fails >= circuitBreakThreshold) now + cooldownMs else prev.circuitOpenUntil
            prev.copy(
                consecutiveFailures = fails,
                totalFailures = prev.totalFailures + 1,
                circuitOpenUntil = openUntil,
                lastError = errorMsg.take(120)
            )
        }
    }

    fun reset(sourceId: String) {
        map.remove(sourceId)
    }

    fun snapshotAll(): List<HealthRecord> = map.values.sortedBy { it.avgLatencyMs }
}
