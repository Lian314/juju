package com.juju.tv

import com.google.gson.JsonParser
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.provider.ProviderRegistry
import com.juju.tv.remote.LanRemoteServer
import com.juju.tv.storage.JuJuRepository
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files

class LanRemoteServerTest {

    private lateinit var tempDir: File
    private lateinit var repository: JuJuRepository
    private lateinit var registry: ProviderRegistry
    private lateinit var governor: MemoryGovernor
    private lateinit var server: LanRemoteServer
    private var mockAssetServer: NanoHTTPD? = null
    private val client = OkHttpClient()
    private var baseUrl = ""

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("juju_lan_test").toFile()
        repository = JuJuRepository(tempDir)
        registry = ProviderRegistry(repository)
        governor = MemoryGovernor()
        server = LanRemoteServer(
            port = 0,
            repository = repository,
            providerRegistry = registry,
            memoryGovernor = governor
        )
        val boundPort = server.startServer()
        baseUrl = "http://127.0.0.1:$boundPort"
    }

    @After
    fun tearDown() {
        server.stop()
        mockAssetServer?.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun testRemoteSpaAndCoreRestApis() {
        // 1. 验证根路由 `/` 手机遥控 SPA 包含 _pauseAware 与串行轨道拉取逻辑
        client.newCall(Request.Builder().url("$baseUrl/").build()).execute().use { resp ->
            assertEquals(200, resp.code())
            val html = resp.body()?.string() ?: ""
            assertTrue(html.contains("_pauseAware"))
            assertTrue(html.contains("visibilitychange"))
            assertTrue(html.contains("loadHomeSerially"))
        }

        // 2. 验证 `/api/hello` 握手与客户端连通回调
        var helloTriggered = false
        server.onClientConnectedCallback = { helloTriggered = true }
        client.newCall(Request.Builder().url("$baseUrl/api/hello").build()).execute().use { resp ->
            assertEquals(200, resp.code())
            val obj = JsonParser.parseString(resp.body()!!.string()).asJsonObject
            assertTrue(obj.get("ok").asBoolean)
            assertTrue(helloTriggered)
        }

        // 3. 验证 `/api/play` 与 `/api/control` 远程控制回调
        var playedDramaId = ""
        var playedEp = 0
        var lastCtrlAction = ""
        server.onRemotePlayCommand = { id, ep ->
            playedDramaId = id
            playedEp = ep
        }
        server.onRemoteControlCommand = { act, _ ->
            lastCtrlAction = act
        }

        val playBody = RequestBody.create(MediaType.parse("application/json"), """{"id":"hg_1001","index":6}""")
        client.newCall(Request.Builder().url("$baseUrl/api/play").post(playBody).build()).execute().use { resp ->
            assertEquals(200, resp.code())
            assertEquals("hg_1001", playedDramaId)
            assertEquals(6, playedEp)
        }

        val ctrlBody = RequestBody.create(MediaType.parse("application/json"), """{"action":"next"}""")
        client.newCall(Request.Builder().url("$baseUrl/api/control").post(ctrlBody).build()).execute().use { resp ->
            assertEquals(200, resp.code())
            assertEquals("next", lastCtrlAction)
        }

        // 4. 验证 `/api/webdav-session` 临时令牌申请、非法令牌拦截 (403) 与配置保存
        var token = ""
        client.newCall(
            Request.Builder().url("$baseUrl/api/webdav-session").post(RequestBody.create(null, ByteArray(0))).build()
        ).execute().use { resp ->
            assertEquals(200, resp.code())
            token = JsonParser.parseString(resp.body()!!.string()).asJsonObject.get("token").asString
            assertTrue(token.isNotBlank())
        }

        client.newCall(Request.Builder().url("$baseUrl/api/webdav?t=invalid_token").build()).execute().use { resp ->
            assertEquals(403, resp.code())
        }

        val davPost = RequestBody.create(
            MediaType.parse("application/json"),
            """{"serverUrl":"https://dav.jianguoyun.com/dav/","username":"user@example.com","password":"app_secret_123","space":"tv_room","skipProbe":true}"""
        )
        client.newCall(Request.Builder().url("$baseUrl/api/webdav?t=$token").post(davPost).build()).execute().use { resp ->
            assertEquals(200, resp.code())
            val obj = JsonParser.parseString(resp.body()!!.string()).asJsonObject
            assertTrue(obj.get("ok").asBoolean)
        }

        val davPostBlankPwd = RequestBody.create(
            MediaType.parse("application/json"),
            """{"serverUrl":"https://dav.jianguoyun.com/dav/","username":"user@example.com","password":"","space":"tv_room","skipProbe":true}"""
        )
        client.newCall(Request.Builder().url("$baseUrl/api/webdav?t=$token").post(davPostBlankPwd).build()).execute().use { resp ->
            assertEquals(200, resp.code())
        }
        assertEquals("app_secret_123", repository.getWebDavConfig().password)

        // 5. 验证手机端通过 `POST /api/sources` 热注入自定义源、检索，并通过 `DELETE /api/sources` 卸载
        val customJson = """{"dramas":[{"id":"self_999","title":"坚果N1Air自救剧场","category":"AI 剧","episodes":[{"index":1,"url":"https://example.com/999.mp4"}]}]}"""
        val srcPost = RequestBody.create(
            MediaType.parse("application/json"),
            com.google.gson.Gson().toJson(
                mapOf(
                    "id" to "rescue1",
                    "name" to "手机注入源",
                    "url" to "local://rescue",
                    "type" to "json",
                    "rawContent" to customJson
                )
            )
        )
        client.newCall(Request.Builder().url("$baseUrl/api/sources").post(srcPost).build()).execute().use { resp ->
            assertEquals(200, resp.code())
        }

        // 关闭外网内置源以离线快速搜索验证
        var settingsCallbackFired = false
        server.onSettingsChangedCallback = { settingsCallbackFired = true }
        val settingsPost = RequestBody.create(
            MediaType.parse("application/json"),
            """{"enabledSources":{"hongguo":false,"huangju":false,"huangdou":false,"custom":true},"sourceDomainOverrides":{"hongguo":"https://mirror.example.com"},"danmakuSpeed":1.25}"""
        )
        client.newCall(Request.Builder().url("$baseUrl/api/settings").post(settingsPost).build()).execute().use { resp ->
            assertEquals(200, resp.code())
        }
        assertTrue(settingsCallbackFired)
        assertEquals("https://mirror.example.com", repository.getSettings().sourceDomainOverrides["hongguo"])
        assertEquals(1.25f, repository.getSettings().danmakuSpeed, 0.01f)

        client.newCall(Request.Builder().url("$baseUrl/api/search?q=坚果N1Air自救剧场").build()).execute().use { resp ->
            assertEquals(200, resp.code())
            val items = JsonParser.parseString(resp.body()!!.string()).asJsonObject.getAsJsonArray("items")
            assertEquals(1, items.size())
            assertEquals("坚果N1Air自救剧场", items[0].asJsonObject.get("title").asString)
        }

        // 删除该自定义源后，搜索结果 items 列表中应立即移除
        client.newCall(Request.Builder().url("$baseUrl/api/sources?id=rescue1").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code())
        }
        client.newCall(Request.Builder().url("$baseUrl/api/search?q=坚果N1Air自救剧场").build()).execute().use { resp ->
            assertEquals(200, resp.code())
            val items = JsonParser.parseString(resp.body()!!.string()).asJsonObject.getAsJsonArray("items")
            assertEquals(0, items.size())
        }

        // 6. 验证 `/api/cover` 真实海报防盗链代拉与缓存
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)
        val assetSrv = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(fakePng), fakePng.size.toLong())
            }
        }
        assetSrv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        mockAssetServer = assetSrv

        val targetCoverUrl = "http://127.0.0.1:${assetSrv.listeningPort}/poster.png"
        val coverProxyUrl = "$baseUrl/api/cover?url=${URLEncoder.encode(targetCoverUrl, "UTF-8")}"
        client.newCall(Request.Builder().url(coverProxyUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code())
            assertEquals("image/png", resp.header("Content-Type"))
            assertArrayEquals(fakePng, resp.body()!!.bytes())
        }
    }
}
