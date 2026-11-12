package com.juju.tv.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.juju.tv.model.CustomSourceConfig
import com.juju.tv.model.FollowEntry
import com.juju.tv.model.ReserveEntry
import com.juju.tv.model.SyncPayload
import com.juju.tv.model.WatchHistoryEntry
import com.juju.tv.model.WebDavConfig
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * WebDAV 跨设备 Gzip 同步与剧集粒度 Last-Write-Wins (LWW) 无冲突合并引擎
 *
 * 复刻并优化自原版 Go `app_webdav.go` (`webdavProbe` / `webdavPropfind` / `webdavPull` / `webdavPush` / `webdavGzip`)：
 * - 兼容坚果云 (`https://dav.jianguoyun.com/dav/`)、群晖 Synology、OpenList / Alist、Nextcloud；
 * - 使用 Gzip 压缩同步载荷 (`juju-sync-<space>.json.gz`)，体积缩减 80% 以上；
 * - 支持历史记录、追剧收藏（含软删除墓碑）、新剧预约与自定义站源的双向 LWW 无冲突合并。
 */
class WebDavSyncEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    data class SyncResult(
        val success: Boolean,
        val message: String,
        val mergedPayload: SyncPayload? = null,
        val httpCode: Int = 200
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun buildRemoteFolderUrl(config: WebDavConfig): String {
        val base = config.serverUrl.trim().trimEnd('/')
        return "$base/juju_tv_sync"
    }

    fun buildRemoteFileUrl(config: WebDavConfig): String {
        val cleanSpace = config.space.trim().ifEmpty { "default" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "")
            .ifEmpty { "default" }
        return "${buildRemoteFolderUrl(config)}/juju-sync-$cleanSpace.json.gz"
    }

    /**
     * 将 [SyncPayload] 序列化为 Gzip 压缩字节流
     */
    fun compressPayloadGzip(payload: SyncPayload): ByteArray {
        val jsonBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(jsonBytes.size / 2)
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(jsonBytes)
        }
        return bos.toByteArray()
    }

    /**
     * 解压 Gzip（或自动兼容明文 JSON）并反序列化为 [SyncPayload]
     */
    fun decompressPayloadGzip(rawBytes: ByteArray): SyncPayload? {
        if (rawBytes.isEmpty()) return null
        return try {
            val isGzip = rawBytes.size >= 2 &&
                    (rawBytes[0].toInt() and 0xFF) == 0x1F &&
                    (rawBytes[1].toInt() and 0xFF) == 0x8B
            val jsonText = if (isGzip) {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(rawBytes, Charsets.UTF_8)
            }
            gson.fromJson(jsonText, SyncPayload::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 剧集粒度双向 Last-Write-Wins (LWW) 无冲突合并算法
     */
    fun mergePayloads(local: SyncPayload, remote: SyncPayload): SyncPayload {
        // 1. 合并观看历史
        val historyMap = LinkedHashMap<String, WatchHistoryEntry>()
        for (h in (local.histories + remote.histories)) {
            val existing = historyMap[h.dramaId]
            if (existing == null) {
                historyMap[h.dramaId] = h
            } else {
                val winner = when {
                    h.updatedAt > existing.updatedAt -> h
                    h.updatedAt < existing.updatedAt -> existing
                    h.episodeIndex > existing.episodeIndex -> h
                    h.episodeIndex == existing.episodeIndex && h.positionMs > existing.positionMs -> h
                    else -> existing
                }
                historyMap[h.dramaId] = winner
            }
        }

        // 2. 合并追剧列表（支持软删除墓碑传播）
        val followMap = LinkedHashMap<String, FollowEntry>()
        for (f in (local.follows + remote.follows)) {
            val existing = followMap[f.dramaId]
            if (existing == null || f.updatedAt > existing.updatedAt) {
                followMap[f.dramaId] = f
            }
        }

        // 3. 合并预约列表
        val reserveMap = LinkedHashMap<String, ReserveEntry>()
        for (r in (local.reserves + remote.reserves)) {
            val existing = reserveMap[r.dramaId]
            if (existing == null || r.updatedAt > existing.updatedAt) {
                reserveMap[r.dramaId] = r
            }
        }

        // 4. 合并自定义站源
        val sourceMap = LinkedHashMap<String, CustomSourceConfig>()
        for (s in (local.customSources + remote.customSources)) {
            val existing = sourceMap[s.id]
            if (existing == null || s.updatedAt > existing.updatedAt) {
                sourceMap[s.id] = s
            }
        }

        return SyncPayload(
            version = maxOf(local.version, remote.version),
            space = local.space.ifBlank { remote.space },
            exportedAt = System.currentTimeMillis(),
            histories = historyMap.values.sortedByDescending { it.updatedAt },
            follows = followMap.values.sortedByDescending { it.updatedAt },
            reserves = reserveMap.values.sortedByDescending { it.updatedAt },
            customSources = sourceMap.values.sortedByDescending { it.updatedAt }
        )
    }

    /**
     * 拨测 WebDAV 服务器连通性 (`PROPFIND` Depth: 0)
     */
    fun probeConnection(config: WebDavConfig): SyncResult {
        if (config.serverUrl.isBlank() || config.username.isBlank()) {
            return SyncResult(false, "请填写完整的 WebDAV 服务器地址与账号", null, 400)
        }
        return try {
            val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
            val propfindBody = RequestBody.create(
                MediaType.parse("application/xml; charset=utf-8"),
                """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
            )
            val req = Request.Builder()
                .url(config.serverUrl.trim())
                .header("Authorization", auth)
                .header("Depth", "0")
                .method("PROPFIND", propfindBody)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code()
                if (code in 200..299 || code == 207) {
                    SyncResult(true, "WebDAV 连接成功 (HTTP $code)", null, code)
                } else if (code == 401 || code == 403) {
                    SyncResult(false, "账号或应用密码认证失败 (HTTP $code)，坚果云请使用「应用密码」而非登录密码", null, code)
                } else {
                    SyncResult(false, "WebDAV 服务器返回异常状态码 HTTP $code", null, code)
                }
            }
        } catch (e: Throwable) {
            SyncResult(false, "连接失败：${e.message ?: e.javaClass.simpleName}", null, 500)
        }
    }

    /**
     * 执行完整的双向拉取 -> LWW 合并 -> Gzip 推送同步流程
     */
    fun syncBidirectional(config: WebDavConfig, localPayload: SyncPayload): SyncResult {
        val probe = probeConnection(config)
        if (!probe.success) return probe

        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val folderUrl = buildRemoteFolderUrl(config)
        val fileUrl = buildRemoteFileUrl(config)

        return try {
            // 1. 确保远端目录存在 (MKCOL)
            val mkcolReq = Request.Builder()
                .url(folderUrl)
                .header("Authorization", auth)
                .method("MKCOL", null)
                .build()
            httpClient.newCall(mkcolReq).execute().close()

            // 2. 拉取远端已有 Gzip 同步包 (GET)
            val getReq = Request.Builder()
                .url(fileUrl)
                .header("Authorization", auth)
                .get()
                .build()
            val remotePayload: SyncPayload? = httpClient.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body()?.bytes() ?: ByteArray(0)
                    decompressPayloadGzip(bytes)
                } else {
                    null
                }
            }

            // 3. LWW 双向无冲突合并
            val merged = if (remotePayload != null) {
                mergePayloads(localPayload, remotePayload)
            } else {
                localPayload.copy(exportedAt = System.currentTimeMillis())
            }

            // 4. 压缩并上传最新合并结果 (PUT)
            val gzBytes = compressPayloadGzip(merged)
            val putReq = Request.Builder()
                .url(fileUrl)
                .header("Authorization", auth)
                .put(RequestBody.create(MediaType.parse("application/gzip"), gzBytes))
                .build()

            httpClient.newCall(putReq).execute().use { putResp ->
                if (putResp.isSuccessful || putResp.code() in listOf(200, 201, 204)) {
                    SyncResult(
                        success = true,
                        message = "同步完成（历史 ${merged.histories.size} 条 · 追剧 ${merged.follows.count { !it.deleted }} 部）",
                        mergedPayload = merged,
                        httpCode = putResp.code()
                    )
                } else {
                    SyncResult(
                        success = false,
                        message = "上传同步文件失败 HTTP ${putResp.code()}",
                        mergedPayload = merged,
                        httpCode = putResp.code()
                    )
                }
            }
        } catch (e: Throwable) {
            SyncResult(false, "同步异常：${e.message}", null, 500)
        }
    }
}
