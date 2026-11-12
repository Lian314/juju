package com.juju.tv.stream

import com.juju.tv.memory.ByteBufferPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 下一集无缝预加载滑动窗口缓存（对应原版 Go `app_preload.go` / `preloadCache`）
 *
 * 针对坚果 N1 Air 2GB RAM 与紧凑闪存空间的专门优化：
 * 1. 当当前集播放至 65%（或剩余不足 25 秒）时，异步预取下一集的前 [maxPreloadBytesPerEpisode]（默认 1.5MB）到本地缓存目录；
 * 2. 流式截断保护：使用 [ByteBufferPool] 分块读取上游流，哪怕上游忽略 Range 返回 200 全量流，也在读满 [maxPreloadBytesPerEpisode] 后立即断开，杜绝 OOM；
 * 3. 严格维持滑动窗口 `keepWindow(dramaId, currentEpisodeIndex, windowSize = 2)`：
 *    切换集数时立即淘汰 `[currentEpisodeIndex, currentEpisodeIndex + 1]` 窗口之外的旧集缓存，通常只占用一集的体积；
 * 4. `sweepOrphans(maxAgeMs)` 自动清理超过有效期的孤儿文件与 `.tmp` 残片。
 */
class EpisodePreloadManager(
    private val cacheRootDir: File,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val bufferPool: ByteBufferPool = ByteBufferPool.shared,
    val maxPreloadBytesPerEpisode: Long = 1536 * 1024L // 1.5 MB 起播秒开预加载上限
) {
    data class PreloadEntry(
        val dramaId: String,
        val episodeIndex: Int,
        val sourceUrl: String,
        val file: File,
        val cachedBytes: Long,
        val totalUpstreamLength: Long,
        val isCencDecrypted: Boolean,
        val cencKeyHex: String = "",
        val cencKidHex: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    private val entries = ConcurrentHashMap<String, PreloadEntry>()

    init {
        if (!cacheRootDir.exists()) {
            cacheRootDir.mkdirs()
        }
    }

    private fun keyFor(dramaId: String, episodeIndex: Int): String {
        val safeDrama = dramaId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${safeDrama}_ep_${episodeIndex}"
    }

    private fun fileFor(dramaId: String, episodeIndex: Int): File {
        return File(cacheRootDir, "${keyFor(dramaId, episodeIndex)}.preload")
    }

    /**
     * 将字节流直接写入指定剧集分集的预加载缓存（可用于真实网络拉取或单元测试注入）
     */
    fun putPreloadBytes(
        dramaId: String,
        episodeIndex: Int,
        sourceUrl: String,
        bytes: ByteArray,
        totalUpstreamLength: Long = bytes.size.toLong(),
        isCencDecrypted: Boolean = false,
        cencKeyHex: String = "",
        cencKidHex: String = ""
    ): PreloadEntry {
        if (!cacheRootDir.exists()) cacheRootDir.mkdirs()
        val key = keyFor(dramaId, episodeIndex)
        val targetFile = fileFor(dramaId, episodeIndex)
        val tmpFile = File(cacheRootDir, "$key.tmp")

        val writeLen = bytes.size.toLong().coerceAtMost(maxPreloadBytesPerEpisode).toInt()
        FileOutputStream(tmpFile).use { fos ->
            fos.write(bytes, 0, writeLen)
            fos.flush()
        }
        if (targetFile.exists()) targetFile.delete()
        tmpFile.renameTo(targetFile)

        val entry = PreloadEntry(
            dramaId = dramaId,
            episodeIndex = episodeIndex,
            sourceUrl = sourceUrl,
            file = targetFile,
            cachedBytes = targetFile.length(),
            totalUpstreamLength = maxOf(totalUpstreamLength, targetFile.length()),
            isCencDecrypted = isCencDecrypted,
            cencKeyHex = cencKeyHex,
            cencKidHex = cencKidHex
        )
        entries[key] = entry
        return entry
    }

    /**
     * 从网络拉取下一集前 [maxPreloadBytesPerEpisode] 字节并落盘（若为 CENC 流则原地解密后落盘）
     *
     * 采用 [ByteBufferPool] 限长流式读取：即使上游未遵守 Range 头而返回整集大文件，也最多只读取 [maxPreloadBytesPerEpisode] 字节。
     */
    fun preloadFromNetwork(
        dramaId: String,
        episodeIndex: Int,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        isCenc: Boolean = false,
        cencKeyHex: String = "",
        cencKidHex: String = ""
    ): PreloadEntry? {
        val existing = getPreloaded(dramaId, episodeIndex)
        if (existing != null && existing.file.exists() && existing.cachedBytes > 0) {
            return existing
        }

        return try {
            val reqBuilder = Request.Builder()
                .url(streamUrl)
                .header("Range", "bytes=0-${maxPreloadBytesPerEpisode - 1}")
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code() != 206) return null
                val body = resp.body() ?: return null
                val maxCap = maxPreloadBytesPerEpisode.toInt()
                val bos = ByteArrayOutputStream(minOf(maxCap, 256 * 1024))

                bufferPool.withBuffer { scratch ->
                    val input = body.byteStream()
                    var totalRead = 0
                    while (totalRead < maxCap) {
                        val toRead = minOf(scratch.size, maxCap - totalRead)
                        val n = input.read(scratch, 0, toRead)
                        if (n <= 0) break
                        bos.write(scratch, 0, n)
                        totalRead += n
                    }
                }

                val rawBytes = bos.toByteArray()
                if (rawBytes.isEmpty()) return null

                if (isCenc && cencKeyHex.length == 32) {
                    val keyBytes = CencMp4Decrypter.hexToBytes(cencKeyHex)
                    val meta = CencMp4Decrypter.parseCencMetadata(rawBytes)
                    CencMp4Decrypter.patchSchemeBoxesToClearInPlace(
                        buffer = rawBytes,
                        length = rawBytes.size,
                        videoFormat = meta.originalVideoFormat,
                        audioFormat = meta.originalAudioFormat
                    )
                    CencMp4Decrypter.decryptStreamRangeInPlace(
                        buffer = rawBytes,
                        chunkLength = rawBytes.size,
                        chunkFileOffset = 0L,
                        keyBytes = keyBytes,
                        samples = meta.samples,
                        bufferPool = bufferPool
                    )
                }

                val contentLenHeader = resp.body()?.contentLength()?.takeIf { it > 0 } ?: rawBytes.size.toLong()
                val totalLen = parseTotalLengthFromContentRange(resp.header("Content-Range"), contentLenHeader)
                putPreloadBytes(
                    dramaId = dramaId,
                    episodeIndex = episodeIndex,
                    sourceUrl = streamUrl,
                    bytes = rawBytes,
                    totalUpstreamLength = totalLen,
                    isCencDecrypted = isCenc,
                    cencKeyHex = cencKeyHex,
                    cencKidHex = cencKidHex
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseTotalLengthFromContentRange(contentRange: String?, fallback: Long): Long {
        if (contentRange.isNullOrBlank()) return fallback
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= contentRange.length) return fallback
        return contentRange.substring(slash + 1).trim().toLongOrNull() ?: fallback
    }

    fun getPreloaded(dramaId: String, episodeIndex: Int): PreloadEntry? {
        val key = keyFor(dramaId, episodeIndex)
        val entry = entries[key] ?: return null
        if (!entry.file.exists()) {
            entries.remove(key)
            return null
        }
        return entry
    }

    /**
     * 维护滑动窗口（对应原版 Go `(*preloadCache).keepWindow`）：
     * 仅保留当前剧集的 `[currentEpisodeIndex, currentEpisodeIndex + windowSize - 1]` 集缓存，
     * 其余剧集或更早/更晚的分集缓存立即从磁盘删除。
     */
    fun keepWindow(dramaId: String, currentEpisodeIndex: Int, windowSize: Int = 2): Int {
        val allowedIndices = (currentEpisodeIndex until (currentEpisodeIndex + windowSize.coerceAtLeast(1))).toSet()
        var evictedCount = 0

        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.dramaId != dramaId || entry.episodeIndex !in allowedIndices) {
                if (entry.file.exists()) {
                    entry.file.delete()
                }
                iterator.remove()
                evictedCount++
            }
        }
        return evictedCount
    }

    /**
     * 清理孤儿预加载文件与过期缓存（对应原版 Go `(*preloadCache).sweepOrphans`）
     */
    fun sweepOrphans(maxAgeMs: Long = 6 * 3600 * 1000L): Int {
        if (!cacheRootDir.exists()) return 0
        val now = System.currentTimeMillis()
        val trackedPaths = entries.values.map { it.file.absolutePath }.toSet()
        var sweptCount = 0

        val files = cacheRootDir.listFiles() ?: return 0
        for (f in files) {
            val isUntracked = f.absolutePath !in trackedPaths
            val isTemp = f.name.endsWith(".tmp")
            val isExpired = (now - f.lastModified()) > maxAgeMs
            if (isUntracked || isTemp || isExpired) {
                if (f.delete()) {
                    sweptCount++
                }
                entries.entries.removeIf { it.value.file.absolutePath == f.absolutePath }
            }
        }
        return sweptCount
    }

    fun clearAll() {
        entries.clear()
        cacheRootDir.listFiles()?.forEach { it.delete() }
    }

    fun stats(): Pair<Int, Long> {
        val files = cacheRootDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val totalBytes = files.sumOf { it.length() }
        return files.size to totalBytes
    }
}
