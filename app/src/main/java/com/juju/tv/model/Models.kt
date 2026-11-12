package com.juju.tv.model

/**
 * 剧集卡片模型（用于首页轨道、分类目录、榜单、搜索结果）
 */
data class DramaCard(
    val id: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String = "hongguo",
    val sourceName: String = "红果短剧",
    val category: String = "真人剧",
    val tags: List<String> = emptyList(),
    val totalEpisodes: Int = 80,
    val updatedEpisodes: Int = 80,
    val isFinished: Boolean = true,
    val score: String = "9.6",
    val playCountText: String = "1280.5万播放",
    val intro: String = "",
    val releaseDate: String = "2026",
    val latestEpisodeText: String = "全80集"
)

/**
 * 单集信息模型
 */
data class EpisodeItem(
    val index: Int,
    val episodeId: String,
    val title: String,
    val durationSec: Int = 95,
    val isVip: Boolean = false,
    val streamUrl: String = "",
    val isCencEncrypted: Boolean = false,
    val cencKeyHex: String = "",
    val cencKidHex: String = "",
    val headers: Map<String, String> = emptyMap()
)

/**
 * 演职员信息
 */
data class CreatorInfo(
    val id: String,
    val name: String,
    val role: String = "主演",
    val avatarUrl: String = ""
)

/**
 * 剧集完整详情模型
 */
data class DramaDetail(
    val id: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val sourceName: String,
    val category: String,
    val tags: List<String>,
    val totalEpisodes: Int,
    val isFinished: Boolean,
    val score: String,
    val playCountText: String,
    val intro: String,
    val releaseYear: String = "2026",
    val creators: List<CreatorInfo> = emptyList(),
    val episodes: List<EpisodeItem> = emptyList(),
    val relatedSeasons: List<DramaCard> = emptyList()
)

/**
 * 播放流解析结果
 */
data class ResolvedStream(
    val dramaId: String,
    val episodeIndex: Int,
    val originalUrl: String,
    val localPlaybackUrl: String,
    val mimeType: String = "video/mp4",
    val qualityLabel: String = "1080P 高清",
    val isCenc: Boolean = false,
    val cencKeyHex: String = "",
    val cencKidHex: String = "",
    val headers: Map<String, String> = emptyMap(),
    val preloadedFilePath: String? = null
)

/**
 * 弹幕条目
 */
data class DanmakuItem(
    val id: String,
    val timeMs: Long,
    val text: String,
    val colorRgb: Int = 0xFFFFFF,
    val isAuthor: Boolean = false
)

/**
 * 评论/长剧评条目
 */
data class CommentItem(
    val id: String,
    val userName: String,
    val avatarUrl: String = "",
    val content: String,
    val likeCount: Int = 0,
    val createdAt: String = "刚刚",
    val episodeIndex: Int = 1,
    val isLongReview: Boolean = false
)

/**
 * 首页轨道定义与数据
 */
data class RailFeed(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val items: List<DramaCard> = emptyList()
)

/**
 * 分类节点
 */
data class CategoryNode(
    val id: String,
    val name: String,
    val groupType: String = "genre", // "type" (剧种) or "genre" (题材)
    val badge: String = ""
)

/**
 * 手机端专属「找剧」多维筛选面板配置
 */
data class BrowseFilterPanel(
    val types: List<String> = listOf("全部", "真人剧", "漫剧", "AI 剧"),
    val genres: List<String> = listOf(
        "全部", "都市", "逆袭", "战神", "古装", "甜宠", "重生",
        "悬疑", "家庭", "萌宝", "仙侠", "脑洞", "年代"
    ),
    val statuses: List<String> = listOf("全部", "已完结", "连载中"),
    val sorts: List<String> = listOf("最热", "最新", "评分最高")
)

/**
 * 多维找剧查询条件
 */
data class BrowseQuery(
    val type: String = "全部",
    val genre: String = "全部",
    val status: String = "全部",
    val sort: String = "最热",
    val page: Int = 1,
    val pageSize: Int = 24
)

/**
 * 观看历史记录（精确到集号与毫秒进度，用于断点续播与 WebDAV LWW 同步）
 */
