package com.juju.tv.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.juju.tv.memory.ByteBufferPool
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.model.BrowseQuery
import com.juju.tv.model.CustomSourceConfig
import com.juju.tv.model.FollowEntry
import com.juju.tv.model.JuJuSettings
import com.juju.tv.model.PlaybackStateSnapshot
import com.juju.tv.model.ReserveEntry
import com.juju.tv.model.WebDavConfig
import com.juju.tv.provider.HongguoProtocol
import com.juju.tv.provider.ProviderRegistry
import com.juju.tv.storage.JuJuRepository
import com.juju.tv.sync.WebDavSyncEngine
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 聚聚 TV 内置局域网 HTTP 遥控与配置同步服务端
 *
 * 暴露全部 42 个 `/api/...` 路由 + `/` 手机遥控 SPA + `/webdav` 扫码配网 SPA：
 * 1. 握手与诊断：`/api/hello`, `/api/remote-info`, `/api/state`, `/api/diagnostics`
 * 2. 远程播控：`/api/play`, `/api/control`
 * 3. 首页、目录、榜单与找剧：`/api/home`, `/api/rail`, `/api/categories`, `/api/catalog`,
 *    `/api/rank-subs`, `/api/rank`, `/api/calendar`, `/api/newdrama`, `/api/browse-panel`, `/api/browse`, `/api/search`
 * 4. 详情、评论与防盗链代理：`/api/detail`, `/api/series`, `/api/comments`, `/api/cover`, `/api/avatar`
 * 5. 本机历史、追剧与预约：`/api/history`, `/api/follow`, `/api/reserve`
 * 6. 红果账号体系：`/api/hongguo/status`, `/api/hongguo/login`, `/api/hongguo/logout`, `/api/hongguo/reset-device`,
 *    `/api/hongguo/history`, `/api/hongguo/favorites`, `/api/hongguo/reservations`, `/api/hongguo/reserve`,
 *    `/api/hongguo/sms/prepare`, `/api/hongguo/sms/send`, `/api/hongguo/sms/verify`
 * 7. 设置、WebDAV 与自救源：`/api/settings`, `/api/webdav-session`, `/api/webdav`, `/api/sources`,
 *    `/api/update`, `/api/update/unlock`, `/api/update/start`
 */
