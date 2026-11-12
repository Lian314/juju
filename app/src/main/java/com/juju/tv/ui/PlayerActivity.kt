package com.juju.tv.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.juju.tv.JuJuApplication
import com.juju.tv.model.DecoderMode
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.WatchHistoryEntry
import com.juju.tv.player.JuJuPlayerManager
import com.juju.tv.player.LiteDanmakuView
import java.util.concurrent.Executors

/**
 * 聚聚 (JuJu TV) 全屏硬件直出播放页
 *
 * 针对坚果 N1 Air 第四代 (2GB RAM · Android 13) 的核心工程设计：
 * 1. 使用原生 [SurfaceView] 直接将 MediaCodec 解码帧送入硬件合成器 Hardware Overlay，不占用 GPU 离屏纹理；
 * 2. 顶层叠加零对象分配的 [LiteDanmakuView]（受 [MemoryGovernor] 实时管控，低内存自动挂起）；
 * 3. 当前集播放至 65% 时自动通知 [EpisodePreloadManager] 预缓存下一集前 1.5MB 到本地，并在切集时调用 `keepWindow(2)` 清理旧缓存；
 * 4. 完整支持遥控器交互：
 *    - `OK / Enter`：暂停/继续播放
 *    - `左 / 右方向键`：快退/快进 10 秒
 *    - `上 / 下方向键`：无缝切换上一集/下一集
 *    - `菜单键 (MENU)`：呼出播放控制面板（倍速 0.75x~2.0x / 三档解码切换 / 弹幕开关 / 选集）
 */
class PlayerActivity : AppCompatActivity() {

    private val app: JuJuApplication by lazy { JuJuApplication.instance }
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var surfaceView: SurfaceView
    private lateinit var danmakuView: LiteDanmakuView
    private lateinit var hudOverlay: LinearLayout
    private lateinit var hudTitleView: TextView
    private lateinit var hudProgressView: TextView
    private lateinit var hudHintView: TextView

    private lateinit var playerManager: JuJuPlayerManager
    private var currentDetail: DramaDetail? = null
    private var currentEpisodeIndex: Int = 1

    // 左右方向键长按 1 秒刻度平滑快进/快退机制
    private var isLongPressSeeking = false
    private var longPressKeyCode = 0
    private var virtualSeekPosMs = -1L
    private var wasPausedBeforeSeek = false
    private var longPressStartTime = 0L
    private var lastThrottledSeekTime = 0L

    private val startLongPressRunnable = Runnable {
        if (longPressKeyCode != 0) {
            startLongPressSeeking(longPressKeyCode)
        }
    }

    private val longPressTickRunnable = object : Runnable {
        override fun run() {
            if (isLongPressSeeking && longPressKeyCode != 0) {
                executeLongPressTick(longPressKeyCode)
                scheduleNextLongPressTick()
            }
        }
    }

    private val progressLoop = object : Runnable {
        override fun run() {
            playerManager.checkAndTriggerPreloadIfNeeded()
            updateHudAndSaveHistory()
            mainHandler.postDelayed(this, 1_500L)
        }
    }

