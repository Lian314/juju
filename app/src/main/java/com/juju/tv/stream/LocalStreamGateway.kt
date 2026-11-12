package com.juju.tv.stream

import com.juju.tv.memory.ByteBufferPool
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.ResolvedStream
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 127.0.0.1 零拷贝流媒体解密与防盗链代理网关（对应原版 Go `nativeStreamServer`）
 *
 * 核心能力：
 * 1. `/stream/cenc`：基于 [CencDecryptingInputStream] 与 [ByteBufferPool] 64KB 滑动窗口实时流式解密 MP4/fMP4，
 *    支持任意 HTTP 206 Range（含开放区间 `bytes=0-` 与非对齐区间），杜绝整集读入堆内存导致 2GB 电视 OOM；
 * 2. `/stream/hls`：拉取远端 M3U8 并通过 [HlsPlaylistRewriter] 重写全部分片与密钥地址；
 * 3. `/stream/proxy`：透传防盗链 Referer / User-Agent Header 与 Range 字节区间请求；
 * 4. `/stream/preload`：优先命中 [EpisodePreloadManager] 本地磁盘预缓存，精确遵守 RFC 7233 Range 边界，
 *    并在超出本地预缓存后无缝回源（若为 CENC 加密剧集则自动衔接 CENC 流式解密）。
 */