data class WatchHistoryEntry(
    val dramaId: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val episodeIndex: Int,
    val episodeTitle: String,
    val totalEpisodes: Int,
    val positionMs: Long,
    val durationMs: Long,
    val progressPercent: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 追剧/收藏条目
 */
data class FollowEntry(
    val dramaId: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val totalEpisodes: Int,
    val lastEpisodeIndex: Int = 1,
    val status: String = "watching", // "want" (想看) | "watching" (在看) | "finished" (看过)
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

/**
 * 新剧预约条目
 */
data class ReserveEntry(
    val dramaId: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val expectedDate: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

/**
 * 解码方式三档枚举（专为坚果 N1 Air / 电视盒子调优）
 */
enum class DecoderMode(val code: String, val label: String, val desc: String) {
    AUTO_ZERO_COPY(
        "auto",
        "自动（零拷贝直出）",
        "默认。MediaCodec 硬件解码后直接送 SurfaceView 硬件层，最省内存与功耗。"
    ),
    COMPATIBLE_COPY(
        "compatible",
        "兼容（跳过零拷贝）",
        "画面一顿一顿、闪烁或切集黑屏时选此项，跳过零拷贝直出。"
    ),
    SOFTWARE_FALLBACK(
        "software",
        "软解兜底",
        "格式兼容面最宽，个别偏门编码硬解花屏时切换，但更耗 CPU。"
    );

    companion object {
        fun fromCode(code: String?): DecoderMode {
            return values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO_ZERO_COPY
        }
    }
}

/**
 * 电视全局设置模型
 */
data class JuJuSettings(
    val decoderMode: String = DecoderMode.AUTO_ZERO_COPY.code,
    val playbackSpeed: Float = 1.0f,
    val defaultQuality: String = "1080P",
    val autoAdvanceEpisode: Boolean = true,
    val preloadNextEpisode: Boolean = true,
    val danmakuEnabled: Boolean = true,
    val danmakuAreaRatio: Float = 0.5f,      // 0.25 (1/4屏), 0.5 (半屏), 0.75, 1.0
    val danmakuFontScale: Float = 1.0f,      // 0.8 ~ 1.4
    val danmakuOpacity: Float = 0.85f,       // 0.3 ~ 1.0
    val danmakuSpeed: Float = 1.0f,
    val danmakuBlockWords: List<String> = emptyList(),
    val alwaysEnableLanRemote: Boolean = true,
    val posterColumns: Int = 6,
    val enabledSources: Map<String, Boolean> = mapOf(
        "hongguo" to true,
        "huangju" to true,
        "huangdou" to true,
        "custom" to true
    ),
    val sourceDomainOverrides: Map<String, String> = emptyMap()
)

/**
 * WebDAV 同步配置
 */
data class WebDavConfig(
    val serverUrl: String = "https://dav.jianguoyun.com/dav/",
    val username: String = "",
    val password: String = "",
    val space: String = "default",
    val autoSync: Boolean = true,
    val lastSyncTime: Long = 0L,
    val lastSyncStatus: String = "未同步"
)

/**
 * 去中心化自定义站源配置（支持 JSON 接口或 M3U8 列表）
 */
data class CustomSourceConfig(
    val id: String,
    val name: String,
    val url: String,
    val type: String = "json", // "json" | "m3u"
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * WebDAV 跨设备 Gzip 同步数据包（支持 LWW 合并）
 */
data class SyncPayload(
    val version: Int = 1,
    val deviceName: String = "JMGO N1 Air 4th · 聚聚 TV",
    val space: String = "default",
    val exportedAt: Long = System.currentTimeMillis(),
    val histories: List<WatchHistoryEntry> = emptyList(),
    val follows: List<FollowEntry> = emptyList(),
    val reserves: List<ReserveEntry> = emptyList(),
    val customSources: List<CustomSourceConfig> = emptyList()
)

/**
 * 电视当前实时播放状态快照（供 `/api/state` 每 1.5s 轮询）
 */
data class PlaybackStateSnapshot(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = true,
    val dramaId: String = "",
    val dramaTitle: String = "",
    val sourceId: String = "",
    val episodeIndex: Int = 1,
    val totalEpisodes: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volumePercent: Int = 80,
    val playbackSpeed: Float = 1.0f,
    val decoderMode: String = DecoderMode.AUTO_ZERO_COPY.code,
    val qualityLabel: String = "1080P"
)

/**
 * 2GB RAM 内存水位枚举与诊断快照
 */
enum class MemoryWatermark(val label: String, val maxPosterCacheBytes: Int, val allowDanmaku: Boolean) {
    NORMAL("充裕 (Normal)", 12 * 1024 * 1024, true),
    MODERATE("温和 (Moderate)", 6 * 1024 * 1024, true),
    LOW("紧张 (Low - 已挂起弹幕并缩容)", 2560 * 1024, false),
    CRITICAL("危急 (Critical - 已清空纹理池保播放)", 512 * 1024, false)
}

data class MemoryStatusSnapshot(
    val deviceModel: String = "JMGO N1 Air (4th Gen)",
    val androidRelease: String = "Android 13 (API 33)",
    val systemTotalRamMb: Long = 2048L,
    val systemAvailRamMb: Long = 620L,
    val systemLowMemory: Boolean = false,
    val jvmUsedHeapMb: Long = 28L,
    val jvmMaxHeapMb: Long = 192L,
    val watermark: String = MemoryWatermark.NORMAL.name,
    val watermarkLabel: String = MemoryWatermark.NORMAL.label,
    val posterCacheLimitKb: Int = 12288,
    val posterCacheUsedKb: Int = 0,
    val preloadCacheFiles: Int = 0,
    val preloadCacheBytes: Long = 0L,
    val byteBufferPoolAvailable: Int = 8
)
