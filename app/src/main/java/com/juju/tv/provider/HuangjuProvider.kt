package com.juju.tv.provider

import com.google.gson.JsonParser
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 黄剧站源解析器（对应原版 Go `provider_huangju.go`）
 *
 * 对接端点（提取自 `libduanju_core.so`）：
 * - `https://api.huangju.net/api/home/contentOptions`
 * - `https://api.huangju.net/api/theater/exploreList`
 * - `https://api.huangju.net/api/search/result`
 * - `https://huangju.net/api/playlet/detail`
 * - `https://huangju.net/api/playlet/play`
 */
class HuangjuProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : DramaProvider {

    override val sourceId: String = "huangju"
    override val sourceName: String = "黄剧短剧"
    override val defaultBaseUrl: String = "https://api.huangju.net"

    private fun getJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; JMGO N1 Air) AppleWebKit/537.36 Chrome/116.0.0.0 Safari/537.36")
            .header("Referer", "https://huangju.net/")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("Huangju HTTP ${resp.code()} for $url")
            }
            return resp.body()?.string() ?: throw java.io.IOException("Huangju empty body")
        }
    }

    override fun fetchHomeRails(baseUrlOverride: String?): List<RailFeed> {
        val cards = fetchCatalog("全部", 1, baseUrlOverride)
        if (cards.isEmpty()) return emptyList()
        return listOf(
            RailFeed("hot", "黄剧热播", "全网同步更新", cards.take(12))
        )
    }

    override fun fetchCategories(baseUrlOverride: String?): List<CategoryNode> {
        return listOf(
            CategoryNode("都市", "都市", "genre"),
            CategoryNode("逆袭", "逆袭", "genre"),
            CategoryNode("古装", "古装", "genre"),
            CategoryNode("甜宠", "甜宠", "genre")
        )
    }

    override fun fetchCatalog(category: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val encodedCat = URLEncoder.encode(category, "UTF-8")
        val url = "$base/api/theater/exploreList?category=$encodedCat&page=$page&pageSize=20"
        val body = getJson(url)
        return parseCards(body)
    }

    override fun fetchRankings(subRank: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog(subRank, page, baseUrlOverride)
    }

    override fun fetchCalendar(dateStr: String, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog("最新", 1, baseUrlOverride)
    }

    override fun browse(query: BrowseQuery, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog(query.genre, query.page, baseUrlOverride)
    }

    override fun search(keyword: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val encodedKw = URLEncoder.encode(keyword, "UTF-8")
        val url = "$base/api/search/result?keywords=$encodedKw&page=$page"
        val body = getJson(url)
        return parseCards(body)
    }

    override fun fetchDetail(dramaId: String, baseUrlOverride: String?): DramaDetail? {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val cleanId = dramaId.removePrefix("hj_")
        val url = "$base/api/playlet/detail?drama_id=$cleanId"
        val body = getJson(url)
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val data = root.getAsJsonObject("data") ?: root
            val title = data.get("title")?.asString ?: return null
            val cover = data.get("cover")?.asString ?: ""
            val intro = data.get("intro")?.asString ?: ""
            val totalEp = data.get("total_episodes")?.asInt ?: 80
            val chapters = data.getAsJsonArray("chapters")
            val episodes = mutableListOf<EpisodeItem>()
            if (chapters != null) {
                for (i in 0 until chapters.size()) {
                    val ch = chapters[i].asJsonObject
                    val idx = ch.get("index")?.asInt ?: (i + 1)
                    val streamUrl = ch.get("url")?.asString ?: ch.get("m3u8_url")?.asString ?: ""
                    episodes.add(
                        EpisodeItem(
                            index = idx,
                            episodeId = "${dramaId}_$idx",
                            title = ch.get("title")?.asString ?: "第${idx}集",
                            streamUrl = streamUrl,
                            headers = mapOf("Referer" to "https://huangju.net/")
                        )
                    )
                }
            }
            DramaDetail(
                id = dramaId,
                title = title,
                coverUrl = cover,
                sourceId = sourceId,
                sourceName = sourceName,
                category = "真人剧",
                tags = listOf("黄剧精选"),
                totalEpisodes = maxOf(totalEp, episodes.size),
                isFinished = true,
                score = "9.4",
                playCountText = "高分短剧",
                intro = intro,
                episodes = episodes
            )
        } catch (_: Throwable) {
            null
        }
    }

    override fun resolveEpisodeStream(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): EpisodeItem? {
        return fetchDetail(dramaId, baseUrlOverride)?.episodes?.firstOrNull { it.index == episodeIndex }
    }

    private fun parseCards(jsonText: String): List<DramaCard> {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val arr = root.getAsJsonObject("data")?.getAsJsonArray("list")
                ?: root.getAsJsonArray("data")
                ?: return emptyList()
            val out = mutableListOf<DramaCard>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = obj.get("drama_id")?.asString ?: obj.get("id")?.asString ?: continue
                val title = obj.get("title")?.asString ?: continue
                val cover = obj.get("cover")?.asString ?: obj.get("image")?.asString ?: ""
                val total = obj.get("total_episodes")?.asInt ?: 80
                out.add(
                    DramaCard(
                        id = if (id.startsWith("hj_")) id else "hj_$id",
                        title = title,
                        coverUrl = cover,
                        sourceId = sourceId,
                        sourceName = sourceName,
                        totalEpisodes = total,
                        updatedEpisodes = total,
                        latestEpisodeText = "全${total}集"
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
