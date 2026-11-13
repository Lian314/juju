package com.juju.tv.provider

import com.google.gson.JsonParser
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.CommentItem
import com.juju.tv.model.CustomSourceConfig
import com.juju.tv.model.DanmakuItem
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 去中心化自定义站源与片源聚合引擎（「聚聚」合规开放站源核心）
 *
 * 1. 支持解析三种通用去中心化源格式（可通过手机扫码实时热添加）：
 *    - 格式 A：聚聚标准 JSON 源 (`{"dramas":[...]}`)
 *    - 格式 B：苹果 CMS / MacCMS V10 VOD 接口 (`{"list":[{"vod_id":..., "vod_name":..., "vod_play_url":"第1集$https://...#第2集$https://..."}]}`)
 *    - 格式 C：标准 M3U / M3U8 列表 (`#EXTINF:-1 tvg-logo="..." group-title="...",剧名 第01集`)
 * 2. 支持远程 MacCMS V10 接口自动补全 `?ac=detail` 与在线搜索 (`?ac=detail&wd=...`)；
 * 3. 已移除失效的境外测试假剧库，不再返回不可用死链，并支持按剧名自动匹配红果公开目录展示页。
 */
class CustomSourceEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : DramaProvider {

    override val sourceId: String = "custom"
    override val sourceName: String = "自定义片源"
    override val defaultBaseUrl: String = "local://custom"

    private val dynamicConfigs = ConcurrentHashMap<String, CustomSourceConfig>()
    private val dynamicDetails = ConcurrentHashMap<String, DramaDetail>()

