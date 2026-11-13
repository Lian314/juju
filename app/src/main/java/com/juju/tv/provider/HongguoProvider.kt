package com.juju.tv.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.CommentItem
import com.juju.tv.model.CreatorInfo
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 红果短剧公开网页目录解析器（仅读取公开 Web SSR 页面与公开搜索接口）
 *
 * 端点说明（`https://hongguoduanju.com`）：
 * - 首页推荐：`GET /`（解析 `window._ROUTER_DATA.loaderData.page.homeSections` 与 `bannerList`）
 * - 分类片库：`GET /category`、`/category/comic`、`/category/real-drama[/<slug>]`、`/category/ai-drama`
 * - 权威榜单：`GET /rank/hot-drama`、`/rank/new-drama`、`/rank/hot-search-drama`、`/rank/must-watch-drama`、`/rank/prestige-drama`
 * - 公开搜剧：`GET /api/search/result?query=<keyword>`
 * - 剧集详情与热评：`GET /detail?series_id=<id>`
 *
 * 注：红果官网对播放页标记了 `"protectedPlayback": true`，本解析器仅同步公开目录元数据与海报封面，
 * 不绕过版权播放保护；播放时将由 [ProviderRegistry] 自动匹配用户在 [CustomSourceEngine] 配置的自建直链源，
 * 若未配置则向播放器返回空直链并明确提示用户。
 */
class HongguoProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : DramaProvider {

    override val sourceId: String = "hongguo"
    override val sourceName: String = "红果短剧"
    override val defaultBaseUrl: String = "https://hongguoduanju.com"

    companion object {
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val CATEGORY_SLUG_MAP = mapOf(
            "爱情" to "romance",
            "甜宠" to "romance",
            "年代" to "period",
            "逆袭" to "comeback",
            "重生" to "comeback",
            "战神" to "comeback",
            "传奇" to "legend",
            "成长" to "growth",
            "家庭" to "family",
            "家族" to "clan",
            "萌宝" to "cute-kids",
            "悬疑" to "suspense",
            "惊悚" to "thriller",
            "恐怖" to "horror",
            "志怪" to "supernatural",
            "古装" to "costume",
            "玄幻" to "fantasy",
            "仙侠" to "fantasy",
            "奇幻" to "wonder",
            "脑洞" to "wonder",
            "都市" to "urban",
            "青春" to "youth",
            "喜剧" to "comedy",
            "科幻" to "sci-fi",
            "灾难" to "disaster",
            "动作冒险" to "action-adventure",
            "战争" to "war",
            "综艺" to "variety",
            "剧情" to "drama"
        )
    }

