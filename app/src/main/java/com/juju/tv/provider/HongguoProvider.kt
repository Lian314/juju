package com.juju.tv.provider

import com.google.gson.JsonParser
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CategoryNode
import com.juju.tv.model.CommentItem
import com.juju.tv.model.DanmakuItem
import com.juju.tv.model.DramaCard
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.RailFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 红果短剧（Web SSR + App API 双通道）解析器
 *
 * 对接端点（提取自原版 `libduanju_core.so`）：
 * - Web 端：`https://hongguoduanju.com/` (`/category/`, `/detail`, `/player/`, `/search/`, `/incent_resource/suggestion`)
 * - App 端：`https://api5-normal-sinfonlineb.fqnovel.com`
 *   - `/reading/bookapi/bookmall/cell/change/v1/`
 *   - `/reading/distribution/category/landpage/v1/`
 *   - `/reading/bookapi/multi-detail/v1/`
 *   - `/novel/player/multi_video_model/v1/`
 *   - `/novel/commentapi/comment/list/`
 */
class HongguoProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : DramaProvider {

    override val sourceId: String = "hongguo"
    override val sourceName: String = "红果短剧"
    override val defaultBaseUrl: String = "https://api5-normal-sinfonlineb.fqnovel.com"
    val webBaseUrl: String = "https://hongguoduanju.com"