    /**
     * 解析并挂载用户注入的自定义 JSON（聚聚格式或 MacCMS V10 格式）或 M3U 文本
     */
    fun loadFromRawContent(sourceConfig: CustomSourceConfig, rawContent: String): List<DramaDetail> {
        val trimmed = rawContent.trim()
        val parsed = if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || sourceConfig.type.equals("m3u", ignoreCase = true)) {
            parseM3uCatalog(sourceConfig, trimmed)
        } else {
            parseJsonCatalog(sourceConfig, trimmed)
        }
        removeCustomSource(sourceConfig.id)
        dynamicConfigs[sourceConfig.id] = sourceConfig
        for (detail in parsed) {
            dynamicDetails[detail.id] = detail
        }
        return parsed
    }

    /**
     * 卸载指定 ID 的自定义站源及其挂载的全部剧集
     */
    fun removeCustomSource(customSourceId: String): Int {
        if (customSourceId.isBlank()) return 0
        dynamicConfigs.remove(customSourceId)
        var removed = 0
        val iterator = dynamicDetails.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sourceId == customSourceId ||
                entry.key.startsWith("cs_${customSourceId}_") ||
                entry.key.startsWith("cms_${customSourceId}_") ||
                entry.key.startsWith("m3u_${customSourceId}_")
            ) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    fun hasDynamicSources(): Boolean = dynamicDetails.isNotEmpty() || dynamicConfigs.isNotEmpty()

    fun getDynamicCards(): List<DramaCard> = dynamicDetails.values.map { it.toCard() }

    /**
     * 按剧名在已挂载的自定义源中查找可播放剧集（用于红果公开目录与自有片源联动匹配）
     */
    fun findDynamicByTitle(title: String): DramaDetail? {
        val normTarget = normalizeTitle(title)
        if (normTarget.isBlank()) return null
        val playable = dynamicDetails.values.filter { d -> d.episodes.any { it.streamUrl.isNotBlank() } }
        playable.firstOrNull { normalizeTitle(it.title) == normTarget }?.let { return it }
        if (normTarget.length >= 3) {
            playable.firstOrNull { d ->
                val normCand = normalizeTitle(d.title)
                normCand.contains(normTarget) || normTarget.contains(normCand)
            }?.let { return it }
        }
        return null
    }

    private fun normalizeTitle(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s:：·—\\-!！?？《》「」\"'.,，。]"), "")

    private fun appendQuery(baseUrl: String, queryPart: String): String {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl$sep$queryPart"
    }

    private fun fetchUrlBody(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; JMGO N1 Air) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body()?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从远程 URL 拉取自定义源并挂载（兼容 MacCMS V10 自动补全 `?ac=detail`）
     */
    fun refreshCustomSource(sourceConfig: CustomSourceConfig): List<DramaDetail> {
        if (!sourceConfig.enabled || sourceConfig.url.isBlank() || sourceConfig.url.startsWith("local://")) {
            return emptyList()
        }
        dynamicConfigs[sourceConfig.id] = sourceConfig

        val cleanPath = sourceConfig.url.substringBefore("?").lowercase()
        val isM3u = sourceConfig.type.equals("m3u", ignoreCase = true) ||
            cleanPath.endsWith(".m3u") || cleanPath.endsWith(".m3u8")

        if (!isM3u && !sourceConfig.url.contains("ac=", ignoreCase = true) && !cleanPath.endsWith(".json")) {
            val cmsDetailUrl = appendQuery(sourceConfig.url, "ac=detail&pg=1")
            val detailBody = fetchUrlBody(cmsDetailUrl)
            if (!detailBody.isNullOrBlank()) {
                val parsedDetail = loadFromRawContent(sourceConfig, detailBody)
                if (parsedDetail.isNotEmpty() && parsedDetail.any { it.episodes.isNotEmpty() }) {
                    return parsedDetail
                }
            }
        }

        val body = fetchUrlBody(sourceConfig.url) ?: return emptyList()
        return loadFromRawContent(sourceConfig, body)
    }

    fun parseJsonCatalog(sourceConfig: CustomSourceConfig, jsonText: String): List<DramaDetail> {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            // 兼容 1：聚聚原生格式 {"dramas": [...]}
            val dramasArr = root.getAsJsonArray("dramas") ?: root.getAsJsonArray("data")
            // 兼容 2：MacCMS V10 标准 VOD 接口 {"list": [{"vod_id":..., "vod_name":..., "vod_play_url":...}]}
            val macCmsArr = root.getAsJsonArray("list")

            val result = mutableListOf<DramaDetail>()
            if (dramasArr != null) {
                for (el in dramasArr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val rawId = obj.get("id")?.asString ?: continue
                    val id = if (rawId.startsWith("cs_")) rawId else "cs_${sourceConfig.id}_$rawId"
                    val title = obj.get("title")?.asString ?: continue
                    val cover = obj.get("coverUrl")?.asString ?: obj.get("cover")?.asString ?: ""
                    val category = obj.get("category")?.asString ?: "真人剧"
                    val intro = obj.get("intro")?.asString ?: ""
                    val score = obj.get("score")?.asString ?: "9.5"
                    val epArr = obj.getAsJsonArray("episodes")
                    val episodes = mutableListOf<EpisodeItem>()
                    if (epArr != null) {
                        for (i in 0 until epArr.size()) {
                            val epObj = epArr[i].asJsonObject
                            val idx = epObj.get("index")?.asInt ?: (i + 1)
                            val url = epObj.get("url")?.asString ?: epObj.get("streamUrl")?.asString ?: ""
                            episodes.add(
                                EpisodeItem(
                                    index = idx,
                                    episodeId = "${id}_$idx",
                                    title = epObj.get("title")?.asString ?: "第${idx}集",
                                    streamUrl = url,
                                    isCencEncrypted = epObj.get("isCenc")?.asBoolean ?: false,
                                    cencKeyHex = epObj.get("cencKeyHex")?.asString ?: ""
                                )
                            )
                        }
                    }
                    result.add(
                        DramaDetail(
                            id = id,
                            title = title,
                            coverUrl = cover,
                            sourceId = sourceConfig.id,
                            sourceName = sourceConfig.name,
                            category = category,
                            tags = listOf(category, sourceConfig.name),
                            totalEpisodes = maxOf(episodes.size, obj.get("totalEpisodes")?.asInt ?: episodes.size),
                            isFinished = true,
                            score = score,
                            playCountText = "自建源直连",
                            intro = intro,
                            episodes = episodes
                        )
                    )
                }
            } else if (macCmsArr != null) {
                for (el in macCmsArr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val vodId = obj.get("vod_id")?.asString ?: continue
                    val id = "cms_${sourceConfig.id}_$vodId"
                    val title = obj.get("vod_name")?.asString ?: continue
                    val cover = obj.get("vod_pic")?.asString ?: ""
                    val category = obj.get("type_name")?.asString ?: "真人剧"
                    val rawIntro = obj.get("vod_content")?.asString?.takeIf { it.isNotBlank() }
                        ?: obj.get("vod_blurb")?.asString ?: ""
                    val intro = rawIntro.replace(Regex("<[^>]*>"), "").trim()
                    val playUrlRaw = obj.get("vod_play_url")?.asString ?: ""
                    val episodes = parseMacCmsPlayUrl(id, playUrlRaw)
                    result.add(
                        DramaDetail(
                            id = id,
                            title = title,
                            coverUrl = cover,
                            sourceId = sourceConfig.id,
                            sourceName = sourceConfig.name,
                            category = category,
                            tags = listOf(category, "CMS直连"),
                            totalEpisodes = maxOf(episodes.size, 1),
                            isFinished = true,
                            score = obj.get("vod_score")?.asString?.takeIf { it.isNotBlank() && it != "0.0" } ?: "9.4",
                            playCountText = obj.get("vod_remarks")?.asString ?: "全集",
                            intro = intro,
                            releaseYear = obj.get("vod_year")?.asString ?: "2026",
                            episodes = episodes
                        )
                    )
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseMacCmsPlayUrl(dramaId: String, vodPlayUrl: String): List<EpisodeItem> {
        if (vodPlayUrl.isBlank()) return emptyList()
        // 如果存在多组播放源（$$$ 分隔），优先选包含 .m3u8 或 .mp4 的一组
        val groups = vodPlayUrl.split("$$$")
        val chosenGroup = groups.firstOrNull { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) }
            ?: groups.first()
        val items = chosenGroup.split("#").filter { it.isNotBlank() }
        return items.mapIndexedNotNull { idx, raw ->
            val parts = raw.split("$", limit = 2)
            val epTitle = if (parts.size == 2) parts[0].trim() else "第${idx + 1}集"
            val url = if (parts.size == 2) parts[1].trim() else parts[0].trim()
            if (url.isEmpty()) null
            else EpisodeItem(
                index = idx + 1,
                episodeId = "${dramaId}_${idx + 1}",
                title = epTitle.ifBlank { "第${idx + 1}集" },
                streamUrl = url
            )
        }
    }

    fun parseM3uCatalog(sourceConfig: CustomSourceConfig, m3uText: String): List<DramaDetail> {
        val grouped = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        val covers = mutableMapOf<String, String>()
        val categories = mutableMapOf<String, String>()

        var pendingTitle = ""
        var pendingLogo = ""
        var pendingGroup = "真人剧"

        for (rawLine in m3uText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingLogo = Regex("""tvg-logo="([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""
                pendingGroup = Regex("""group-title="([^"]+)"""").find(line)?.groupValues?.get(1) ?: "真人剧"
                pendingTitle = line.substringAfterLast(",").trim()
            } else if (!line.startsWith("#") && pendingTitle.isNotEmpty()) {
                val dramaName = pendingTitle.replace(Regex("""[\s#_-]*第?\d+[集话期].*$"""), "").trim().ifEmpty { pendingTitle }
                val epLabel = pendingTitle.removePrefix(dramaName).trim().ifEmpty { "第1集" }
                grouped.getOrPut(dramaName) { mutableListOf() }.add(epLabel to line)
                if (pendingLogo.isNotEmpty()) covers[dramaName] = pendingLogo
                categories[dramaName] = pendingGroup
                pendingTitle = ""
            }
        }

        var idxCounter = 1
        return grouped.map { (dramaTitle, epPairs) ->
            val id = "m3u_${sourceConfig.id}_${idxCounter++}"
            val episodes = epPairs.mapIndexed { i, (epName, url) ->
                EpisodeItem(
                    index = i + 1,
                    episodeId = "${id}_${i + 1}",
                    title = epName,
                    streamUrl = url
                )
            }
            DramaDetail(
                id = id,
                title = dramaTitle,
                coverUrl = covers[dramaTitle] ?: "",
                sourceId = sourceConfig.id,
                sourceName = sourceConfig.name,
                category = categories[dramaTitle] ?: "真人剧",
                tags = listOf(categories[dramaTitle] ?: "真人剧", "M3U直连"),
                totalEpisodes = episodes.size,
                isFinished = true,
                score = "9.5",
                playCountText = "共${episodes.size}集",
                intro = "来自自定义 M3U 源「${sourceConfig.name}」的直连剧集。",
                episodes = episodes
            )
        }
    }

    private fun allDetails(): Collection<DramaDetail> = dynamicDetails.values

    private fun DramaDetail.toCard(): DramaCard = DramaCard(
        id = id,
        title = title,
        coverUrl = coverUrl,
        sourceId = sourceId,
        sourceName = sourceName,
        category = category,
        tags = tags,
        totalEpisodes = totalEpisodes,
        updatedEpisodes = episodes.size.coerceAtLeast(totalEpisodes),
        isFinished = isFinished,
        score = score,
        playCountText = playCountText,
        intro = intro,
        releaseDate = releaseYear,
        latestEpisodeText = if (isFinished) "全${totalEpisodes}集" else "更新至${episodes.size}集"
    )

    override fun fetchHomeRails(baseUrlOverride: String?): List<RailFeed> {
        val allCards = allDetails().map { it.toCard() }
        if (allCards.isEmpty()) return emptyList()
        val rails = mutableListOf(
            RailFeed("hot", "自定义直连片库", "已配置站源精选剧集", allCards.take(24))
        )
        val comic = allCards.filter { it.category.contains("漫") }
        if (comic.isNotEmpty()) rails.add(RailFeed("category:漫剧", "精品漫剧", "来自自定义片源", comic))
        val human = allCards.filter { !it.category.contains("漫") && !it.category.contains("AI", ignoreCase = true) }
        if (human.isNotEmpty()) rails.add(RailFeed("category:真人剧", "真人短剧", "来自自定义片源", human))
        val ai = allCards.filter { it.category.contains("AI", ignoreCase = true) }
        if (ai.isNotEmpty()) rails.add(RailFeed("category:AI 剧", "先锋 AI 剧", "来自自定义片源", ai))
        return rails
    }

    override fun fetchCategories(baseUrlOverride: String?): List<CategoryNode> = listOf(
        CategoryNode("真人剧", "真人剧", "type", "热播"),
        CategoryNode("漫剧", "漫剧", "type", "高燃"),
        CategoryNode("AI 剧", "AI 剧", "type", "先锋"),
        CategoryNode("都市", "都市", "genre"),
        CategoryNode("逆袭", "逆袭", "genre"),
        CategoryNode("战神", "战神", "genre"),
        CategoryNode("古装", "古装", "genre"),
        CategoryNode("甜宠", "甜宠", "genre"),
        CategoryNode("重生", "重生", "genre"),
        CategoryNode("悬疑", "悬疑", "genre"),
        CategoryNode("仙侠", "仙侠", "genre"),
        CategoryNode("脑洞", "脑洞", "genre")
    )

    override fun fetchCatalog(category: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val allCards = allDetails().map { it.toCard() }
        if (allCards.isEmpty()) return emptyList()
        if (category.isBlank() || category == "全部" || category == "all" || category == "hot") {
            return allCards
        }
        val clean = category.removePrefix("category:").trim()
        return allCards.filter { card ->
            card.category.contains(clean, ignoreCase = true) ||
                card.tags.any { it.contains(clean, ignoreCase = true) }
        }
    }

    override fun fetchRankings(subRank: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val allCards = allDetails().map { it.toCard() }
        return when (subRank) {
            "新剧榜" -> allCards.sortedByDescending { it.releaseDate }
            "飙升榜" -> allCards.reversed()
            "必看榜" -> allCards.sortedByDescending { it.score }
            else -> allCards
        }
    }

    override fun fetchCalendar(dateStr: String, baseUrlOverride: String?): List<DramaCard> {
        return allDetails().map { it.toCard() }.take(12)
    }

    override fun browse(query: BrowseQuery, baseUrlOverride: String?): List<DramaCard> {
        var list = allDetails().map { it.toCard() }
        if (query.type != "全部" && query.type.isNotBlank()) {
            list = list.filter { it.category.contains(query.type, ignoreCase = true) }
        }
        if (query.genre != "全部" && query.genre.isNotBlank()) {
            list = list.filter { card -> card.tags.any { it.contains(query.genre, ignoreCase = true) } }
        }
        if (query.status == "已完结") {
            list = list.filter { it.isFinished }
        } else if (query.status == "连载中") {
            list = list.filter { !it.isFinished }
        }
        if (query.sort == "评分最高") {
            list = list.sortedByDescending { it.score }
        }
        return list
    }

    override fun search(keyword: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return allDetails().map { it.toCard() }.take(24)

        val localHits = allDetails().filter { d ->
            d.title.contains(kw, ignoreCase = true) ||
                d.intro.contains(kw, ignoreCase = true) ||
                d.tags.any { it.contains(kw, ignoreCase = true) } ||
                d.creators.any { it.name.contains(kw, ignoreCase = true) }
        }.map { it.toCard() }
        if (localHits.isNotEmpty()) return localHits

        // 对已挂载的远程苹果 CMS 接口自动发起实时 `?ac=detail&wd=` 检索
        val remoteHits = mutableListOf<DramaCard>()
        val encoded = URLEncoder.encode(kw, "UTF-8")
        for (cfg in dynamicConfigs.values) {
            if (!cfg.enabled || cfg.url.isBlank() || cfg.url.startsWith("local://")) continue
            val cleanPath = cfg.url.substringBefore("?").lowercase()
            if (cfg.type.equals("m3u", ignoreCase = true) || cleanPath.endsWith(".m3u") || cleanPath.endsWith(".m3u8")) continue
            val searchUrl = appendQuery(cfg.url, "ac=detail&wd=$encoded&pg=$page")
            val body = fetchUrlBody(searchUrl) ?: continue
            val parsed = parseJsonCatalog(cfg, body)
            for (d in parsed) {
                dynamicDetails[d.id] = d
                remoteHits.add(d.toCard())
            }
        }
        return remoteHits
    }

    override fun fetchDetail(dramaId: String, baseUrlOverride: String?): DramaDetail? {
        return dynamicDetails[dramaId]
    }

    override fun resolveEpisodeStream(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): EpisodeItem? {
        val detail = fetchDetail(dramaId, baseUrlOverride) ?: return null
        return detail.episodes.firstOrNull { it.index == episodeIndex } ?: detail.episodes.firstOrNull()
    }

    override fun fetchComments(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): List<CommentItem> {
        return emptyList()
    }

    override fun fetchDanmaku(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): List<DanmakuItem> {
        return emptyList()
    }
}
