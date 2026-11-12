package com.juju.tv

import com.juju.tv.stream.HlsPlaylistRewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistRewriterTest {

    @Test
    fun testRewriteM3u8WithKeysAndSegments() {
        val rawM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="/api/hls_key/key123.key",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:5.000,
            seg_0001.ts
            #EXTINF:5.000,
            https://cdn.example.com/video/seg_0002.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val headers = mapOf("Referer" to "https://hongguoduanju.com/", "User-Agent" to "JuJuTV/1.0")
        val rewritten = HlsPlaylistRewriter.rewriteM3u8(
            m3u8Content = rawM3u8,
            basePlaylistUrl = "https://vod.example.com/drama/1001/index.m3u8",
            localGatewayBaseUrl = "http://127.0.0.1:18900",
            headers = headers
        )

        assertTrue(rewritten.contains("URI=\"http://127.0.0.1:18900/stream/proxy?url=https%3A%2F%2Fvod.example.com%2Fapi%2Fhls_key%2Fkey123.key"))
        assertTrue(rewritten.contains("URI=\"http://127.0.0.1:18900/stream/proxy?url=https%3A%2F%2Fvod.example.com%2Fdrama%2F1001%2Finit.mp4"))
        assertTrue(rewritten.contains("http://127.0.0.1:18900/stream/proxy?url=https%3A%2F%2Fvod.example.com%2Fdrama%2F1001%2Fseg_0001.ts"))
        assertTrue(rewritten.contains("http://127.0.0.1:18900/stream/proxy?url=https%3A%2F%2Fcdn.example.com%2Fvideo%2Fseg_0002.ts"))

        // 验证 Header 编解码无损
        val encoded = HlsPlaylistRewriter.encodeHeadersParam(headers)
        val decoded = HlsPlaylistRewriter.decodeHeadersParam( java.net.URLDecoder.decode(encoded, "UTF-8") )
        assertEquals("https://hongguoduanju.com/", decoded["Referer"])
        assertEquals("JuJuTV/1.0", decoded["User-Agent"])
    }
}