class LanRemoteServer(
    port: Int = DEFAULT_PORT,
    private val repository: JuJuRepository,
    private val providerRegistry: ProviderRegistry,
    private val memoryGovernor: MemoryGovernor,
    private val webDavSyncEngine: WebDavSyncEngine = WebDavSyncEngine(),
    private val coverHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 18899
        private const val MAX_COVER_BYTES = 512 * 1024
    }

    private data class CachedCover(
        val contentType: String,
        val bytes: ByteArray
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    // 轻量封面代理缓存（最多保留 16 张已代理封面，避免手机端反复拉取相同海报）
    private val coverProxyCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedCover>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCover>?): Boolean {
                return size > 16
            }
        }
    )

    @Volatile
    var playbackStateProvider: () -> PlaybackStateSnapshot = { PlaybackStateSnapshot() }

    @Volatile
    var onRemotePlayCommand: ((dramaId: String, episodeIndex: Int) -> Unit)? = null

    @Volatile
    var onRemoteControlCommand: ((action: String, value: Long?) -> Unit)? = null

    @Volatile
    var onClientConnectedCallback: ((clientIp: String) -> Unit)? = null

    @Volatile
    var onSettingsChangedCallback: ((JuJuSettings) -> Unit)? = null

    @Volatile
    var onSourcesChangedCallback: (() -> Unit)? = null

    fun startServer(): Int {
        if (!isAlive) {
            try {
                start(SOCKET_READ_TIMEOUT, true)
            } catch (_: Throwable) {
                // Port occupied fallback handled by caller
            }
        }
        return listeningPort
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val params = session.parms ?: emptyMap()

        // 处理 CORS 预检
        if (method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK"))
        }

        return try {
            val resp = when (uri) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebRemoteAssets.renderRemoteControlHtml())
                "/manifest.json" -> newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", WebRemoteAssets.renderManifestJson())
                "/webdav" -> {
                    val token = params["t"] ?: ""
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebRemoteAssets.renderWebDavConfigHtml(token))
                }

                // 1. 握手、发现与诊断
                "/api/hello" -> {
                    val clientIp = session.remoteIpAddress ?: "unknown"
                    onClientConnectedCallback?.invoke(clientIp)
                    val mem = memoryGovernor.pollAndroidMemory(null)
                    jsonOk(
                        mapOf(
                            "ok" to true,
                            "app" to "聚聚 TV",
                            "version" to "1.0.0-n1air",
                            "device" to mem.deviceModel,
                            "availRamMb" to mem.systemAvailRamMb,
                            "watermark" to mem.watermark,
                            "watermarkLabel" to mem.watermarkLabel
                        )
                    )
                }
                "/api/remote-info" -> jsonOk(
                    mapOf(
                        "name" to "坚果 N1 Air 第四代 · 聚聚 TV",
                        "package" to "com.juju.tv",
                        "version" to "1.0.0-n1air",
                        "port" to listeningPort,
                        "mdns" to "_juju-remote._tcp"
                    )
                )
                "/api/state" -> jsonOk(playbackStateProvider())
                "/api/diagnostics" -> {
                    val mem = memoryGovernor.pollAndroidMemory(null)
                    val settings = repository.getSettings()
                    jsonOk(
                        mapOf(
                            "app" to "聚聚 (JuJu TV)",
                            "version" to "1.0.0-n1air",
                            "memory" to mem,
                            "decoderMode" to settings.decoderMode,
                            "preloadEnabled" to settings.preloadNextEpisode,
                            "danmakuEnabled" to settings.danmakuEnabled,
                            "sourceDomainOverrides" to settings.sourceDomainOverrides,
                            "sourceHealth" to providerRegistry.healthTracker.snapshotAll(),
                            "hongguoDeviceId" to HongguoProtocol.currentIdentity().deviceId
                        )
                    )
                }

                // 2. 远程播放控制
                "/api/play" -> {
                    val body = readJsonBody(session)
                    val dramaId = body.get("id")?.asString ?: params["id"] ?: ""
                    val epIndex = body.get("index")?.asInt ?: params["index"]?.toIntOrNull() ?: 1
                    if (dramaId.isBlank()) {
                        return corsResponse(jsonError(Response.Status.BAD_REQUEST, "Missing drama id"))
                    }
                    onRemotePlayCommand?.invoke(dramaId, epIndex)
                    jsonOk(mapOf("ok" to true, "id" to dramaId, "index" to epIndex))
                }
                "/api/control" -> {
                    val body = readJsonBody(session)
                    val action = body.get("action")?.asString ?: params["action"] ?: ""
                    val value = body.get("value")?.asLong ?: params["value"]?.toLongOrNull()
                    if (action.isBlank()) {
                        return corsResponse(jsonError(Response.Status.BAD_REQUEST, "Missing control action"))
                    }
                    onRemoteControlCommand?.invoke(action, value)
                    jsonOk(mapOf("ok" to true, "action" to action))
                }

                // 3. 首页、轨道、分类、榜单与找剧
                "/api/home" -> {
                    val rails = providerRegistry.fetchHomeRails()
                    val histories = repository.getHistories(10)
                    val state = playbackStateProvider()
                    jsonOk(
                        mapOf(
                            "hero" to (rails.firstOrNull()?.items?.firstOrNull()),
                            "nowPlaying" to state,
                            "continueWatching" to histories,
                            "railKeys" to listOf("hot", "category:漫剧", "category:真人剧", "category:AI 剧", "rest")
                        )
                    )
                }
                "/api/rail" -> {
                    val key = params["key"] ?: "hot"
                    jsonOk(providerRegistry.fetchSingleRail(key))
                }
                "/api/categories" -> jsonOk(mapOf("items" to providerRegistry.fetchCategories()))
                "/api/catalog" -> {
                    val cat = params["category"] ?: "全部"
                    val page = params["page"]?.toIntOrNull() ?: 1
                    jsonOk(mapOf("items" to providerRegistry.fetchCatalog(cat, page)))
                }
                "/api/rank-subs" -> jsonOk(mapOf("subs" to providerRegistry.fetchRankSubs()))
                "/api/rank" -> {
                    val sub = params["sub"] ?: "热播榜"
                    val page = params["page"]?.toIntOrNull() ?: 1
                    jsonOk(mapOf("sub" to sub, "items" to providerRegistry.fetchRankings(sub, page)))
                }
                "/api/calendar" -> jsonOk(mapOf("items" to providerRegistry.fetchCalendar(params["date"] ?: "")))
                "/api/newdrama" -> jsonOk(mapOf("items" to providerRegistry.fetchRankings("新剧榜", 1)))
                "/api/browse-panel" -> jsonOk(providerRegistry.getBrowseFilterPanel())
                "/api/browse" -> {
                    val q = BrowseQuery(
                        type = params["type"] ?: "全部",
                        genre = params["genre"] ?: "全部",
                        status = params["status"] ?: "全部",
                        sort = params["sort"] ?: "最热",
                        page = params["page"]?.toIntOrNull() ?: 1
                    )
                    jsonOk(mapOf("items" to providerRegistry.browse(q)))
                }
                "/api/search" -> {
                    val kw = params["q"] ?: params["keyword"] ?: ""
                    val page = params["page"]?.toIntOrNull() ?: 1
                    jsonOk(mapOf("q" to kw, "items" to providerRegistry.searchAll(kw, page)))
                }

                // 4. 详情、关联季、评论与封面防盗链代理
                "/api/detail" -> {
                    val id = params["id"] ?: return corsResponse(jsonError(Response.Status.BAD_REQUEST, "Missing id"))
                    val detail = providerRegistry.fetchDetail(id)
                        ?: return corsResponse(jsonError(Response.Status.NOT_FOUND, "Drama not found"))
                    jsonOk(
                        mapOf(
                            "detail" to detail,
                            "isFollowed" to repository.isFollowed(id),
                            "history" to repository.getHistoryForDrama(id)
                        )
                    )
                }
                "/api/series" -> {
                    val id = params["id"] ?: ""
                    jsonOk(mapOf("items" to providerRegistry.fetchRelatedSeasons(id)))
                }
                "/api/comments" -> {
                    val id = params["id"] ?: ""
                    val ep = params["ep"]?.toIntOrNull() ?: 1
                    jsonOk(mapOf("items" to providerRegistry.fetchComments(id, ep)))
                }
                "/api/cover", "/api/avatar" -> handleCoverProxy(params["url"] ?: "")

                // 5. 本机观看历史、追剧收藏与新剧预约
                "/api/history" -> jsonOk(mapOf("items" to repository.getHistories(50)))
                "/api/follow" -> {
                    if (method == Method.POST) {
                        val body = readJsonBody(session)
                        val action = body.get("action")?.asString ?: "add"
                        val dramaId = body.get("dramaId")?.asString ?: body.get("id")?.asString ?: ""
                        if (action == "remove") {
                            repository.removeFollow(dramaId)
                        } else if (dramaId.isNotBlank()) {
                            val detail = providerRegistry.fetchDetail(dramaId)
                            repository.upsertFollow(
                                FollowEntry(
                                    dramaId = dramaId,
                                    title = body.get("title")?.asString ?: detail?.title ?: dramaId,
                                    coverUrl = body.get("coverUrl")?.asString ?: detail?.coverUrl ?: "",
                                    sourceId = detail?.sourceId ?: "hongguo",
                                    totalEpisodes = detail?.totalEpisodes ?: 80,
                                    status = body.get("status")?.asString ?: "watching"
                                )
                            )
                        }
                    }
                    jsonOk(mapOf("items" to repository.getFollows(params["status"])))
                }
                "/api/reserve" -> {
                    if (method == Method.POST) {
                        val body = readJsonBody(session)
                        val dramaId = body.get("dramaId")?.asString ?: body.get("id")?.asString ?: ""
                        if (dramaId.isNotBlank()) {
                            val detail = providerRegistry.fetchDetail(dramaId)
                            repository.toggleReserve(
                                ReserveEntry(
                                    dramaId = dramaId,
                                    title = detail?.title ?: dramaId,
                                    coverUrl = detail?.coverUrl ?: "",
                                    sourceId = detail?.sourceId ?: "hongguo",
                                    expectedDate = body.get("expectedDate")?.asString ?: "即将上线"
                                )
                            )
                        }
                    }
                    jsonOk(mapOf("items" to repository.getReserves()))
                }

                // 6. 设置、WebDAV 与去中心化自救源
                "/api/settings" -> {
                    if (method == Method.POST) {
                        val body = readJsonBody(session)
                        val updated = repository.updateSettings { old ->
                            val newEnabledSources = old.enabledSources.toMutableMap()
                            body.getAsJsonObject("enabledSources")?.entrySet()?.forEach { (k, v) ->
                                if (v.isJsonPrimitive) {
                                    newEnabledSources[k] = v.asBoolean
                                    providerRegistry.healthTracker.reset(k)
                                }
                            }

                            val newDomainOverrides = old.sourceDomainOverrides.toMutableMap()
                            body.getAsJsonObject("sourceDomainOverrides")?.entrySet()?.forEach { (k, v) ->
                                if (v.isJsonPrimitive) {
                                    val domain = v.asString.trim()
                                    if (domain.isEmpty()) newDomainOverrides.remove(k)
                                    else newDomainOverrides[k] = domain
                                    providerRegistry.healthTracker.reset(k)
                                }
                            }

                            val newBlockWords = if (body.has("danmakuBlockWords") && body.get("danmakuBlockWords").isJsonArray) {
                                body.getAsJsonArray("danmakuBlockWords").mapNotNull {
                                    if (it.isJsonPrimitive) it.asString.trim().takeIf { s -> s.isNotEmpty() } else null
                                }
                            } else old.danmakuBlockWords

                            old.copy(
                                decoderMode = body.get("decoderMode")?.asString ?: old.decoderMode,
                                playbackSpeed = body.get("playbackSpeed")?.asFloat ?: old.playbackSpeed,
                                defaultQuality = body.get("defaultQuality")?.asString ?: old.defaultQuality,
                                autoAdvanceEpisode = body.get("autoAdvanceEpisode")?.asBoolean ?: old.autoAdvanceEpisode,
                                preloadNextEpisode = body.get("preloadNextEpisode")?.asBoolean ?: old.preloadNextEpisode,
                                danmakuEnabled = body.get("danmakuEnabled")?.asBoolean ?: old.danmakuEnabled,
                                danmakuAreaRatio = body.get("danmakuAreaRatio")?.asFloat ?: old.danmakuAreaRatio,
                                danmakuFontScale = body.get("danmakuFontScale")?.asFloat ?: old.danmakuFontScale,
                                danmakuOpacity = body.get("danmakuOpacity")?.asFloat ?: old.danmakuOpacity,
                                danmakuSpeed = body.get("danmakuSpeed")?.asFloat ?: old.danmakuSpeed,
                                danmakuBlockWords = newBlockWords,
                                alwaysEnableLanRemote = body.get("alwaysEnableLanRemote")?.asBoolean ?: old.alwaysEnableLanRemote,
                                posterColumns = body.get("posterColumns")?.asInt ?: old.posterColumns,
                                enabledSources = newEnabledSources,
                                sourceDomainOverrides = newDomainOverrides
                            )
                        }
                        onSettingsChangedCallback?.invoke(updated)
                    }
                    jsonOk(repository.getSettings())
                }
                "/api/webdav-session" -> {
                    val token = repository.issueWebDavSessionToken()
                    jsonOk(mapOf("ok" to true, "token" to token, "url" to "/webdav?t=$token"))
                }
                "/api/webdav" -> {
                    val token = params["t"]
                    if (!repository.validateWebDavSessionToken(token)) {
                        return corsResponse(jsonError(Response.Status.FORBIDDEN, "临时会话令牌已过期，请从电视或遥控页重新进入"))
                    }
                    if (method == Method.POST) {
                        val body = readJsonBody(session)
                        val saved = repository.saveWebDavConfig(
                            WebDavConfig(
                                serverUrl = body.get("serverUrl")?.asString ?: "https://dav.jianguoyun.com/dav/",
                                username = body.get("username")?.asString ?: "",
                                password = body.get("password")?.asString ?: "",
                                space = body.get("space")?.asString ?: "default"
                            )
                        )
                        val skipProbe = body.get("skipProbe")?.asBoolean ?: false
                        val syncRes = if (skipProbe) {
                            WebDavSyncEngine.SyncResult(true, "配置已保存", null, 200)
                        } else {
                            webDavSyncEngine.syncBidirectional(saved, repository.exportSyncPayload())
                        }
                        if (syncRes.mergedPayload != null) {
                            repository.applyMergedSyncPayload(syncRes.mergedPayload)
                        }
                        jsonOk(mapOf("ok" to syncRes.success, "message" to syncRes.message))
                    } else {
                        val cfg = repository.getWebDavConfig()
                        jsonOk(
                            mapOf(
                                "serverUrl" to cfg.serverUrl,
                                "username" to cfg.username,
                                "hasPassword" to cfg.password.isNotEmpty(),
                                "space" to cfg.space,
                                "lastSyncTime" to cfg.lastSyncTime
                            )
                        )
                    }
                }
                "/api/sources" -> {
                    if (method == Method.POST) {
                        val body = readJsonBody(session)
                        val id = body.get("id")?.asString ?: "src_${UUID.randomUUID().toString().take(6)}"
                        val name = body.get("name")?.asString ?: "自定义源"
                        val url = body.get("url")?.asString ?: ""
                        val type = body.get("type")?.asString ?: "json"
                        val rawContent = body.get("rawContent")?.asString
                        val cfg = CustomSourceConfig(id = id, name = name, url = url, type = type, enabled = true)
                        repository.upsertCustomSource(cfg)
                        val loaded = if (!rawContent.isNullOrBlank()) {
                            providerRegistry.customSourceEngine.loadFromRawContent(cfg, rawContent)
                        } else {
                            providerRegistry.customSourceEngine.refreshCustomSource(cfg)
                        }
                        providerRegistry.resetHealthAndCaches()
                        onSourcesChangedCallback?.invoke()
                        return corsResponse(
                            jsonOk(
                                mapOf(
                                    "ok" to true,
                                    "message" to "已挂载自定义源「$name」（解析剧集 ${loaded.size} 部）",
                                    "customSources" to repository.getCustomSources()
                                )
                            )
                        )
                    } else if (method == Method.DELETE) {
                        val id = params["id"] ?: readJsonBody(session).get("id")?.asString ?: ""
                        if (id.isNotBlank()) {
                            repository.deleteCustomSource(id)
                            providerRegistry.customSourceEngine.removeCustomSource(id)
                            providerRegistry.resetHealthAndCaches()
                            onSourcesChangedCallback?.invoke()
                        }
                    }
                    jsonOk(
                        mapOf(
                            "customSources" to repository.getCustomSources(),
                            "domainOverrides" to repository.getSettings().sourceDomainOverrides,
                            "health" to providerRegistry.healthTracker.snapshotAll()
                        )
                    )
                }

                // 7. 红果官方账号与设备身份辅助端点 + 无门禁更新端点
                "/api/hongguo/status" -> jsonOk(
                    mapOf(
                        "loggedIn" to false,
                        "deviceId" to HongguoProtocol.currentIdentity().deviceId,
                        "installId" to HongguoProtocol.currentIdentity().installId
                    )
                )
                "/api/hongguo/reset-device" -> {
                    val fresh = HongguoProtocol.resetIdentity()
                    providerRegistry.resetHealthAndCaches()
                    onSourcesChangedCallback?.invoke()
                    jsonOk(mapOf("ok" to true, "deviceId" to fresh.deviceId, "installId" to fresh.installId))
                }
                "/api/hongguo/login", "/api/hongguo/logout",
                "/api/hongguo/history", "/api/hongguo/favorites",
                "/api/hongguo/reservations", "/api/hongguo/reserve",
                "/api/hongguo/sms/prepare", "/api/hongguo/sms/send", "/api/hongguo/sms/verify" -> {
                    jsonOk(mapOf("ok" to true, "items" to emptyList<Any>(), "message" to "聚聚免登录直连模式已开启"))
                }
                "/api/update", "/api/update/unlock", "/api/update/start" -> {
                    // 彻底移除原版 4 位 TG 群密码门禁，永久解锁！
                    jsonOk(mapOf("ok" to true, "unlocked" to true, "hasUpdate" to false, "version" to "1.0.0-n1air"))
                }

                else -> jsonError(Response.Status.NOT_FOUND, "Unknown API route: $uri")
            }
            corsResponse(resp)
        } catch (e: Throwable) {
            corsResponse(jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "Internal Server Error"))
        }
    }

    /**
     * 电视端代拉封面/头像防盗链代理（支持 LRU 缓存与 SVG 兜底占位）
     */
    private fun handleCoverProxy(rawUrl: String): Response {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            coverProxyCache[trimmed]?.let { cached ->
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    cached.contentType,
                    ByteArrayInputStream(cached.bytes),
                    cached.bytes.size.toLong()
                )
                resp.addHeader("Cache-Control", "public, max-age=86400")
                return resp
            }

            try {
                val originReferer = if (trimmed.contains("byteimg.com", ignoreCase = true) ||
                    trimmed.contains("fqnovelpic.com", ignoreCase = true)
                ) {
                    "https://hongguoduanju.com/"
                } else {
                    try {
                        val u = URI(trimmed)
                        "${u.scheme}://${u.host}/"
                    } catch (_: Throwable) {
                        "https://hongguoduanju.com/"
                    }
                }
                val req = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; JMGO N1 Air) AppleWebKit/537.36 Chrome/116.0.0.0 Safari/537.36")
                    .header("Referer", originReferer)
                    .build()

                coverHttpClient.newCall(req).execute().use { upstream ->
                    if (upstream.isSuccessful) {
                        val bodyStream = upstream.body()?.byteStream()
                        if (bodyStream != null) {
                            val bos = ByteArrayOutputStream(32 * 1024)
                            ByteBufferPool.shared.withBuffer { scratch ->
                                var total = 0
                                while (total < MAX_COVER_BYTES) {
                                    val toRead = minOf(scratch.size, MAX_COVER_BYTES - total)
                                    val n = bodyStream.read(scratch, 0, toRead)
                                    if (n <= 0) break
                                    bos.write(scratch, 0, n)
                                    total += n
                                }
                            }
                            val imgBytes = bos.toByteArray()
                            if (imgBytes.isNotEmpty()) {
                                val contentType = upstream.header("Content-Type")?.takeIf { it.isNotBlank() }
                                    ?: inferImageMimeType(trimmed)
                                if (imgBytes.size <= 128 * 1024) {
                                    coverProxyCache[trimmed] = CachedCover(contentType, imgBytes)
                                }
                                val resp = newFixedLengthResponse(
                                    Response.Status.OK,
                                    contentType,
                                    ByteArrayInputStream(imgBytes),
                                    imgBytes.size.toLong()
                                )
                                resp.addHeader("Cache-Control", "public, max-age=86400")
                                return resp
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fallback to stylized SVG poster below
            }
        }
        return handleCoverSvgPlaceholder(trimmed)
    }

    private fun inferImageMimeType(url: String): String {
        val path = url.substringBefore("?").lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".svg") -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }

    private fun handleCoverSvgPlaceholder(rawUrl: String): Response {
        val label = rawUrl.substringAfterLast("/").take(12).ifEmpty { "聚聚海报" }
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="240" height="336" viewBox="0 0 240 336">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0%" stop-color="#1E293B"/>
                  <stop offset="100%" stop-color="#0F172A"/>
                </linearGradient>
              </defs>
              <rect width="240" height="336" rx="16" fill="url(#g)"/>
              <circle cx="120" cy="140" r="36" fill="#FF6B35" opacity="0.85"/>
              <polygon points="110,124 110,156 138,140" fill="#FFFFFF"/>
              <text x="120" y="220" text-anchor="middle" fill="#F8FAFC" font-size="16" font-family="sans-serif">聚聚 · 1080P</text>
              <text x="120" y="246" text-anchor="middle" fill="#94A3B8" font-size="12" font-family="sans-serif">$label</text>
            </svg>
        """.trimIndent()
        val bytes = svg.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun readJsonBody(session: IHTTPSession): JsonObject {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return JsonObject()
            if (postData.isBlank()) JsonObject()
            else JsonParser.parseString(postData).asJsonObject
        } catch (_: Throwable) {
            JsonObject()
        }
    }

    private fun jsonOk(payload: Any): Response {
        val json = gson.toJson(payload)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val json = gson.toJson(mapOf("ok" to false, "message" to message))
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }
}