    private fun resolveBase(baseUrlOverride: String?): String =
        (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')

    private fun fetchText(fullUrl: String, acceptJson: Boolean = false): String {
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "$defaultBaseUrl/")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header(
                "Accept",
                if (acceptJson) "application/json, text/plain, */*"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Hongguo Web HTTP ${resp.code()} for $fullUrl")
            }
            return resp.body()?.string() ?: throw IOException("Hongguo Web empty body for $fullUrl")
        }
    }

    internal fun extractRouterData(html: String): JsonObject? {
        val markerIdx = html.indexOf("_ROUTER_DATA")
        if (markerIdx < 0) return null
        val eqIdx = html.indexOf('=', markerIdx)
        if (eqIdx < 0) return null
        val startIdx = html.indexOf('{', eqIdx + 1)
        if (startIdx < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JsonParser.parseString(html.substring(startIdx, i + 1)).asJsonObject
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun fetchRouterLoaderData(url: String): JsonObject {
        val html = fetchText(url, acceptJson = false)
        val router = extractRouterData(html)
            ?: throw IOException("Hongguo Web missing _ROUTER_DATA for $url")
        return router.getAsJsonObject("loaderData")
            ?: throw IOException("Hongguo Web missing loaderData for $url")
    }

    @Volatile
    private var cachedHomeBase: String = ""

    @Volatile
    private var cachedHomeAtMs: Long = 0L

    @Volatile
    private var cachedHomeRails: List<RailFeed> = emptyList()

    fun clearCache() {
        cachedHomeAtMs = 0L
        cachedHomeRails = emptyList()
    }

    override fun fetchHomeRails(baseUrlOverride: String?): List<RailFeed> {
        val base = resolveBase(baseUrlOverride)
        val now = System.currentTimeMillis()
        if (cachedHomeBase == base && cachedHomeRails.isNotEmpty() && (now - cachedHomeAtMs) < 180_000L) {
            return cachedHomeRails
        }
        val loaderData = fetchRouterLoaderData("$base/")
        val pageObj = loaderData.getAsJsonObject("page")
            ?: findObjectContaining(loaderData, "homeSections")
            ?: throw IOException("Hongguo home_page missing homeSections")

        val sectionMap = LinkedHashMap<String, MutableList<DramaCard>>()
        sectionMap["all"] = mutableListOf()
        sectionMap["comic"] = mutableListOf()
        sectionMap["human"] = mutableListOf()
        sectionMap["ai"] = mutableListOf()

        val homeSections = pageObj.getAsJsonArray("homeSections")
        if (homeSections != null) {
            for (secEl in homeSections) {
                if (!secEl.isJsonObject) continue
                val secObj = secEl.asJsonObject
                val tabType = secObj.optString("tab_type", "all")
                val videoArr = secObj.getAsJsonArray("video_list") ?: continue
                val defaultCat = when (tabType) {
                    "comic" -> "漫剧"
                    "ai" -> "AI 剧"
                    else -> "真人剧"
                }
                val parsed = parseWebSeriesArray(videoArr, defaultCat)
                sectionMap.getOrPut(tabType) { mutableListOf() }.addAll(parsed)
            }
        }

        // 将 bannerList 的热门首推并入综合热播（补充简介与标签）
        val bannerArr = pageObj.getAsJsonArray("bannerList")
        if (bannerArr != null) {
            sectionMap["all"]?.addAll(parseWebSeriesArray(bannerArr, "真人剧"))
        }

        // 串行补充官网热播榜及漫剧/真人剧/AI剧频道片库，确保 6 列大屏海报墙饱满展示 24+ 部新剧
        val hotSupp = runCatching { fetchRankings("热播榜", 1, base) }.getOrNull().orEmpty()
        val comicSupp = runCatching { fetchCatalog("漫剧", 1, base) }.getOrNull().orEmpty()
        val humanSupp = runCatching { fetchCatalog("真人剧", 1, base) }.getOrNull().orEmpty()
        val aiSupp = runCatching { fetchCatalog("AI 剧", 1, base) }.getOrNull().orEmpty()

        val hotCards = mergeDistinctCards(sectionMap["all"].orEmpty() + hotSupp)
        val comicCards = mergeDistinctCards(sectionMap["comic"].orEmpty() + comicSupp)
        val humanCards = mergeDistinctCards(sectionMap["human"].orEmpty() + humanSupp)
        val aiCards = mergeDistinctCards(sectionMap["ai"].orEmpty() + aiSupp)

        val rails = mutableListOf<RailFeed>()
        if (hotCards.isNotEmpty()) rails.add(RailFeed("hot", "热门短剧", "红果官网实时热播", hotCards))
        if (comicCards.isNotEmpty()) rails.add(RailFeed("category:漫剧", "精品漫剧", "动态漫 · 热血玄幻", comicCards))
        if (humanCards.isNotEmpty()) rails.add(RailFeed("category:真人剧", "王牌真人剧", "都市逆袭 · 古装甜宠", humanCards))
        if (aiCards.isNotEmpty()) rails.add(RailFeed("category:AI 剧", "先锋 AI 剧", "AIGC 奇幻视觉短剧", aiCards))
        if (rails.isNotEmpty()) {
            cachedHomeBase = base
            cachedHomeAtMs = now
            cachedHomeRails = rails
        }
        return rails
    }

    override fun fetchCategories(baseUrlOverride: String?): List<CategoryNode> {
        val base = resolveBase(baseUrlOverride)
        val nodes = mutableListOf(
            CategoryNode("真人剧", "真人剧", "type", "热播"),
            CategoryNode("漫剧", "漫剧", "type", "动态漫"),
            CategoryNode("AI 剧", "AI 剧", "type", "AIGC")
        )
        runCatching {
            val loaderData = fetchRouterLoaderData("$base/category")
            val catPage = loaderData.getAsJsonObject("category_page")
                ?: findObjectContaining(loaderData, "selectorList")
            val selectorList = catPage?.getAsJsonArray("selectorList")
            if (selectorList != null) {
                for (rowEl in selectorList) {
                    val items = rowEl.asJsonObject?.getAsJsonArray("items") ?: continue
                    for (itemEl in items) {
                        val obj = itemEl.asJsonObject ?: continue
                        val showName = obj.optString("show_name")
                        if (showName.isNotBlank() && nodes.none { it.name == showName }) {
                            nodes.add(CategoryNode(showName, showName, "genre"))
                        }
                    }
                }
            }
        }
        if (nodes.size == 3) {
            listOf("都市", "逆袭", "古装", "甜宠", "家庭", "萌宝", "悬疑", "玄幻", "年代")
                .forEach { nodes.add(CategoryNode(it, it, "genre")) }
        }
        return nodes
    }

    override fun fetchCatalog(category: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = resolveBase(baseUrlOverride)
        val cleanCat = category.removePrefix("category:").trim()
        val path = when (cleanCat) {
            "漫剧" -> "/category/comic"
            "AI 剧", "AI剧" -> "/category/ai-drama"
            "真人剧" -> "/category/real-drama"
            "", "全部", "all", "hot" -> "/category"
            else -> {
                val slug = CATEGORY_SLUG_MAP[cleanCat]
                if (slug != null) "/category/real-drama/$slug" else "/category"
            }
        }
        val url = if (page > 1) "$base$path?page=$page" else "$base$path"
        val loaderData = fetchRouterLoaderData(url)
        val catPage = loaderData.getAsJsonObject("category_page")
            ?: loaderData.getAsJsonObject("category_$")
            ?: findObjectContaining(loaderData, "recommendList")
            ?: throw IOException("Hongguo category page missing recommendList for $url")
        val recArr = catPage.getAsJsonArray("recommendList") ?: return emptyList()
        val defaultCat = when (cleanCat) {
            "漫剧" -> "漫剧"
            "AI 剧", "AI剧" -> "AI 剧"
            else -> "真人剧"
        }
        return parseWebSeriesArray(recArr, defaultCat)
    }

    override fun fetchRankings(subRank: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = resolveBase(baseUrlOverride)
        val slug = when (subRank.trim()) {
            "新剧榜" -> "new-drama"
            "飙升榜", "热搜榜" -> "hot-search-drama"
            "必看榜" -> "must-watch-drama"
            "口碑榜" -> "prestige-drama"
            else -> "hot-drama"
        }
        val url = "$base/rank/$slug"
        val loaderData = fetchRouterLoaderData(url)
        val pageObj = loaderData.getAsJsonObject("rank_$slug/page")
            ?: findObjectContaining(loaderData, "content")
            ?: throw IOException("Hongguo rank page missing content for $url")
        val rankArr = pageObj.getAsJsonObject("content")?.getAsJsonArray("rankList") ?: return emptyList()
        return parseRankList(rankArr)
    }

    override fun fetchCalendar(dateStr: String, baseUrlOverride: String?): List<DramaCard> {
        return fetchRankings("新剧榜", 1, baseUrlOverride)
    }

    override fun browse(query: BrowseQuery, baseUrlOverride: String?): List<DramaCard> {
        val target = when {
            query.genre != "全部" && query.genre.isNotBlank() -> query.genre
            query.type != "全部" && query.type.isNotBlank() -> query.type
            else -> "全部"
        }
        var list = fetchCatalog(target, query.page, baseUrlOverride)
        if (query.status == "已完结") {
            list = list.filter { it.isFinished }
        } else if (query.status == "连载中") {
            list = list.filter { !it.isFinished }
        }
        if (query.sort == "评分最高") {
            list = list.sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
        }
        return list
    }

    override fun search(keyword: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val base = resolveBase(baseUrlOverride)
        val encoded = URLEncoder.encode(kw, "UTF-8")
        val jsonBody = fetchText("$base/api/search/result?query=$encoded", acceptJson = true)
        val root = JsonParser.parseString(jsonBody).asJsonObject
        val searchArr = root.getAsJsonArray("searchList") ?: return emptyList()
        val out = mutableListOf<DramaCard>()
        for (el in searchArr) {
            if (!el.isJsonObject) continue
            val itemObj = el.asJsonObject
            val vObj = itemObj.getAsJsonObject("video_data") ?: itemObj
            parseWebSeriesObject(vObj, "真人剧")?.let { out.add(it) }
        }
        return out
    }

    override fun fetchDetail(dramaId: String, baseUrlOverride: String?): DramaDetail? {
        val cleanId = dramaId.removePrefix("hg_").trim()
        if (cleanId.isBlank() || !cleanId.all { it.isDigit() }) return null
        val base = resolveBase(baseUrlOverride)
        val loaderData = fetchRouterLoaderData("$base/detail?series_id=$cleanId")
        val detailPage = loaderData.getAsJsonObject("detail_page")
            ?: findObjectContaining(loaderData, "seriesDetail")
            ?: return null
        val sd = detailPage.getAsJsonObject("seriesDetail") ?: return null
        val social = detailPage.getAsJsonObject("seriesSocialInfo")

        val title = sd.optString("series_name").ifBlank { sd.optString("title") }
        if (title.isBlank()) return null

        val cover = normalizeCover(sd.optString("series_cover").ifBlank { sd.optString("cover") })
        val intro = sd.optString("series_intro").ifBlank { sd.optString("intro") }
        val totalEp = sd.optInt("episode_cnt", sd.getAsJsonObject("series_episode_info")?.optInt("episode_total_cnt", 0) ?: 0)
            .coerceAtLeast(1)
        val statusCode = sd.optInt("series_status", sd.getAsJsonObject("series_episode_info")?.optInt("series_status", 1) ?: 1)
        val isFinished = statusCode == 1 || sd.optString("episode_right_text").startsWith("全")

        val tags = mutableListOf<String>()
        sd.getAsJsonArray("tags")?.forEach { t ->
            if (t.isJsonPrimitive) tags.add(t.asString)
        }
        sd.getAsJsonArray("category_list")?.forEach { c ->
            c.asJsonObject?.optString("name")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
        }
        val category = inferCategory(tags, sd.optString("genre"))

        val ratingVal = social?.optDouble("rating", 0.0) ?: 0.0
        val score = if (ratingVal > 0.0) String.format("%.1f", ratingVal) else "9.2"
        val heatText = social?.getAsJsonObject("hot_score_data")?.optString("text")
            ?: social?.optString("rank_label")
            ?: sd.optString("episode_right_text", "红果热播")

        val creators = mutableListOf<CreatorInfo>()
        sd.getAsJsonArray("celebrities")?.forEach { cEl ->
            val cObj = cEl.asJsonObject ?: return@forEach
            val nick = cObj.optString("nickname")
            if (nick.isNotBlank()) {
                creators.add(
                    CreatorInfo(
                        id = cObj.optString("celebrity_id", nick),
                        name = nick,
                        role = cObj.optString("sub_title", "主演"),
                        avatarUrl = normalizeCover(cObj.optString("avatar"))
                    )
                )
            }
        }

        // 提取公开剧集目录列表；红果官网标记 protectedPlayback，此处 streamUrl 留空，不绕过保护。
        // 若用户在自定义源配置了同名直链片源，将由 ProviderRegistry 自动匹配挂载播放地址。
        val vidList = sd.getAsJsonArray("vid_list")
        val epCount = maxOf(vidList?.size() ?: 0, totalEp)
        val episodes = (1..epCount).map { idx ->
            val vid = if (vidList != null && idx - 1 < vidList.size()) {
                vidList[idx - 1].asString
            } else {
                "${cleanId}_ep_$idx"
            }
            EpisodeItem(
                index = idx,
                episodeId = vid,
                title = "第${idx}集",
                durationSec = 95,
                streamUrl = ""
            )
        }

        return DramaDetail(
            id = if (dramaId.startsWith("hg_")) dramaId else "hg_$cleanId",
            title = title,
            coverUrl = cover,
            sourceId = sourceId,
            sourceName = "$sourceName（公开目录）",
            category = category,
            tags = tags.distinct().take(6).ifEmpty { listOf(category, "红果目录") },
            totalEpisodes = epCount,
            isFinished = isFinished,
            score = score,
            playCountText = heatText,
            intro = intro,
            creators = creators,
            episodes = episodes
        )
    }

    override fun resolveEpisodeStream(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): EpisodeItem? {
        val detail = fetchDetail(dramaId, baseUrlOverride) ?: return null
        return detail.episodes.firstOrNull { it.index == episodeIndex } ?: detail.episodes.firstOrNull()
    }

    override fun fetchComments(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): List<CommentItem> {
        val cleanId = dramaId.removePrefix("hg_").trim()
        if (cleanId.isBlank() || !cleanId.all { it.isDigit() }) return emptyList()
        return try {
            val base = resolveBase(baseUrlOverride)
            val loaderData = fetchRouterLoaderData("$base/detail?series_id=$cleanId")
            val detailPage = loaderData.getAsJsonObject("detail_page")
                ?: findObjectContaining(loaderData, "seriesSocialInfo")
                ?: return emptyList()
            val reviewsArr = detailPage.getAsJsonObject("seriesSocialInfo")
                ?.getAsJsonObject("reviews")
                ?.getAsJsonArray("reviews") ?: return emptyList()
            reviewsArr.mapIndexedNotNull { idx, el ->
                val obj = el.asJsonObject ?: return@mapIndexedNotNull null
                val content = obj.optString("content")
                if (content.isBlank()) return@mapIndexedNotNull null
                CommentItem(
                    id = "hg_rev_${cleanId}_$idx",
                    userName = obj.optString("user_name", "红果剧迷"),
                    avatarUrl = normalizeCover(obj.optString("user_avatar")),
                    content = content,
                    likeCount = obj.optInt("digg_count", 0),
                    episodeIndex = episodeIndex,
                    isLongReview = content.length > 60
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseWebSeriesArray(arr: com.google.gson.JsonArray, defaultCat: String): List<DramaCard> {
        val list = mutableListOf<DramaCard>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            parseWebSeriesObject(el.asJsonObject, defaultCat)?.let { list.add(it) }
        }
        return list
    }

    private fun parseWebSeriesObject(obj: JsonObject, defaultCat: String): DramaCard? {
        val rawId = obj.optString("series_id").ifBlank { obj.optString("seriesId") }
        val title = obj.optString("series_title").ifBlank { obj.optString("series_name") }.ifBlank { obj.optString("title") }
        if (rawId.isBlank() || title.isBlank()) return null

        val cover = normalizeCover(
            obj.optString("series_cover")
                .ifBlank { obj.optString("cover") }
                .ifBlank { obj.optString("background_cover_pc") }
        )
        val totalEp = obj.optInt("episode_cnt", obj.getAsJsonObject("series_episode_info")?.optInt("episode_total_cnt", 0) ?: 0)
            .coerceAtLeast(1)
        val statusCode = obj.optInt("series_status", obj.getAsJsonObject("series_episode_info")?.optInt("series_status", 1) ?: 1)
        val rightText = obj.optString("episode_right_text")
        val isFinished = statusCode == 1 || rightText.startsWith("全")

        val tags = mutableListOf<String>()
        obj.getAsJsonArray("tags")?.forEach { if (it.isJsonPrimitive) tags.add(it.asString) }
        obj.getAsJsonArray("category_list")?.forEach { it.asJsonObject?.optString("name")?.takeIf { n -> n.isNotBlank() }?.let { n -> tags.add(n) } }
        val category = inferCategory(tags, obj.optString("genre"), defaultCat)

        return DramaCard(
            id = if (rawId.startsWith("hg_")) rawId else "hg_$rawId",
            title = title,
            coverUrl = cover,
            sourceId = sourceId,
            sourceName = sourceName,
            category = category,
            tags = tags.distinct().take(4),
            totalEpisodes = totalEp,
            updatedEpisodes = totalEp,
            isFinished = isFinished,
            score = obj.optString("score", "9.3"),
            playCountText = obj.getAsJsonObject("hot_score_data")?.optString("text", "") ?: "红果热播",
            intro = obj.optString("series_intro"),
            latestEpisodeText = rightText.ifBlank { if (isFinished) "全${totalEp}集" else "更新至${totalEp}集" }
        )
    }

    private fun parseRankList(arr: com.google.gson.JsonArray): List<DramaCard> {
        val list = mutableListOf<DramaCard>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val rawId = obj.optString("seriesId").ifBlank { obj.optString("id") }
            val title = obj.optString("title")
            if (rawId.isBlank() || title.isBlank()) continue
            val cover = normalizeCover(obj.optString("cover"))
            val epCount = obj.optInt("episodeCount", 60).coerceAtLeast(1)
            val tags = mutableListOf<String>()
            obj.getAsJsonArray("tags")?.forEach { if (it.isJsonPrimitive) tags.add(it.asString) }
            val category = inferCategory(tags, "", "真人剧")
            val scoreRaw = obj.optString("scoreText", "9.4分").removeSuffix("分").ifBlank { "9.4" }
            val heat = obj.optString("primaryMetricText").ifBlank { obj.optString("heatText", "热播") }
            list.add(
                DramaCard(
                    id = if (rawId.startsWith("hg_")) rawId else "hg_$rawId",
                    title = title,
                    coverUrl = cover,
                    sourceId = sourceId,
                    sourceName = sourceName,
                    category = category,
                    tags = tags.take(4),
                    totalEpisodes = epCount,
                    updatedEpisodes = epCount,
                    isFinished = true,
                    score = scoreRaw,
                    playCountText = heat,
                    intro = obj.optString("description"),
                    latestEpisodeText = "${heat.take(10)} · 全${epCount}集"
                )
            )
        }
        return list
    }

    private fun inferCategory(tags: List<String>, genre: String, fallback: String = "真人剧"): String {
        return when {
            tags.any { it.contains("漫剧") || it.contains("动漫") } -> "漫剧"
            tags.any { it.contains("AI", ignoreCase = true) } -> "AI 剧"
            genre == "205" && fallback != "真人剧" -> fallback
            else -> fallback
        }
    }

    private fun mergeDistinctCards(cards: List<DramaCard>): List<DramaCard> {
        val map = LinkedHashMap<String, DramaCard>()
        for (c in cards) {
            if (!map.containsKey(c.id)) map[c.id] = c
        }
        return map.values.toList()
    }

    private fun normalizeCover(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return trimmed
    }

    private fun findObjectContaining(parent: JsonObject, childKey: String): JsonObject? {
        for (entry in parent.entrySet()) {
            val v = entry.value
            if (v.isJsonObject) {
                val obj = v.asJsonObject
                if (obj.has(childKey)) return obj
            }
        }
        return null
    }

    private fun JsonObject.optString(key: String, default: String = ""): String {
        val el: JsonElement = this.get(key) ?: return default
        if (el.isJsonNull || !el.isJsonPrimitive) return default
        return el.asString ?: default
    }

    private fun JsonObject.optInt(key: String, default: Int = 0): Int {
        val el = this.get(key) ?: return default
        if (el.isJsonNull || !el.isJsonPrimitive) return default
        return runCatching { el.asInt }.getOrDefault(default)
    }

    private fun JsonObject.optDouble(key: String, default: Double = 0.0): Double {
        val el = this.get(key) ?: return default
        if (el.isJsonNull || !el.isJsonPrimitive) return default
        return runCatching { el.asDouble }.getOrDefault(default)
    }
}