class LocalStreamGateway(
    port: Int = 0,
    private val preloadManager: EpisodePreloadManager? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val bufferPool: ByteBufferPool = ByteBufferPool.shared
) : NanoHTTPD("127.0.0.1", port) {

    // 缓存最近 8 个加密视频流的 CENC 元数据（避免拖动进度条时重复解析 moov/moof 头）
    private val cencMetaCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CencMp4Decrypter.CencMetadata>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CencMp4Decrypter.CencMetadata>?): Boolean {
                return size > 8
            }
        }
    )

    fun startGateway(): Int {
        if (!isAlive) {
            start(SOCKET_READ_TIMEOUT, true)
        }
        return listeningPort
    }

    fun baseUrl(): String = "http://127.0.0.1:$listeningPort"

    /**
     * 将剧集单集信息转换为本地网关可播放地址
     */
    fun buildPlaybackStream(dramaId: String, episode: EpisodeItem): ResolvedStream {
        if (!isAlive) startGateway()
        val preloaded = preloadManager?.getPreloaded(dramaId, episode.index)
        val encodedUrl = URLEncoder.encode(episode.streamUrl, "UTF-8")
        val headerParam = HlsPlaylistRewriter.encodeHeadersParam(episode.headers)
        val hSuffix = if (headerParam.isNotEmpty()) "&h=$headerParam" else ""

        val cleanPath = episode.streamUrl.substringBefore("?")
        val isHls = cleanPath.endsWith(".m3u8", ignoreCase = true)

        val localUrl = when {
            preloaded != null && preloaded.file.exists() && preloaded.cachedBytes > 0 -> {
                val cencSuffix = if (episode.isCencEncrypted && episode.cencKeyHex.isNotBlank()) {
                    "&cenc=1&key=${episode.cencKeyHex}&kid=${episode.cencKidHex}"
                } else ""
                "${baseUrl()}/stream/preload?dramaId=${URLEncoder.encode(dramaId, "UTF-8")}&ep=${episode.index}&url=$encodedUrl$cencSuffix$hSuffix"
            }
            episode.isCencEncrypted && episode.cencKeyHex.isNotBlank() -> {
                "${baseUrl()}/stream/cenc?url=$encodedUrl&key=${episode.cencKeyHex}&kid=${episode.cencKidHex}$hSuffix"
            }
            isHls -> {
                "${baseUrl()}/stream/hls?url=$encodedUrl$hSuffix"
            }
            episode.headers.isNotEmpty() -> {
                "${baseUrl()}/stream/proxy?url=$encodedUrl$hSuffix"
            }
            else -> episode.streamUrl
        }

        return ResolvedStream(
            dramaId = dramaId,
            episodeIndex = episode.index,
            originalUrl = episode.streamUrl,
            localPlaybackUrl = localUrl,
            mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
            qualityLabel = "1080P 高清",
            isCenc = episode.isCencEncrypted,
            cencKeyHex = episode.cencKeyHex,
            cencKidHex = episode.cencKidHex,
            headers = episode.headers,
            preloadedFilePath = preloaded?.file?.absolutePath
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = session.parms ?: emptyMap()
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]

        return try {
            when (uri) {
                "/stream/hls" -> handleHlsPlaylist(params)
                "/stream/cenc" -> handleCencStream(params, rangeHeader)
                "/stream/preload" -> handlePreloadStream(params, rangeHeader)
                "/stream/proxy" -> handleProxyStream(params, rangeHeader)
                "/stream/health" -> newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"port":$listeningPort}""")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: $uri")
            }
        } catch (e: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Gateway Error: ${e.message}")
        }
    }

    private fun handleHlsPlaylist(params: Map<String, String>): Response {
        val targetUrl = params["url"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        val headers = HlsPlaylistRewriter.decodeHeadersParam(params["h"])

        val reqBuilder = Request.Builder().url(targetUrl)
        for ((k, v) in headers) reqBuilder.header(k, v)

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return newFixedLengthResponse(Response.Status.lookup(resp.code()) ?: Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Upstream HTTP ${resp.code()}")
            }
            val rawM3u8 = resp.body()?.string() ?: ""
            val rewritten = HlsPlaylistRewriter.rewriteM3u8(
                m3u8Content = rawM3u8,
                basePlaylistUrl = targetUrl,
                localGatewayBaseUrl = baseUrl(),
                headers = headers
            )
            val response = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Cache-Control", "no-store")
            return response
        }
    }

    /**
     * CENC 实时流式解密处理（使用 [CencDecryptingInputStream] + [ByteBufferPool] 64KB 滑动分块，绝不整包读入内存）
     */
    private fun handleCencStream(params: Map<String, String>, rangeHeader: String?): Response {
        val targetUrl = params["url"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        val keyHex = params["key"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing key")
        val headers = HlsPlaylistRewriter.decodeHeadersParam(params["h"])
        val keyBytes = CencMp4Decrypter.hexToBytes(keyHex)
        if (keyBytes.size != 16) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid 128-bit CENC key")
        }

        // 1. 获取或探测 CENC 元数据（使用 ByteBufferPool 限长读取前 512KB）
        val meta = cencMetaCache[targetUrl] ?: run {
            val probeReq = Request.Builder().url(targetUrl).header("Range", "bytes=0-524287")
            for ((k, v) in headers) probeReq.header(k, v)
            val headerBytes = httpClient.newCall(probeReq.build()).execute().use { r ->
                val bodyStream = r.body()?.byteStream() ?: return@use ByteArray(0)
                readUpToBytes(bodyStream, 524288)
            }
            val parsed = CencMp4Decrypter.parseCencMetadata(headerBytes)
            cencMetaCache[targetUrl] = parsed
            parsed
        }

        // 2. 解析客户端请求的 Range 起点与终点
        val (rangeStart, rangeEnd) = parseRangeHeader(rangeHeader)
        val reqBuilder = Request.Builder().url(targetUrl)
        for ((k, v) in headers) reqBuilder.header(k, v)
        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        }

        val upstreamResp = httpClient.newCall(reqBuilder.build()).execute()
        if (!upstreamResp.isSuccessful && upstreamResp.code() != 206) {
            val code = upstreamResp.code()
            upstreamResp.close()
            return newFixedLengthResponse(
                Response.Status.lookup(code) ?: Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Upstream CENC HTTP $code"
            )
        }

        val contentLength = upstreamResp.body()?.contentLength() ?: -1L
        val rawStream = upstreamResp.body()?.byteStream() ?: ByteArrayInputStream(ByteArray(0))

        // 3. 包装为基于 ByteBufferPool 的 64KB 滑动流式解密 InputStream
        val decryptingStream = CencDecryptingInputStream(
            upstream = rawStream,
            onCloseCallback = { upstreamResp.close() },
            startFileOffset = rangeStart,
            keyBytes = keyBytes,
            meta = meta,
            bufferPool = bufferPool
        )

        val status = if (rangeHeader != null || upstreamResp.code() == 206) {
            Response.Status.PARTIAL_CONTENT
        } else {
            Response.Status.OK
        }
        val response = if (contentLength >= 0) {
            newFixedLengthResponse(status, "video/mp4", decryptingStream, contentLength)
        } else {
            newChunkedResponse(status, "video/mp4", decryptingStream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        val upstreamContentRange = upstreamResp.header("Content-Range")
        if (upstreamContentRange != null) {
            response.addHeader("Content-Range", upstreamContentRange)
        } else if (rangeHeader != null && contentLength > 0) {
            val actualEnd = rangeEnd ?: (rangeStart + contentLength - 1).coerceAtLeast(0L)
            val totalLen = actualEnd + 1
            response.addHeader("Content-Range", "bytes $rangeStart-$actualEnd/$totalLen")
        }
        return response
    }

    private fun handlePreloadStream(params: Map<String, String>, rangeHeader: String?): Response {
        val dramaId = params["dramaId"] ?: ""
        val epIndex = params["ep"]?.toIntOrNull() ?: 1
        val targetUrl = params["url"] ?: ""
        val preloaded = preloadManager?.getPreloaded(dramaId, epIndex)

        val (rangeStart, rangeEnd) = parseRangeHeader(rangeHeader)
        if (preloaded != null && preloaded.file.exists() && rangeStart < preloaded.cachedBytes) {
            val maxCachedEnd = preloaded.cachedBytes - 1
            val actualEnd = if (rangeEnd != null && rangeEnd >= rangeStart) {
                minOf(rangeEnd, maxCachedEnd)
            } else {
                maxCachedEnd
            }
            val sliceLength = (actualEnd - rangeStart + 1).coerceAtLeast(0L)
            val boundedStream = BoundedFileInputStream(preloaded.file, rangeStart, sliceLength)

            val status = if (rangeHeader != null || preloaded.totalUpstreamLength > preloaded.cachedBytes) {
                Response.Status.PARTIAL_CONTENT
            } else {
                Response.Status.OK
            }
            val resp = newFixedLengthResponse(status, "video/mp4", boundedStream, sliceLength)
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("X-JuJu-Preload", "HIT")
            if (status == Response.Status.PARTIAL_CONTENT) {
                resp.addHeader(
                    "Content-Range",
                    "bytes $rangeStart-$actualEnd/${preloaded.totalUpstreamLength}"
                )
            }
            return resp
        }

        // 超出本地预缓存部分自动回源：若为 CENC 加密流，自动走 CENC 流式解密而非明文透传！
        if (targetUrl.isNotBlank()) {
            val isCenc = params["cenc"] == "1" || (preloaded?.isCencDecrypted == true)
            val keyHex = params["key"]?.takeIf { it.isNotBlank() } ?: preloaded?.cencKeyHex ?: ""
            if (isCenc && keyHex.length == 32) {
                val cencParams = HashMap(params).apply { put("key", keyHex) }
                return handleCencStream(cencParams, rangeHeader)
            }
            return handleProxyStream(params, rangeHeader)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Preload cache miss")
    }

    private fun handleProxyStream(params: Map<String, String>, rangeHeader: String?): Response {
        val targetUrl = params["url"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        val headers = HlsPlaylistRewriter.decodeHeadersParam(params["h"])

        val reqBuilder = Request.Builder().url(targetUrl)
        for ((k, v) in headers) reqBuilder.header(k, v)
        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        }

        val upstreamResp = httpClient.newCall(reqBuilder.build()).execute()
        val contentType = upstreamResp.header("Content-Type") ?: "video/mp4"
        val contentLength = upstreamResp.body()?.contentLength() ?: -1L
        val rawStream: InputStream = upstreamResp.body()?.byteStream() ?: ByteArrayInputStream(ByteArray(0))

        val status = if (upstreamResp.code() == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = if (contentLength >= 0) {
            newFixedLengthResponse(status, contentType, rawStream, contentLength)
        } else {
            newChunkedResponse(status, contentType, rawStream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        upstreamResp.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
        return response
    }

    private fun readUpToBytes(input: InputStream, maxBytes: Int): ByteArray {
        val bos = ByteArrayOutputStream(minOf(maxBytes, 65536))
        bufferPool.withBuffer { buf ->
            var total = 0
            while (total < maxBytes) {
                val toRead = minOf(buf.size, maxBytes - total)
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                bos.write(buf, 0, n)
                total += n
            }
        }
        return bos.toByteArray()
    }

    fun parseRangeHeader(rangeHeader: String?): Pair<Long, Long?> {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return 0L to null
        }
        val spec = rangeHeader.removePrefix("bytes=").substringBefore(",").trim()
        val parts = spec.split("-", limit = 2)
        val start = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
        return start to end
    }

    /**
     * 精确限制读取字节数的本地文件流（确保 HTTP Range 206 请求严格返回请求区间内的字节数）
     */
    private class BoundedFileInputStream(
        file: File,
        startOffset: Long,
        private var remainingBytes: Long
    ) : InputStream() {
        private val fis = FileInputStream(file)

        init {
            if (startOffset > 0L) {
                var toSkip = startOffset
                while (toSkip > 0L) {
                    val skipped = fis.skip(toSkip)
                    if (skipped <= 0L) break
                    toSkip -= skipped
                }
            }
        }

        override fun read(): Int {
            if (remainingBytes <= 0L) return -1
            val b = fis.read()
            if (b >= 0) remainingBytes--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remainingBytes <= 0L) return -1
            val maxToRead = minOf(len.toLong(), remainingBytes).toInt()
            val n = fis.read(b, off, maxToRead)
            if (n > 0) remainingBytes -= n
            return n
        }

        override fun available(): Int {
            return minOf(fis.available().toLong(), remainingBytes).toInt()
        }

        override fun close() {
            remainingBytes = 0L
            fis.close()
        }
    }

    /**
     * 基于 [ByteBufferPool] 64KB 缓冲块的零拷贝滑动窗口 CENC 流式解密管道
     *
     * 每次从上游网络流读取最多 64KB 到复用缓冲池中，原地完成 Box 头部伪装与 AES-128-CTR 解密后吐给播放器，
     * 彻底杜绝大体积 MP4 全量读入堆内存。
     */
    class CencDecryptingInputStream(
        private val upstream: InputStream,
        private val onCloseCallback: (() -> Unit)? = null,
        startFileOffset: Long,
        private val keyBytes: ByteArray,
        private val meta: CencMp4Decrypter.CencMetadata,
        private val bufferPool: ByteBufferPool = ByteBufferPool.shared
    ) : InputStream() {

        private var chunkBuf: ByteArray? = bufferPool.acquire()
        private var chunkFileOffset: Long = startFileOffset
        private var nextReadFileOffset: Long = startFileOffset
        private var bufPos: Int = 0
        private var bufLimit: Int = 0
        private var eof: Boolean = false
        private val closed = AtomicBoolean(false)
        private val dynamicSamples = ArrayList<CencMp4Decrypter.CencSample>(meta.samples)

        override fun read(): Int {
            if (!ensureBufferFilled()) return -1
            val buf = chunkBuf ?: return -1
            return buf[bufPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensureBufferFilled()) return -1
            val buf = chunkBuf ?: return -1
            val available = bufLimit - bufPos
            val toCopy = minOf(len, available)
            System.arraycopy(buf, bufPos, b, off, toCopy)
            bufPos += toCopy
            return toCopy
        }

        private fun ensureBufferFilled(): Boolean {
            if (bufPos < bufLimit) return true
            if (eof || closed.get()) return false
            val buf = chunkBuf ?: return false

            var totalRead = 0
            // 尽量凑满至少一个完整 Box 头或直到上游暂时无更多字节
            while (totalRead < buf.size) {
                val n = upstream.read(buf, totalRead, buf.size - totalRead)
                if (n < 0) {
                    if (totalRead == 0) {
                        eof = true
                        return false
                    }
                    break
                }
                if (n > 0) {
                    totalRead += n
                    // 若已读取满 16KB 或上游无立即可读字节，即可立即解密下发以降低首帧延迟
                    if (totalRead >= 16384 || upstream.available() <= 0) break
                }
            }

            chunkFileOffset = nextReadFileOffset
            nextReadFileOffset += totalRead
            bufPos = 0
            bufLimit = totalRead

            // 1. 若当前块包含后续新出现的 fMP4 moof 分片头，动态追加采样表
            if (containsMoofFourCC(buf, totalRead)) {
                val extraMeta = CencMp4Decrypter.parseCencMetadata(
                    data = buf.copyOfRange(0, totalRead),
                    baseFileOffset = chunkFileOffset
                )
                if (extraMeta.samples.isNotEmpty()) {
                    val existingOffsets = dynamicSamples.map { it.fileOffset }.toHashSet()
                    for (s in extraMeta.samples) {
                        if (s.fileOffset !in existingOffsets) {
                            dynamicSamples.add(s)
                        }
                    }
                    dynamicSamples.sortBy { it.fileOffset }
                }
            }

            // 2. 若当前块与 MP4 头部区间 [0, headerEndOffset) 相交（无论 rangeStart 是否为 0），原地伪装 encv/enca/sinf
            val headerLimit = meta.headerEndOffset.coerceAtLeast(4096L)
            if (chunkFileOffset < headerLimit) {
                val patchLen = minOf(totalRead.toLong(), headerLimit - chunkFileOffset).toInt()
                CencMp4Decrypter.patchSchemeBoxesToClearInPlace(
                    buffer = buf,
                    length = patchLen,
                    videoFormat = meta.originalVideoFormat,
                    audioFormat = meta.originalAudioFormat
                )
            }

            // 3. 原地执行 AES-128-CTR 解密
            if (dynamicSamples.isNotEmpty()) {
                CencMp4Decrypter.decryptStreamRangeInPlace(
                    buffer = buf,
                    chunkLength = totalRead,
                    chunkFileOffset = chunkFileOffset,
                    keyBytes = keyBytes,
                    samples = dynamicSamples,
                    bufferPool = bufferPool
                )
            }
            return true
        }

        private fun containsMoofFourCC(buf: ByteArray, length: Int): Boolean {
            val limit = length - 8
            var i = 0
            while (i <= limit) {
                if (buf[i + 4] == 'm'.code.toByte() &&
                    buf[i + 5] == 'o'.code.toByte() &&
                    buf[i + 6] == 'o'.code.toByte() &&
                    buf[i + 7] == 'f'.code.toByte()
                ) {
                    return true
                }
                i++
            }
            return false
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                val buf = chunkBuf
                chunkBuf = null
                bufferPool.release(buf)
                try {
                    upstream.close()
                } catch (_: Throwable) {}
                try {
                    onCloseCallback?.invoke()
                } catch (_: Throwable) {}
            }
        }
    }
}
