package com.juju.tv

import com.juju.tv.stream.EpisodePreloadManager
import com.juju.tv.stream.LocalStreamGateway
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class EpisodePreloadManagerTest {

    private lateinit var tempDir: File
    private lateinit var preloadManager: EpisodePreloadManager
    private var gateway: LocalStreamGateway? = null
    private var mockUpstream: NanoHTTPD? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("juju_preload_test").toFile()
        preloadManager = EpisodePreloadManager(tempDir, maxPreloadBytesPerEpisode = 1024L)
    }

    @After
    fun tearDown() {
        gateway?.stop()
        mockUpstream?.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSlidingWindowAndOrphanSweep() {
        val sampleData = ByteArray(2048) { (it % 251).toByte() }
        val e1 = preloadManager.putPreloadBytes("hg_1001", 1, "https://example.com/1.mp4", sampleData)
        assertEquals(1024L, e1.cachedBytes)

        preloadManager.putPreloadBytes("hg_1001", 2, "https://example.com/2.mp4", sampleData)
        preloadManager.putPreloadBytes("hg_1001", 3, "https://example.com/3.mp4", sampleData)
        preloadManager.putPreloadBytes("hg_1001", 4, "https://example.com/4.mp4", sampleData)

        // 切换到第 3 集，维护窗口 [3, 4] -> 应自动清理第 1 集与第 2 集
        val evicted = preloadManager.keepWindow("hg_1001", currentEpisodeIndex = 3, windowSize = 2)
        assertEquals(2, evicted)
        assertNull(preloadManager.getPreloaded("hg_1001", 1))
        assertNull(preloadManager.getPreloaded("hg_1001", 2))
        assertNotNull(preloadManager.getPreloaded("hg_1001", 3))
        assertNotNull(preloadManager.getPreloaded("hg_1001", 4))

        // 写入一个未跟踪的孤儿 .tmp 文件，验证 sweepOrphans 清理
        val orphanFile = File(tempDir, "orphan_broken.tmp")
        orphanFile.writeBytes(ByteArray(128))
        val swept = preloadManager.sweepOrphans(maxAgeMs = 3600_000L)
        assertEquals(1, swept)
        assertTrue(!orphanFile.exists())
    }

    @Test
    fun testLocalStreamGatewayPreloadExactRangeAndOpenEndedRangeHit() {
        val data = ByteArray(500) { idx -> (idx + 10).toByte() }
        preloadManager.putPreloadBytes("hg_1001", 2, "https://example.com/2.mp4", data, totalUpstreamLength = 5000L)

        val gw = LocalStreamGateway(port = 0, preloadManager = preloadManager)
        gw.startGateway()
        gateway = gw

        val client = OkHttpClient()
        val url = "${gw.baseUrl()}/stream/preload?dramaId=hg_1001&ep=2&url=https%3A%2F%2Fexample.com%2F2.mp4"

        // 1. 精确闭合区间 Range: bytes=20-119 -> 严格按 RFC 7233 返回 100 字节 (bytes 20-119/5000)
        val exactReq = Request.Builder()
            .url(url)
            .header("Range", "bytes=20-119")
            .build()
        client.newCall(exactReq).execute().use { resp ->
            assertEquals(206, resp.code())
            assertEquals("HIT", resp.header("X-JuJu-Preload"))
            assertEquals("bytes 20-119/5000", resp.header("Content-Range"))
            val body = resp.body()?.bytes() ?: ByteArray(0)
            assertEquals(100, body.size)
            assertArrayEquals(data.copyOfRange(20, 120), body)
        }

        // 2. 开放区间 Range: bytes=20- -> 返回本地缓存剩余的 480 字节 (bytes 20-499/5000)
        val openReq = Request.Builder()
            .url(url)
            .header("Range", "bytes=20-")
            .build()
        client.newCall(openReq).execute().use { resp ->
            assertEquals(206, resp.code())
            assertEquals("HIT", resp.header("X-JuJu-Preload"))
            assertEquals("bytes 20-499/5000", resp.header("Content-Range"))
            val body = resp.body()?.bytes() ?: ByteArray(0)
            assertEquals(480, body.size)
            assertArrayEquals(data.copyOfRange(20, 500), body)
        }
    }

    @Test
    fun testPreloadFromNetworkTruncatesWhenUpstreamIgnoresRange() {
        val fullVideo = ByteArray(16 * 1024) { (it and 0xFF).toByte() }
        val upstream = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                // 模拟上游 CDN 忽略 Range 请求直接返回 200 全量 16KB 流
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "video/mp4",
                    ByteArrayInputStream(fullVideo),
                    fullVideo.size.toLong()
                )
            }
        }
        upstream.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        mockUpstream = upstream

        val entry = preloadManager.preloadFromNetwork(
            dramaId = "hg_9001",
            episodeIndex = 3,
            streamUrl = "http://127.0.0.1:${upstream.listeningPort}/full.mp4"
        )
        assertNotNull(entry)
        assertEquals(1024L, entry!!.cachedBytes)
        assertEquals(16 * 1024L, entry.totalUpstreamLength)
        assertArrayEquals(fullVideo.copyOfRange(0, 1024), entry.file.readBytes())
    }
}