    private val hideHudRunnable = Runnable {
        hudOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupPlayerUi()

        playerManager = JuJuPlayerManager(this)
        val settings = app.repository.getSettings()
        playerManager.initPlayer(DecoderMode.fromCode(settings.decoderMode), surfaceView)

        playerManager.onEpisodeEnded = {
            if (app.repository.getSettings().autoAdvanceEpisode) {
                playNextEpisode()
            }
        }
        playerManager.onPreloadTriggerReached = { dramaId, nextEpIndex ->
            if (app.repository.getSettings().preloadNextEpisode) {
                triggerNextEpisodePreload(dramaId, nextEpIndex)
            }
        }
        playerManager.onPlayerError = { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // 绑定手机扫码遥控实时控制与设置热更新回调
        app.lanRemoteServer.playbackStateProvider = { playerManager.snapshotState() }
        app.lanRemoteServer.onRemoteControlCommand = { action, value ->
            mainHandler.post { handleRemoteControlAction(action, value) }
        }
        app.lanRemoteServer.onSettingsChangedCallback = { updated ->
            mainHandler.post {
                playerManager.setPlaybackSpeed(updated.playbackSpeed)
                danmakuView.danmakuEnabled = updated.danmakuEnabled
                danmakuView.areaRatio = updated.danmakuAreaRatio
                danmakuView.fontScale = updated.danmakuFontScale
                danmakuView.danmakuAlpha = (updated.danmakuOpacity * 255).toInt().coerceIn(60, 255)
                danmakuView.danmakuSpeed = updated.danmakuSpeed
                danmakuView.blockWords = updated.danmakuBlockWords
                danmakuView.invalidate()

                val curSnap = playerManager.snapshotState()
                val requestedMode = DecoderMode.fromCode(updated.decoderMode)
                if (curSnap.decoderMode != requestedMode.code) {
                    val pos = curSnap.positionMs
                    playerManager.initPlayer(requestedMode, surfaceView)
                    currentDetail?.let { playEpisodeInternal(it, currentEpisodeIndex, pos) }
                }
            }
        }

        val dramaId = intent.getStringExtra("drama_id") ?: "hg_1001"
        val epIndex = intent.getIntExtra("episode_index", 1)
        val startPos = intent.getLongExtra("start_position_ms", 0L)
        loadDramaAndPlay(dramaId, epIndex, startPos)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(progressLoop)
    }

    override fun onPause() {
        super.onPause()
        if (isLongPressSeeking) {
            finishLongPressSeeking()
        } else {
            mainHandler.removeCallbacks(startLongPressRunnable)
            mainHandler.removeCallbacks(longPressTickRunnable)
        }
        mainHandler.removeCallbacks(progressLoop)
        updateHudAndSaveHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.lanRemoteServer.playbackStateProvider = { com.juju.tv.model.PlaybackStateSnapshot() }
        app.lanRemoteServer.onRemoteControlCommand = null
        app.lanRemoteServer.onSettingsChangedCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
        playerManager.releasePlayer()
    }

    private fun setupPlayerUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. 底层硬件直出 SurfaceView
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(surfaceView)

        // 2. 中层零分配硬件加速弹幕层
        val settings = app.repository.getSettings()
        danmakuView = LiteDanmakuView(this).apply {
            memoryGovernor = app.memoryGovernor
            danmakuEnabled = settings.danmakuEnabled
            areaRatio = settings.danmakuAreaRatio
            fontScale = settings.danmakuFontScale
            danmakuAlpha = (settings.danmakuOpacity * 255).toInt().coerceIn(60, 255)
            danmakuSpeed = settings.danmakuSpeed
            blockWords = settings.danmakuBlockWords
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(danmakuView)

        // 3. 顶层大屏播控 HUD 状态条
        hudOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0B0E14"))
            setPadding(36, 20, 36, 20)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            layoutParams = lp
        }

        hudTitleView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        hudProgressView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 6, 0, 6)
        }
        hudHintView = TextView(this).apply {
            text = "左右键：短按10秒 / 长按1秒精准连续微调  ·  上下键：上一集/下一集  ·  确认键：暂停/播放  ·  菜单键：设置"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        hudOverlay.addView(hudTitleView)
        hudOverlay.addView(hudProgressView)
        hudOverlay.addView(hudHintView)
        root.addView(hudOverlay)

        setContentView(root)
    }

    private fun loadDramaAndPlay(dramaId: String, episodeIndex: Int, startPositionMs: Long) {
        bgExecutor.execute {
            val detail = app.providerRegistry.fetchDetail(dramaId) ?: return@execute
            currentDetail = detail
            playEpisodeInternal(detail, episodeIndex, startPositionMs)
        }
    }

    private fun playEpisodeInternal(detail: DramaDetail, episodeIndex: Int, startPositionMs: Long) {
        val targetEp = detail.episodes.firstOrNull { it.index == episodeIndex }
            ?: detail.episodes.firstOrNull()
            ?: return
        currentEpisodeIndex = targetEp.index

        // 维护滑动窗口：只保留当前集和下一集的本地预加载文件，立即清理更早的分集缓存
        app.preloadManager.keepWindow(detail.id, currentEpisodeIndex, windowSize = 2)

        val resolved = app.streamGateway.buildPlaybackStream(detail.id, targetEp)
        val danmakuList = app.providerRegistry.fetchDanmaku(detail.id, currentEpisodeIndex)
        val settings = app.repository.getSettings()

        mainHandler.post {
            hudTitleView.text = "《${detail.title}》 · 第 ${targetEp.index}/${detail.totalEpisodes} 集" +
                    (if (resolved.preloadedFilePath != null) " ⚡ [已命中本地预加载]" else "")
            showHudTemporarily()
            danmakuView.setDanmakuList(danmakuList)
            playerManager.playResolvedStream(
                stream = resolved,
                dramaTitle = detail.title,
                sourceId = detail.sourceId,
                totalEps = detail.totalEpisodes,
                startPositionMs = startPositionMs,
                speed = settings.playbackSpeed
            )
        }
    }

    private fun triggerNextEpisodePreload(dramaId: String, nextEpIndex: Int) {
        val detail = currentDetail ?: return
        val nextEp = detail.episodes.firstOrNull { it.index == nextEpIndex } ?: return
        bgExecutor.execute {
            app.preloadManager.preloadFromNetwork(
                dramaId = dramaId,
                episodeIndex = nextEpIndex,
                streamUrl = nextEp.streamUrl,
                headers = nextEp.headers,
                isCenc = nextEp.isCencEncrypted,
                cencKeyHex = nextEp.cencKeyHex,
                cencKidHex = nextEp.cencKidHex
            )
        }
    }

    private fun playNextEpisode() {
        val detail = currentDetail ?: return
        if (currentEpisodeIndex < detail.totalEpisodes) {
            playEpisodeInternal(detail, currentEpisodeIndex + 1, 0L)
        } else {
            Toast.makeText(this, "🎉 已播完全部 ${detail.totalEpisodes} 集", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPreviousEpisode() {
        val detail = currentDetail ?: return
        if (currentEpisodeIndex > 1) {
            playEpisodeInternal(detail, currentEpisodeIndex - 1, 0L)
        } else {
            Toast.makeText(this, "已经是第 1 集", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleRemoteControlAction(action: String, value: Long?) {
        when (action) {
            "toggle" -> {
                playerManager.togglePlayPause()
                danmakuView.setPlaybackPaused(playerManager.snapshotState().isPaused)
                showHudTemporarily()
            }
            "prev" -> playPreviousEpisode()
            "next" -> playNextEpisode()
            "seek_back" -> {
                playerManager.seekRelative(-10_000L)
                showHudTemporarily()
            }
            "seek_fwd" -> {
                playerManager.seekRelative(10_000L)
                showHudTemporarily()
            }
            "seek" -> {
                if (value != null) playerManager.seekTo(value)
                showHudTemporarily()
            }
            "volume" -> {
                if (value != null) playerManager.setVolumePercent(value.toInt())
            }
            "exit" -> finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        ) {
            if (event?.repeatCount == 0) {
                longPressKeyCode = keyCode
                isLongPressSeeking = false
                longPressStartTime = SystemClock.uptimeMillis()
                mainHandler.removeCallbacks(startLongPressRunnable)
                // 240ms 未松开则视为长按，激活 1 秒刻度平滑连续微调
                mainHandler.postDelayed(startLongPressRunnable, 240L)
                return true
            } else if (event != null && event.repeatCount > 0) {
                // 系统连续连按事件，立即激活长按
                if (!isLongPressSeeking && longPressKeyCode == keyCode) {
                    mainHandler.removeCallbacks(startLongPressRunnable)
                    startLongPressSeeking(keyCode)
                }
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                playerManager.togglePlayPause()
                danmakuView.setPlaybackPaused(playerManager.snapshotState().isPaused)
                showHudTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playPreviousEpisode()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNextEpisode()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showPlayerSettingsDialog()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        ) {
            mainHandler.removeCallbacks(startLongPressRunnable)
            mainHandler.removeCallbacks(longPressTickRunnable)

            if (isLongPressSeeking) {
                finishLongPressSeeking()
                return true
            } else if (longPressKeyCode == keyCode) {
                // 短按：单次快进/快退 10 秒
                longPressKeyCode = 0
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -10_000L else 10_000L
                playerManager.seekRelative(delta)
                showHudTemporarily()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startLongPressSeeking(keyCode: Int) {
        if (isLongPressSeeking) return
        isLongPressSeeking = true
        val snap = playerManager.snapshotState()
        wasPausedBeforeSeek = snap.isPaused
        if (!wasPausedBeforeSeek) {
            playerManager.togglePlayPause() // 拖动期间先暂停音频与画面渲染，避免破音与解码卡顿
            danmakuView.setPlaybackPaused(true)
        }
        virtualSeekPosMs = snap.positionMs
        longPressStartTime = SystemClock.uptimeMillis()
        lastThrottledSeekTime = SystemClock.uptimeMillis()

        executeLongPressTick(keyCode)
        scheduleNextLongPressTick()
    }

    private fun scheduleNextLongPressTick() {
        mainHandler.removeCallbacks(longPressTickRunnable)
        if (!isLongPressSeeking) return
        val heldMs = SystemClock.uptimeMillis() - longPressStartTime
        // 速率把控：
        // 前 1.2 秒：每 75ms 步进 1 秒（约 13.3 秒/秒），让用户眼睛清晰跟上每一秒刻度跳动
        // 超过 1.2 秒：平滑过渡到 50ms 步进 1 秒（约 20 秒/秒），避免长距离拖动等待过久
        val interval = if (heldMs > 1200L) 50L else 75L
        mainHandler.postDelayed(longPressTickRunnable, interval)
    }

    private fun executeLongPressTick(keyCode: Int) {
        val snap = playerManager.snapshotState()
        val dur = snap.durationMs.coerceAtLeast(1L)
        val isForward = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        val delta = if (isForward) 1_000L else -1_000L

        virtualSeekPosMs = (virtualSeekPosMs + delta).coerceIn(0L, dur)

        // 实时更新 HUD，毫秒级显示 1 秒步进刻度
        updateHudForSeeking(virtualSeekPosMs, dur, isForward)

        // 适度节流（每 250ms）刷新一次底层播放器画面，既能看到背景画面关键帧，又保护 2GB 内存不被连续 Range 请求打爆
        val now = SystemClock.uptimeMillis()
        if (now - lastThrottledSeekTime >= 250L) {
            lastThrottledSeekTime = now
            playerManager.seekTo(virtualSeekPosMs)
        }
    }

    private fun finishLongPressSeeking() {
        if (!isLongPressSeeking) return
        isLongPressSeeking = false
        longPressKeyCode = 0
        mainHandler.removeCallbacks(startLongPressRunnable)
        mainHandler.removeCallbacks(longPressTickRunnable)

        val target = virtualSeekPosMs
        if (target >= 0L) {
            playerManager.seekTo(target)
        }
        if (!wasPausedBeforeSeek) {
            playerManager.togglePlayPause() // 释放按键后恢复播放
            danmakuView.setPlaybackPaused(false)
        }
        showHudTemporarily()
        updateHudAndSaveHistory()
        virtualSeekPosMs = -1L
    }

    private fun updateHudForSeeking(posMs: Long, durMs: Long, isForward: Boolean) {
        hudOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)

        val posSec = posMs / 1000L
        val durSec = durMs / 1000L
        val pct = ((posMs * 100L) / durMs.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
        val icon = if (isForward) "⏩ 连续快进 [1秒刻度]" else "⏪ 连续快退 [1秒刻度]"

        hudProgressView.text = String.format(
            "%s  %02d:%02d / %02d:%02d (%d%%)  ·  松开按键立即起播",
            icon,
            posSec / 60, posSec % 60,
            durSec / 60, durSec % 60,
            pct
        )
    }

    private fun showPlayerSettingsDialog() {
        val settings = app.repository.getSettings()
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val curDecoder = DecoderMode.fromCode(settings.decoderMode)

        val options = arrayOf(
            "倍速播放：当前 ${settings.playbackSpeed}x（点击切换）",
            "解码方式：${curDecoder.label}（花屏/卡顿时切换）",
            "弹幕显示：${if (danmakuView.danmakuEnabled) "已开启" else "已关闭"}",
            "下一集无缝预加载：${if (settings.preloadNextEpisode) "已开启" else "已关闭"}"
        )

        AlertDialog.Builder(this)
            .setTitle("🎬 播放控制与解码调优")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val idx = speeds.indexOfFirst { it == settings.playbackSpeed }
                        val nextSpeed = speeds[(idx + 1) % speeds.size]
                        app.repository.updateSettings { it.copy(playbackSpeed = nextSpeed) }
                        playerManager.setPlaybackSpeed(nextSpeed)
                        Toast.makeText(this, "倍速已切换为 ${nextSpeed}x", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val modes = DecoderMode.values()
                        val nextMode = modes[(curDecoder.ordinal + 1) % modes.size]
                        app.repository.updateSettings { it.copy(decoderMode = nextMode.code) }
                        val pos = playerManager.snapshotState().positionMs
                        playerManager.initPlayer(nextMode, surfaceView)
                        currentDetail?.let { playEpisodeInternal(it, currentEpisodeIndex, pos) }
                        Toast.makeText(this, "已切换至：${nextMode.label}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val nextDanmaku = !danmakuView.danmakuEnabled
                        danmakuView.danmakuEnabled = nextDanmaku
                        danmakuView.invalidate()
                        app.repository.updateSettings { it.copy(danmakuEnabled = nextDanmaku) }
                    }
                    3 -> {
                        app.repository.updateSettings { it.copy(preloadNextEpisode = !it.preloadNextEpisode) }
                    }
                }
            }
            .setPositiveButton("返回播放", null)
            .show()
    }

    private fun updateHudAndSaveHistory() {
        val detail = currentDetail ?: return
        val snap = playerManager.snapshotState()
        danmakuView.setPlaybackPaused(snap.isPaused)
        val posSec = (snap.positionMs / 1000L).coerceAtLeast(0L)
        val durSec = (snap.durationMs / 1000L).coerceAtLeast(1L)
        val pct = ((snap.positionMs * 100L) / snap.durationMs.coerceAtLeast(1L)).toInt().coerceIn(0, 100)

        val mem = app.memoryGovernor.pollAndroidMemory(this)
        hudProgressView.text = String.format(
            "%02d:%02d / %02d:%02d (%d%%)  ·  %s  ·  倍速 %.2fx  ·  可用内存 %dMB [%s]",
            posSec / 60, posSec % 60,
            durSec / 60, durSec % 60,
            pct,
            DecoderMode.fromCode(snap.decoderMode).label,
            snap.playbackSpeed,
            mem.systemAvailRamMb,
            mem.watermark
        )

        if (snap.positionMs > 0L) {
            app.repository.recordHistory(
                WatchHistoryEntry(
                    dramaId = detail.id,
                    title = detail.title,
                    coverUrl = detail.coverUrl,
                    sourceId = detail.sourceId,
                    episodeIndex = currentEpisodeIndex,
                    episodeTitle = "第${currentEpisodeIndex}集",
                    totalEpisodes = detail.totalEpisodes,
                    positionMs = snap.positionMs,
                    durationMs = snap.durationMs,
                    progressPercent = pct
                )
            )
        }
    }

    private fun showHudTemporarily() {
        hudOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 4_500L)
    }
}
