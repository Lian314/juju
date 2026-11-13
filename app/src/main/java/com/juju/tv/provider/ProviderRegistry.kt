package com.juju.tv.provider

import com.google.gson.JsonParser
import com.juju.tv.model.BrowseFilterPanel
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.CommentItem
import com.juju.tv.model.DanmakuItem
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import com.juju.tv.storage.JuJuRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 多站源并发调度、AliDNS DoH 防劫持解析、健康度熔断与自动降级中心
 *
 * 对应原版 Go 层：
 * - `app_sources.go` / `app_source_health.go`
 * - `network_limiter.go` (在 2GB RAM 投影仪上严格限制并发网络请求数 <= 4)
 * - `network_dns.go` (`https://dns.alidns.com/resolve` DoH 域名解析)
 */
class ProviderRegistry(
    private val repository: JuJuRepository,
    val healthTracker: SourceHealthTracker = SourceHealthTracker(),
    val hongguoProvider: HongguoProvider = HongguoProvider(),
    val huangjuProvider: HuangjuProvider = HuangjuProvider(),
    val huangdouProvider: HuangdouProvider = HuangdouProvider(),
    val customSourceEngine: CustomSourceEngine = CustomSourceEngine()
) {
    // 严格限制同时在飞的站源请求不超过 4 个，保护 2GB 电视的 CPU 与 Socket 资源
    private val networkLimiter = Semaphore(4)

    private val dohHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val providers: List<DramaProvider> = listOf(
        hongguoProvider,
        huangjuProvider,
        huangdouProvider,
        customSourceEngine
    )

    /**
     * AliDNS DoH (`https://dns.alidns.com/resolve`) 防投影仪路由器 DNS 污染解析器
     */
    fun resolveHostViaAliDoH(hostname: String): List<String> {
        return try {
            val url = "https://dns.alidns.com/resolve?name=$hostname&type=1"
            val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
            dohHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body()?.string() ?: return emptyList()
                val root = JsonParser.parseString(body).asJsonObject
                val answers = root.getAsJsonArray("Answer") ?: return emptyList()
                answers.mapNotNull { el ->
                    val obj = el.asJsonObject
                    if (obj.get("type")?.asInt == 1) obj.get("data")?.asString else null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun enabledProviders(): List<DramaProvider> {
        val settings = repository.getSettings()
        return providers.filter { p ->
            (settings.enabledSources[p.sourceId] ?: true) && healthTracker.isAvailable(p.sourceId)
        }.ifEmpty { listOf(customSourceEngine) }
    }

    private fun domainOverrideFor(sourceId: String): String? {
        return repository.getSettings().sourceDomainOverrides[sourceId]?.takeIf { it.isNotBlank() }
    }

    private inline fun <T> callProviderSafely(
        provider: DramaProvider,
        block: (DramaProvider, String?) -> T
    ): T? {
        val startMs = System.currentTimeMillis()
        val acquired = networkLimiter.tryAcquire(4, TimeUnit.SECONDS)
        if (!acquired) return null
        return try {
            val res = block(provider, domainOverrideFor(provider.sourceId))
            val cost = System.currentTimeMillis() - startMs
            healthTracker.recordSuccess(provider.sourceId, cost)
            res
        } catch (e: Throwable) {
            healthTracker.recordFailure(provider.sourceId, e.message ?: e.javaClass.simpleName)
            null
        } finally {
            networkLimiter.release()
        }
    }

    fun resetHealthAndCaches() {
        providers.forEach { healthTracker.reset(it.sourceId) }
        hongguoProvider.clearCache()
    }

    /**
     * 获取首页全部推荐轨道（固定顺序：hot -> category:漫剧 -> category:真人剧 -> category:AI 剧 -> rest）
     */
    fun fetchHomeRails(): List<RailFeed> {
        val dynamicCards = customSourceEngine.getDynamicCards()
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val rails = callProviderSafely(p) { prov, overrideUrl -> prov.fetchHomeRails(overrideUrl) }
            if (!rails.isNullOrEmpty() && rails.any { it.items.isNotEmpty() }) {
                if (dynamicCards.isEmpty()) return rails
                return rails.mapIndexed { index, rail ->
                    if (index == 0 || rail.key == "hot") {
                        rail.copy(items = (dynamicCards + rail.items).distinctBy { it.id })
                    } else rail
                }
            }
        }
        return customSourceEngine.fetchHomeRails()
    }

    /**
     * 按轨道 key 串行获取单条轨道（供手机扫码遥控 `/api/rail?key=...` 调用）
     */
    fun fetchSingleRail(railKey: String): RailFeed {
        val rails = fetchHomeRails()
        return rails.firstOrNull { it.key == railKey }
            ?: customSourceEngine.fetchHomeRails().firstOrNull { it.key == railKey }
            ?: RailFeed(railKey, railKey.removePrefix("category:"), "", customSourceEngine.fetchCatalog(railKey, 1))
    }

    fun fetchCategories(): List<CategoryNode> {
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val cats = callProviderSafely(p) { prov, overrideUrl -> prov.fetchCategories(overrideUrl) }
            if (!cats.isNullOrEmpty()) return cats
        }
        return customSourceEngine.fetchCategories()
    }

    fun fetchCatalog(category: String, page: Int = 1): List<DramaCard> {
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val list = callProviderSafely(p) { prov, overrideUrl -> prov.fetchCatalog(category, page, overrideUrl) }
            if (!list.isNullOrEmpty()) {
                return if (customSourceEngine.hasDynamicSources()) {
                    (customSourceEngine.fetchCatalog(category, page) + list).distinctBy { it.id }
                } else list
            }
        }
        return customSourceEngine.fetchCatalog(category, page)
    }

    fun fetchRankSubs(): List<String> = listOf("热播榜", "新剧榜", "飙升榜", "必看榜")

    fun fetchRankings(subRank: String, page: Int = 1): List<DramaCard> {
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val list = callProviderSafely(p) { prov, overrideUrl -> prov.fetchRankings(subRank, page, overrideUrl) }
            if (!list.isNullOrEmpty()) return list
        }
        return customSourceEngine.fetchRankings(subRank, page)
    }

    fun fetchCalendar(dateStr: String = ""): List<DramaCard> {
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val list = callProviderSafely(p) { prov, overrideUrl -> prov.fetchCalendar(dateStr, overrideUrl) }
            if (!list.isNullOrEmpty()) return list
        }
        return customSourceEngine.fetchCalendar(dateStr)
    }

    fun getBrowseFilterPanel(): BrowseFilterPanel = BrowseFilterPanel()

    fun browse(query: BrowseQuery): List<DramaCard> {
        for (p in enabledProviders()) {
            if (p.sourceId == "custom") continue
            val list = callProviderSafely(p) { prov, overrideUrl -> prov.browse(query, overrideUrl) }
            if (!list.isNullOrEmpty()) {
                return if (customSourceEngine.hasDynamicSources()) {
                    (customSourceEngine.browse(query) + list).distinctBy { it.id }
                } else list
            }
        }
        return customSourceEngine.browse(query)
    }

    /**
     * 多源聚合去重搜索
     */
    fun searchAll(keyword: String, page: Int = 1): List<DramaCard> {
        val merged = LinkedHashMap<String, DramaCard>()
        for (p in enabledProviders()) {
            val items = callProviderSafely(p) { prov, overrideUrl -> prov.search(keyword, page, overrideUrl) }
                ?: emptyList()
            for (card in items) {
                val dedupKey = "${card.title.trim().lowercase()}_${card.category}"
                if (!merged.containsKey(dedupKey)) {
                    merged[dedupKey] = card
                }
            }
        }
        if (merged.isEmpty()) {
            for (card in customSourceEngine.search(keyword, page)) {
                merged[card.id] = card
            }
        }
        return merged.values.toList()
    }

    fun fetchDetail(dramaId: String): DramaDetail? {
        if (dramaId.isBlank()) return null
        val targetProvider = when {
            dramaId.startsWith("hj_") -> huangjuProvider
            dramaId.startsWith("hd_") -> huangdouProvider
            dramaId.startsWith("cs_") || dramaId.startsWith("cms_") || dramaId.startsWith("m3u_") -> customSourceEngine
            else -> hongguoProvider
        }
        if (targetProvider != customSourceEngine && healthTracker.isAvailable(targetProvider.sourceId)) {
            val detail = callProviderSafely(targetProvider) { prov, overrideUrl -> prov.fetchDetail(dramaId, overrideUrl) }
            if (detail != null) {
                if (detail.episodes.none { it.streamUrl.isNotBlank() }) {
                    val matched = customSourceEngine.findDynamicByTitle(detail.title)
                    if (matched != null && matched.episodes.any { it.streamUrl.isNotBlank() }) {
                        return detail.copy(
                            sourceName = "${detail.sourceName} · 已匹配「${matched.sourceName}」直链",
                            episodes = matched.episodes,
                            totalEpisodes = matched.episodes.size
                        )
                    }
                }
                return detail
            }
        }
        return customSourceEngine.fetchDetail(dramaId)
    }

    fun resolveEpisode(dramaId: String, episodeIndex: Int): EpisodeItem? {
        val detail = fetchDetail(dramaId) ?: return null
        return detail.episodes.firstOrNull { it.index == episodeIndex } ?: detail.episodes.firstOrNull()
    }

    fun fetchComments(dramaId: String, episodeIndex: Int = 1): List<CommentItem> {
        if (dramaId.startsWith("hg_") && healthTracker.isAvailable(hongguoProvider.sourceId)) {
            val online = callProviderSafely(hongguoProvider) { prov, overrideUrl ->
                prov.fetchComments(dramaId, episodeIndex, overrideUrl)
            }
            if (!online.isNullOrEmpty()) return online
        }
        return customSourceEngine.fetchComments(dramaId, episodeIndex)
    }

    fun fetchDanmaku(dramaId: String, episodeIndex: Int = 1): List<DanmakuItem> {
        return customSourceEngine.fetchDanmaku(dramaId, episodeIndex)
    }

    /**
     * 查全季（关联季搜索）
     */
    fun fetchRelatedSeasons(dramaId: String): List<DramaCard> {
        val detail = fetchDetail(dramaId) ?: return emptyList()
        val baseTitle = detail.title.substringBefore("：").substringBefore("第").trim()
        return searchAll(baseTitle, 1).filter { it.id != dramaId }.take(6)
    }
}