    private fun buildAppQuery(extra: Map<String, String> = emptyMap()): String {
        val id = HongguoProtocol.currentIdentity()
        val params = LinkedHashMap<String, String>()
        params["iid"] = id.installId
        params["device_id"] = id.deviceId
        params["ac"] = "wifi"
        params["channel"] = "gp"
        params["aid"] = HongguoProtocol.AID.toString()
        params["app_name"] = HongguoProtocol.APP_NAME
        params["version_code"] = HongguoProtocol.VERSION_CODE.toString()
        params["version_name"] = HongguoProtocol.VERSION_NAME
        params["device_platform"] = "android"
        params["os"] = "android"
        params["device_type"] = id.deviceModel
        params["device_brand"] = id.deviceBrand
        params["os_api"] = id.osApi.toString()
        params["os_version"] = id.osVersion
        params["openudid"] = id.openUdid
        params["cdid"] = id.cdid
        params.putAll(extra)
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun executeSignedGet(fullUrl: String): String {
        val headers = HongguoProtocol.buildSignedHeaders(fullUrl)
        val reqBuilder = Request.Builder().url(fullUrl)
        for ((k, v) in headers) reqBuilder.header(k, v)
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("Hongguo HTTP ${resp.code()} for $fullUrl")
            }
            return resp.body()?.string() ?: throw java.io.IOException("Hongguo empty body")
        }
    }

    override fun fetchHomeRails(baseUrlOverride: String?): List<RailFeed> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val q = buildAppQuery(mapOf("req_type" to "1", "NeedPersonalRecommend" to "1"))
        val url = "$base/reading/bookapi/bookmall/cell/change/v1/?$q"
        val body = executeSignedGet(url)
        val cards = parseBookmallCards(body)
        if (cards.isEmpty()) return emptyList()

        return listOf(
            RailFeed("hot", "热门短剧", "红果全站实时热播", cards.take(12)),
            RailFeed("category:漫剧", "精品漫剧", "高燃动态漫与3D漫剧", cards.filter { it.category.contains("漫") }.ifEmpty { cards.take(8) }),
            RailFeed("category:真人剧", "王牌真人剧", "都市逆袭 · 古装甜宠", cards.filter { !it.category.contains("漫") }.take(12)),
            RailFeed("category:AI 剧", "先锋 AI 剧", "AIGC 奇幻视觉短剧", cards.takeLast(8))
        )
    }

    override fun fetchCategories(baseUrlOverride: String?): List<CategoryNode> {
        return listOf(
            CategoryNode("真人剧", "真人剧", "type", "HOT"),
            CategoryNode("漫剧", "漫剧", "type", "动漫"),
            CategoryNode("AI 剧", "AI 剧", "type", "AI"),
            CategoryNode("都市", "都市", "genre"),
            CategoryNode("逆袭", "逆袭", "genre"),
            CategoryNode("战神", "战神", "genre"),
            CategoryNode("古装", "古装", "genre"),
            CategoryNode("甜宠", "甜宠", "genre"),
            CategoryNode("重生", "重生", "genre"),
            CategoryNode("悬疑", "悬疑", "genre"),
            CategoryNode("萌宝", "萌宝", "genre"),
            CategoryNode("仙侠", "仙侠", "genre"),
            CategoryNode("年代", "年代", "genre")
        )
    }

    override fun fetchCatalog(category: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val q = buildAppQuery(
            mapOf(
                "category" to category,
                "offset" to ((page - 1) * 20).toString(),
                "limit" to "20"
            )
        )
        val url = "$base/reading/distribution/category/landpage/v1/?$q"
        val body = executeSignedGet(url)
        return parseBookmallCards(body)
    }

    override fun fetchRankings(subRank: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog(subRank, page, baseUrlOverride)
    }

    override fun fetchCalendar(dateStr: String, baseUrlOverride: String?): List<DramaCard> {
        return fetchCatalog("firstonlinetime_new", 1, baseUrlOverride)
    }

    override fun browse(query: BrowseQuery, baseUrlOverride: String?): List<DramaCard> {
        val cat = if (query.genre != "全部") query.genre else query.type
        return fetchCatalog(cat, query.page, baseUrlOverride)
    }

    override fun search(keyword: String, page: Int, baseUrlOverride: String?): List<DramaCard> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val q = buildAppQuery(
            mapOf(
                "query" to keyword,
                "offset" to ((page - 1) * 20).toString(),
                "count" to "20"
            )
        )
        val url = "$base/reading/bookapi/search/uncover_subscribe/v1/?$q"
        val body = executeSignedGet(url)
        return parseBookmallCards(body)
    }

    override fun fetchDetail(dramaId: String, baseUrlOverride: String?): DramaDetail? {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val cleanId = dramaId.removePrefix("hg_")
        val q = buildAppQuery(mapOf("book_id" to cleanId, "series_id" to cleanId))
        val url = "$base/reading/bookapi/multi-detail/v1/?$q"
        val body = executeSignedGet(url)
        return parseDramaDetailJson(dramaId, body)
    }

    override fun resolveEpisodeStream(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): EpisodeItem? {
        val detail = fetchDetail(dramaId, baseUrlOverride) ?: return null
        return detail.episodes.firstOrNull { it.index == episodeIndex } ?: detail.episodes.firstOrNull()
    }

    override fun fetchComments(dramaId: String, episodeIndex: Int, baseUrlOverride: String?): List<CommentItem> {
        val base = (baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl).trimEnd('/')
        val cleanId = dramaId.removePrefix("hg_")
        val q = buildAppQuery(mapOf("book_id" to cleanId, "episode_index" to episodeIndex.toString()))
        val url = "$base/novel/commentapi/comment/list/?$q"
        val body = executeSignedGet(url)
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val arr = root.getAsJsonObject("data")?.getAsJsonArray("comment_list") ?: return emptyList()
            arr.mapIndexed { idx, el ->
                val obj = el.asJsonObject
                CommentItem(
                    id = obj.get("comment_id")?.asString ?: "c_$idx",
                    userName = obj.get("user_name")?.asString ?: "剧迷用户",
                    content = obj.get("text")?.asString ?: "",
                    likeCount = obj.get("digg_count")?.asInt ?: 0,
                    episodeIndex = episodeIndex
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    internal fun parseBookmallCards(jsonText: String): List<DramaCard> {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val dataEl = root.get("data") ?: return emptyList()
            val listArr = when {
                dataEl.isJsonArray -> dataEl.asJsonArray
                dataEl.isJsonObject -> {
                    val dObj = dataEl.asJsonObject
                    dObj.getAsJsonArray("list")
                        ?: dObj.getAsJsonArray("book_list")
                        ?: dObj.getAsJsonArray("series_list")
                        ?: dObj.getAsJsonArray("items")
                }
                else -> null
            } ?: return emptyList()

            val result = mutableListOf<DramaCard>()
            for (el in listArr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val rawId = obj.get("book_id")?.asString
                    ?: obj.get("series_id")?.asString
                    ?: obj.get("id")?.asString
                    ?: continue
                val title = obj.get("book_name")?.asString
                    ?: obj.get("title")?.asString
                    ?: obj.get("series_name")?.asString
                    ?: continue
                val cover = obj.get("thumb_url")?.asString
                    ?: obj.get("cover")?.asString
                    ?: obj.get("cover_url")?.asString
                    ?: ""
                val totalEp = obj.get("total_episode")?.asInt
                    ?: obj.get("serial_count")?.asInt
                    ?: 80
                val category = obj.get("category")?.asString ?: "真人剧"
                val intro = obj.get("abstract")?.asString ?: obj.get("intro")?.asString ?: ""
                val score = obj.get("score")?.asString ?: "9.5"
                result.add(
                    DramaCard(
                        id = if (rawId.startsWith("hg_")) rawId else "hg_$rawId",
                        title = title,
                        coverUrl = cover,
                        sourceId = sourceId,
                        sourceName = sourceName,
                        category = category,
                        totalEpisodes = totalEp,
                        updatedEpisodes = totalEp,
                        score = score,
                        intro = intro,
                        latestEpisodeText = "全${totalEp}集"
                    )
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    internal fun parseDramaDetailJson(dramaId: String, jsonText: String): DramaDetail? {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val dataObj = root.getAsJsonObject("data") ?: root
            val title = dataObj.get("book_name")?.asString ?: dataObj.get("title")?.asString ?: return null
            val cover = dataObj.get("thumb_url")?.asString ?: dataObj.get("cover")?.asString ?: ""
            val intro = dataObj.get("abstract")?.asString ?: dataObj.get("intro")?.asString ?: ""
            val totalEp = dataObj.get("total_episode")?.asInt ?: 80
            val epArray = dataObj.getAsJsonArray("episodes") ?: dataObj.getAsJsonArray("chapter_list")

            val episodes = mutableListOf<EpisodeItem>()
            if (epArray != null) {
                for (i in 0 until epArray.size()) {
                    val epObj = epArray[i].asJsonObject
                    val idx = epObj.get("index")?.asInt ?: (i + 1)
                    val epId = epObj.get("item_id")?.asString ?: epObj.get("episode_id")?.asString ?: "${dramaId}_$idx"
                    val epTitle = epObj.get("title")?.asString ?: "第${idx}集"
                    val playUrl = epObj.get("play_url")?.asString ?: epObj.get("main_url")?.asString ?: ""
                    val cencKey = epObj.get("spade_a")?.asString ?: epObj.get("cenc_key")?.asString ?: ""
                    val cencKid = epObj.get("cenc_kid")?.asString ?: ""
                    episodes.add(
                        EpisodeItem(
                            index = idx,
                            episodeId = epId,
                            title = epTitle,
                            streamUrl = playUrl,
                            isCencEncrypted = cencKey.length == 32,
                            cencKeyHex = cencKey,
                            cencKidHex = cencKid
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
                category = dataObj.get("category")?.asString ?: "真人剧",
                tags = listOf("红果热播", "高清1080P"),
                totalEpisodes = maxOf(totalEp, episodes.size),
                isFinished = true,
                score = dataObj.get("score")?.asString ?: "9.6",
                playCountText = "热播短剧",
                intro = intro,
                episodes = episodes
            )
        } catch (_: Throwable) {
            null
        }
    }
}
