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
 * 黄豆短剧站源解析器（对应原版 Go `provider_huangdou.go`）
 *
 * 对接端点（提取自 `libduanju_core.so`）：
 * - `/api/app/playlet-tab/all`
 * - `/api/app/playlet/home/tab/`
 * - `/api/app/playlet/detail/`
 * - `/api/app/playlet-chapter/list/`
 * - `/api/app/vid/h5/m3u8/`
 */
class HuangdouProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : DramaProvider {

    override val sourceId: String = "huangdou"
    override val sourceName: String = "黄豆剧场"
    override val defaultBaseUrl: String = "https://zzzznnn.lkkwip.cn"

    private fun getJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.11.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("Huangdou HTTP ${resp.code()} for $url")
            }
            return resp.body()?.string() ?: throw java.io.IOException("Huangdou empty body")
        }
    }

    override fun fetchHomeRails(baseUrlOverride: String?): List<RailFeed> {
        val cards = fetchCatalog("all", 1, baseUrlOverride)
        if (cards.isEmpty()) return emptyList()
        return listOf(RailFeed("rest", "黄豆精选", "热门短剧直连", cards.take(12)))
    }

    override fun fetchCategories(baseUrlOverride: String?): List<CategoryNode> = emptyList()

    override fun fetchCatalog(category: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val url = "$base/api/app/playlet/home/tab/?tab=$category&page=$page"
        val body = getJson(url)
        return parseCards(body)
    }

    override fun fetchRankings(subRank: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog("rank", page, baseUrlOverride)
    }

    override fun fetchCalendar(dateStr: String, baseUrlOverride: String?): List<DramaCard> = emptyList()

    override fun browse(query: BrowseQuery, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog(query.genre, query.page, baseUrlOverride)
    }

    override fun search(keyword: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$base/api/app/playlet/home/tab/?keyword=$encoded&page=$page"
        val body = getJson(url)
        return parseCards(body)
    }

    override fun fetchDetail(dramaId: String, baseUrlOverride: String?): DramaDetail? {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val cleanId = dramaId.removePrefix("hd_")
        val detailBody = getJson("$base/api/app/playlet/detail/$cleanId")
        val chapterBody = try { getJson("$base/api/app/playlet-chapter/list/$cleanId") } catch (_: Throwable) { null }

        return try {
            val dObj = JsonParser.parseString(detailBody).asJsonObject.getAsJsonObject("data") ?: return null
            val title = dObj.get("title")?.asString ?: return null
            val cover = dObj.get("cover")?.asString ?: ""
            val intro = dObj.get("intro")?.asString ?: ""

            val episodes = mutableListOf<EpisodeItem>()
            if (!chapterBody.isNullOrBlank()) {
                val chArr = JsonParser.parseString(chapterBody).asJsonObject.getAsJsonArray("data")
                if (chArr != null) {
                    for (i in 0 until chArr.size()) {
                        val ch = chArr[i].asJsonObject
                        val idx = ch.get("chapter_num")?.asInt ?: (i + 1)
                        val vid = ch.get("vid")?.asString ?: "${cleanId}_$idx"
                        val m3u8Url = ch.get("m3u8_url")?.asString ?: "$base/api/app/vid/h5/m3u8/$vid"
                        episodes.add(
                            EpisodeItem(
                                index = idx,
                                episodeId = vid,
                                title = "第${idx}集",
                                streamUrl = m3u8Url
                            )
                        )
                    }
                }
            }
            DramaDetail(
                id = dramaId,
                title = title,
                coverUrl = cover,
                sourceId = sourceId,
                sourceName = sourceName,
                category = "真人剧",
                tags = listOf("黄豆剧场"),
                totalEpisodes = maxOf(80, episodes.size),
                isFinished = true,
                score = "9.3",
                playCountText = "精选剧场",
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
            val arr = root.getAsJsonArray("data") ?: return emptyList()
            arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.asString ?: return@mapNotNull null
                DramaCard(
                    id = if (id.startsWith("hd_")) id else "hd_$id",
                    title = title,
                    coverUrl = obj.get("cover")?.asString ?: "",
                    sourceId = sourceId,
                    sourceName = sourceName
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
