package com.juju.tv.stream

import java.net.URI
import java.net.URLEncoder

/**
 * HLS (`.m3u8`) 播放列表重写器（对应原版 Go `media_playlist.go` / `nativeRewrite`）
 *
 * 将上游 M3U8 中的：
 * 1. `#EXT-X-KEY:METHOD=AES-128,URI="..."` 密钥地址
 * 2. `#EXT-X-MAP:URI="..."` fMP4 初始化分片地址
 * 3. 子播放列表（Master Playlist 下的 `.m3u8`）
 * 4. 媒体分片（`.ts` / `.m4s` / `.mp4` / 无后缀分片）
 * 全部重写为通过本机 `http://127.0.0.1:<port>/stream/...` 代理并自动附带防盗链 Header。
 */
object HlsPlaylistRewriter {

    private val uriAttrRegex = Regex("""URI="([^"]+)"""")

    fun rewriteM3u8(
        m3u8Content: String,
        basePlaylistUrl: String,
        localGatewayBaseUrl: String, // e.g., "http://127.0.0.1:18900"
        headers: Map<String, String> = emptyMap()
    ): String {
        val headerParam = encodeHeadersParam(headers)
        val lines = m3u8Content.lines()
        val out = StringBuilder(m3u8Content.length + 512)

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                out.append("\n")
                continue
            }
            if (line.startsWith("#")) {
                if (line.contains("URI=\"")) {
                    val rewrittenTag = uriAttrRegex.replace(line) { match ->
                        val rawUri = match.groupValues[1]
                        val absUrl = resolveUrl(basePlaylistUrl, rawUri)
                        val proxyUrl = buildProxyUrl(localGatewayBaseUrl, absUrl, isSubPlaylist = false, headerParam = headerParam)
                        "URI=\"$proxyUrl\""
                    }
                    out.append(rewrittenTag).append("\n")
                } else {
                    out.append(line).append("\n")
                }
            } else {
                val absUrl = resolveUrl(basePlaylistUrl, line)
                val isSubM3u8 = absUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
                val proxyUrl = buildProxyUrl(localGatewayBaseUrl, absUrl, isSubPlaylist = isSubM3u8, headerParam = headerParam)
                out.append(proxyUrl).append("\n")
            }
        }
        return out.toString()
    }

    fun resolveUrl(baseUrl: String, relativeOrAbs: String): String {
        val trimmed = relativeOrAbs.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(trimmed).toString()
        } catch (_: Throwable) {
            if (trimmed.startsWith("/")) {
                val schemeAndHost = baseUrl.substringBefore("/", "") + "//" +
                        baseUrl.substringAfter("://").substringBefore("/")
                "$schemeAndHost$trimmed"
            } else {
                val parent = baseUrl.substringBeforeLast("/", baseUrl)
                "$parent/$trimmed"
            }
        }
    }

    private fun buildProxyUrl(
        gatewayBase: String,
        targetUrl: String,
        isSubPlaylist: Boolean,
        headerParam: String
    ): String {
        val cleanBase = gatewayBase.trimEnd('/')
        val route = if (isSubPlaylist) "/stream/hls" else "/stream/proxy"
        val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")
        return if (headerParam.isNotEmpty()) {
            "$cleanBase$route?url=$encodedUrl&h=$headerParam"
        } else {
            "$cleanBase$route?url=$encodedUrl"
        }
    }

    fun encodeHeadersParam(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val joined = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        return URLEncoder.encode(joined, "UTF-8")
    }

    fun decodeHeadersParam(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (line in encoded.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotEmpty()) result[k] = v
            }
        }
        return result
    }
}
