package com.juju.tv

import com.juju.tv.model.CustomSourceConfig
import com.juju.tv.provider.CustomSourceEngine
import com.juju.tv.provider.ProviderRegistry
import com.juju.tv.provider.SourceHealthTracker
import com.juju.tv.storage.JuJuRepository
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomSourceAndRegistryTest {

    private lateinit var tempDir: File
    private lateinit var repository: JuJuRepository
    private lateinit var registry: ProviderRegistry
    private var mockBrokenServer: NanoHTTPD? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("juju_source_test").toFile()
        repository = JuJuRepository(tempDir)
        registry = ProviderRegistry(repository)
    }

    @After
    fun tearDown() {
        mockBrokenServer?.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun testParseMacCmsV10AndM3uSelfRescueSourcesAndUnload() {
        val engine = CustomSourceEngine()

        // 1. 测试苹果 MacCMS V10 标准 VOD JSON 解析
        val macCmsJson = """
            {
              "code": 1,
              "list": [
                {
                  "vod_id": 8801,
                  "vod_name": "苍穹之下：决战光明顶",
                  "type_name": "漫剧",
                  "vod_pic": "https://example.com/8801.jpg",
                  "vod_score": "9.8",
                  "vod_content": "<p>高燃武侠漫剧</p>",
                  "vod_play_url": "第1集${'$'}https://cdn.example.com/8801/1.m3u8#第2集${'$'}https://cdn.example.com/8801/2.m3u8"
                }
              ]
            }
        """.trimIndent()
        val cmsCfg = CustomSourceConfig(id = "nas_cms", name = "家庭NAS苹果CMS", url = "http://192.168.1.10/api.php/provide/vod/", type = "json")
        val cmsParsed = engine.loadFromRawContent(cmsCfg, macCmsJson)
        assertEquals(1, cmsParsed.size)
        assertEquals("苍穹之下：决战光明顶", cmsParsed[0].title)
        assertEquals(2, cmsParsed[0].episodes.size)
        assertEquals("https://cdn.example.com/8801/2.m3u8", cmsParsed[0].episodes[1].streamUrl)

        // 2. 测试标准 M3U8 播放列表自救源解析
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://example.com/a.jpg" group-title="真人剧",无双神医 第01集
            https://cdn.example.com/doctor/ep01.mp4
            #EXTINF:-1 tvg-logo="https://example.com/a.jpg" group-title="真人剧",无双神医 第02集
            https://cdn.example.com/doctor/ep02.mp4
        """.trimIndent()
        val m3uCfg = CustomSourceConfig(id = "local_m3u", name = "本地M3U剧单", url = "http://192.168.1.10/drama.m3u", type = "m3u")
        val m3uParsed = engine.loadFromRawContent(m3uCfg, m3uContent)
        assertEquals(1, m3uParsed.size)
        assertEquals("无双神医", m3uParsed[0].title)
        assertEquals(2, m3uParsed[0].episodes.size)

        // 验证热挂载后可直接通过搜索检索到
        assertTrue(engine.search("无双神医", 1).any { it.title == "无双神医" })

        // 验证卸载自定义源后剧目从内存中干净移除
        val removedCount = engine.removeCustomSource("local_m3u")
        assertEquals(1, removedCount)
        assertFalse(engine.search("无双神医", 1).any { it.title == "无双神医" })
    }

    @Test
    fun testCircuitBreakerHealthTrackerAndHttp500FailureRecording() {
        val tracker = SourceHealthTracker(circuitBreakThreshold = 3, cooldownMs = 60_000L)
        assertTrue(tracker.isAvailable("broken_src"))
        tracker.recordFailure("broken_src", "Timeout 1")
        tracker.recordFailure("broken_src", "Timeout 2")
        assertTrue(tracker.isAvailable("broken_src"))
        tracker.recordFailure("broken_src", "Timeout 3")
        assertFalse(tracker.isAvailable("broken_src"))

        tracker.recordSuccess("broken_src", 85L)
        assertTrue(tracker.isAvailable("broken_src"))

        // 验证真实 Provider 返回 HTTP 500 时，ProviderRegistry 会准确记录为 Failure 并触发熔断
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upstream 500")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        mockBrokenServer = server

        val brokenUrl = "http://127.0.0.1:${server.listeningPort}"
        repository.updateSettings {
            it.copy(
                enabledSources = mapOf("hongguo" to true, "huangju" to false, "huangdou" to false, "custom" to true),
                sourceDomainOverrides = mapOf("hongguo" to brokenUrl)
            )
        }

        repeat(3) {
            registry.fetchCatalog("都市", 1)
        }
        assertFalse("Hongguo provider must enter circuit-open state after 3 HTTP 500 errors", registry.healthTracker.isAvailable("hongguo"))
    }

    @Test
    fun testEmptyBuiltInReturnsNullInsteadOfFakeCatalog() {
        val engine = CustomSourceEngine()
        assertTrue(engine.fetchHomeRails().isEmpty())
        assertTrue(engine.fetchDanmaku("hg_1001", 1).isEmpty())
        assertTrue(engine.fetchComments("hg_1001", 1).isEmpty())
        assertNull(engine.fetchDetail("hg_99999"))
    }

    @Test
    fun testHongguoWebSsrParsingAndCustomSourceTitleMatching() {
        val homeHtml = """
            <html><head><script>
            window._ROUTER_DATA = {
              "loaderData": {
                "page": {
                  "homeSections": [
                    {
                      "tab_type": "all",
                      "tab_name": "热播短剧",
                      "video_list": [
                        {
                          "series_id": "7702763145030290457",
                          "series_title": "万妖图录传第十四季",
                          "series_cover": "https://p6-novel.byteimg.com/novel-pic/973dad4339e00fa9a5d1bf71e6431245~tplv-shrink:640:0.image",
                          "episode_cnt": 2,
                          "series_status": 1,
                          "category_list": [{"name": "玄幻"}, {"name": "漫剧"}]
                        }
                      ]
                    }
                  ],
                  "bannerList": []
                }
              }
            };
            </script></head></html>
        """.trimIndent()

        val detailHtml = """
            <html><head><script>
            window._ROUTER_DATA = {
              "loaderData": {
                "detail_page": {
                  "seriesDetail": {
                    "series_id": "7702763145030290457",
                    "series_name": "万妖图录传第十四季",
                    "series_cover": "https://p6-novel.byteimg.com/novel-pic/973dad4339e00fa9a5d1bf71e6431245~tplv-shrink:640:0.image",
                    "series_intro": "百妖谱奇幻故事",
                    "episode_cnt": 2,
                    "series_status": 1,
                    "tags": ["漫剧", "玄幻"],
                    "vid_list": ["v1", "v2"]
                  },
                  "seriesSocialInfo": {
                    "rating": 9.5,
                    "hot_score_data": {"text": "8387万热度"},
                    "reviews": {"reviews": [{"user_name": "剧迷", "content": "好看", "digg_count": 12}]}
                  }
                }
              }
            };
            </script></head></html>
        """.trimIndent()

        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                return when (session.uri) {
                    "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", homeHtml)
                    "/detail" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", detailHtml)
                    "/api/search/result" -> newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"searchList":[{"video_data":{"series_id":"7702763145030290457","series_title":"万妖图录传第十四季","series_cover":"https://p6-novel.byteimg.com/novel-pic/test.image","episode_cnt":2}}]}"""
                    )
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
                }
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        mockBrokenServer = server

        repository.updateSettings {
            it.copy(
                enabledSources = mapOf("hongguo" to true, "huangju" to false, "huangdou" to false, "custom" to true),
                sourceDomainOverrides = mapOf("hongguo" to "http://127.0.0.1:${server.listeningPort}")
            )
        }

        val rails = registry.fetchHomeRails()
        assertTrue(rails.isNotEmpty())
        assertEquals("万妖图录传第十四季", rails[0].items[0].title)
        assertTrue(rails[0].items[0].coverUrl.contains("byteimg.com"))

        // 未挂载自定义源时，红果公开目录返回空直链且不回退到假剧目
        val rawDetail = registry.fetchDetail("hg_7702763145030290457")
        assertNotNull(rawDetail)
        assertEquals("", rawDetail!!.episodes[0].streamUrl)

        // 挂载含同名剧集的 MacCMS 自定义源后，fetchDetail 自动匹配直链
        val cmsJson = """{"list":[{"vod_id":1,"vod_name":"万妖图录传第十四季","vod_play_url":"第1集${'$'}https://cdn.example.com/1.m3u8#第2集${'$'}https://cdn.example.com/2.m3u8"}]}"""
        registry.customSourceEngine.loadFromRawContent(
            CustomSourceConfig(id = "user_cms", name = "自建剧源", url = "local://cms", type = "json"),
            cmsJson
        )
        val matchedDetail = registry.fetchDetail("hg_7702763145030290457")
        assertNotNull(matchedDetail)
        assertEquals("https://cdn.example.com/1.m3u8", matchedDetail!!.episodes[0].streamUrl)
        assertTrue(registry.searchAll("万妖图录").isNotEmpty())
    }

    @Test
    fun testMacCmsRemoteAutoDetailAndLiveSearch() {
        val cmsServer = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val ac = session.parms["ac"] ?: ""
                val wd = session.parms["wd"] ?: ""
                return if (ac == "detail" && wd.isBlank()) {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"list":[{"vod_id":10,"vod_name":"首页测试短剧","vod_pic":"https://example.com/10.jpg","vod_play_url":"第1集${'$'}https://example.com/10_1.mp4"}]}"""
                    )
                } else if (ac == "detail" && wd.contains("搜索")) {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"list":[{"vod_id":11,"vod_name":"搜索命中短剧","vod_pic":"https://example.com/11.jpg","vod_play_url":"第1集${'$'}https://example.com/11_1.m3u8"}]}"""
                    )
                } else {
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"list":[]}""")
                }
            }
        }
        cmsServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        mockBrokenServer = cmsServer

        val engine = CustomSourceEngine()
        val cfg = CustomSourceConfig(
            id = "cms_live",
            name = "CMS接口",
            url = "http://127.0.0.1:${cmsServer.listeningPort}/api.php/provide/vod/",
            type = "json"
        )
        val initial = engine.refreshCustomSource(cfg)
        assertEquals(1, initial.size)
        assertEquals("https://example.com/10_1.mp4", initial[0].episodes[0].streamUrl)

        val searched = engine.search("搜索", 1)
        assertEquals(1, searched.size)
        assertEquals("搜索命中短剧", searched[0].title)
    }
}
