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
}
