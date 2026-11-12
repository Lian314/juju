package com.juju.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juju.tv.JuJuApplication
import com.juju.tv.model.DecoderMode
import com.juju.tv.model.DramaCard
import com.juju.tv.remote.NsdHelper
import java.util.concurrent.Executors

/**
 * 聚聚 (JuJu TV) 大屏主界面（专为坚果 N1 Air 第四代 1080P 遥控器交互定制）
 *
 * 包含：
 * 1. 顶部大屏导航栏：`热门短剧` | `精品漫剧` | `王牌真人剧` | `先锋 AI 剧` | `热播榜单` | `我的追剧/历史` | `手机扫码遥控` | `设置/2GB优化`
 * 2. 右上角实时系统内存与局域网遥控状态角标（每 5 秒无锁刷新一次 `availMem` 与 `MemoryWatermark`）
 * 3. 首屏 Hero 焦点横幅（一键继续观看或播放首推热剧）
 * 4. 6 列硬件加速海报网格（共享 `RecycledViewPool` + `RGB_565` 海报池）
 * 5. 手机扫码遥控实时联动：当手机端点击点播或发起首次 `/api/hello` 握手时自动响应
 */
class MainActivity : AppCompatActivity() {

    private val app: JuJuApplication by lazy { JuJuApplication.instance }
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusBadgeView: TextView
    private lateinit var heroTitleView: TextView
    private lateinit var heroMetaView: TextView
    private lateinit var heroPlayBtn: Button
    private lateinit var heroDetailBtn: Button
    private lateinit var sectionTitleView: TextView
    private lateinit var posterRecyclerView: RecyclerView
    private lateinit var posterAdapter: DramaGridAdapter

    private var currentHeroCard: DramaCard? = null
    private var qrDialog: AlertDialog? = null

    private val memoryPollRunnable = object : Runnable {
        override fun run() {
            updateMemoryAndLanBadge()
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupTvUiLayout()
        bindRemoteServerCallbacks()
        loadChannelFeed("hot", "🔥 热门短剧 · 全站实时热播")
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(memoryPollRunnable)
        refreshContinueWatchingHero()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(memoryPollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMobileRemoteQrDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupTvUiLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(40, 28, 40, 20)
        }

        // 1. 顶栏：品牌名 + 内存/局域网状态角标
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandTitle = TextView(this).apply {
            text = "聚聚 TV"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF6B35"))
        }
        val brandSub = TextView(this).apply {
            text = "  ·  坚果 N1 Air 2GB 专享极速版"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        statusBadgeView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topHeader.addView(brandTitle)
        topHeader.addView(brandSub)
        topHeader.addView(statusBadgeView)
        root.addView(topHeader)

        // 2. 频道导航条（遥控器左右方向键切换）
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 14)
        }
        val channels = listOf(
            "热门短剧" to { loadChannelFeed("hot", "🔥 热门短剧 · 全站实时热播") },
            "漫剧" to { loadChannelFeed("category:漫剧", "⚡ 高燃漫剧 · 动态漫与玄幻") },
            "真人剧" to { loadChannelFeed("category:真人剧", "🎬 王牌真人剧 · 都市逆袭与古装") },
            "AI 剧" to { loadChannelFeed("category:AI 剧", "🤖 先锋 AI 剧 · AIGC 奇幻视觉") },
            "热播榜单" to { loadRankingsFeed() },
            "追剧/历史" to { loadHistoryAndFollowsFeed() },
            "搜剧" to { showSearchDialog() },
            "📱 手机遥控" to { showMobileRemoteQrDialog() },
            "⚙️ 2GB设置" to { showSettingsDialog() }
        )

        channels.forEachIndexed { idx, (label, action) ->
            val btn = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E2534"))
                setPadding(22, 10, 22, 10)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 68)
                lp.rightMargin = 12
                layoutParams = lp
                setOnClickListener { action() }
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        if (hasFocus) Color.parseColor("#FF6B35") else Color.parseColor("#1E2534")
                    )
                }
            }
            navRow.addView(btn)
            if (idx == 0) {
                btn.post { btn.requestFocus() }
            }
        }
        root.addView(navRow)

        // 3. 首屏 Hero 横幅（继续观看 / 首推剧集）
        val heroBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#151A24"))
            setPadding(28, 18, 28, 18)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 16
            layoutParams = lp
        }
        val heroTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroTitleView = TextView(this).apply {
            text = "正在加载精选剧集..."
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        heroMetaView = TextView(this).apply {
            text = "按 OK 键立即全屏硬件直出播放 · 手机扫码可免打字搜剧与配置自定义源"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6, 0, 0)
        }
        heroTextCol.addView(heroTitleView)
        heroTextCol.addView(heroMetaView)

        heroPlayBtn = Button(this).apply {
            text = "▶ 立即播放"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF6B35"))
            setPadding(28, 12, 28, 12)
            setOnClickListener {
                currentHeroCard?.let { card ->
                    val hist = app.repository.getHistoryForDrama(card.id)
                    openPlayer(card.id, hist?.episodeIndex ?: 1, hist?.positionMs ?: 0L)
                }
            }
        }
        heroDetailBtn = Button(this).apply {
            text = "📋 选集/详情"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 12, 24, 12)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = 12
            layoutParams = lp
            setOnClickListener {
                currentHeroCard?.let { openDetail(it.id) }
            }
        }

        heroBanner.addView(heroTextCol)
        heroBanner.addView(heroPlayBtn)
        heroBanner.addView(heroDetailBtn)
        root.addView(heroBanner)

        // 4. 当前频道标题
        sectionTitleView = TextView(this).apply {
            text = "🔥 热门短剧"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, 10)
        }
        root.addView(sectionTitleView)

        // 5. 6 列硬件加速海报网格
        posterAdapter = DramaGridAdapter { card ->
            openDetail(card.id)
        }
        posterRecyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            adapter = posterAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(4) // 严格控制离屏 ViewHolder 数量以节省 2GB 内存
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(posterRecyclerView)

        // 6. 底部遥控器操作提示栏
        val footerView = TextView(this).apply {
            text = getString(com.juju.tv.R.string.tv_hint_footer)
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 8, 0, 0)
        }
        root.addView(footerView)

        setContentView(root)
    }

    private fun bindRemoteServerCallbacks() {
        app.lanRemoteServer.onRemotePlayCommand = { dramaId, epIndex ->
            mainHandler.post {
                openPlayer(dramaId, epIndex, 0L)
            }
        }
        app.lanRemoteServer.onClientConnectedCallback = { clientIp ->
            mainHandler.post {
                qrDialog?.let { dlg ->
                    if (dlg.isShowing) {
                        dlg.dismiss()
                        Toast.makeText(this, "📱 手机 ($clientIp) 已连接聚聚遥控！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateMemoryAndLanBadge() {
        val snap = app.memoryGovernor.pollAndroidMemory(this)
        val lanIp = NsdHelper.detectLanIpv4()
        val port = app.lanRemoteServer.listeningPort
        statusBadgeView.text = "可用内存: ${snap.systemAvailRamMb}MB [${snap.watermark}]  ·  手机扫码遥控: http://$lanIp:$port"
    }

    private fun refreshContinueWatchingHero() {
        val lastHist = app.repository.getHistories(1).firstOrNull()
        if (lastHist != null) {
            currentHeroCard = DramaCard(
                id = lastHist.dramaId,
                title = lastHist.title,
                coverUrl = lastHist.coverUrl,
                sourceId = lastHist.sourceId,
                totalEpisodes = lastHist.totalEpisodes
            )
            heroTitleView.text = "继续观看：《${lastHist.title}》 第${lastHist.episodeIndex}集 (${lastHist.progressPercent}%)"
            heroMetaView.text = "上次看到第 ${lastHist.episodeIndex}/${lastHist.totalEpisodes} 集  ·  按「立即播放」无缝续播"
            heroPlayBtn.text = "▶ 继续播放第${lastHist.episodeIndex}集"
        }
    }

    private fun loadChannelFeed(railKey: String, titleText: String) {
        sectionTitleView.text = titleText
        bgExecutor.execute {
            val rail = app.providerRegistry.fetchSingleRail(railKey)
            mainHandler.post {
                posterAdapter.submitList(rail.items)
                if (currentHeroCard == null && rail.items.isNotEmpty()) {
                    val first = rail.items.first()
                    currentHeroCard = first
                    heroTitleView.text = "今日首推：《${first.title}》 (${first.latestEpisodeText})"
                    heroMetaView.text = "${first.category} · 评分 ${first.score} · ${first.intro.ifBlank { "1080P 硬件直出高清短剧" }}"
                    heroPlayBtn.text = "▶ 立即播放第1集"
                }
            }
        }
    }

    private fun loadRankingsFeed() {
        sectionTitleView.text = "🏆 全网短剧热播总榜"
        bgExecutor.execute {
            val items = app.providerRegistry.fetchRankings("热播榜", 1)
            mainHandler.post { posterAdapter.submitList(items) }
        }
    }

    private fun loadHistoryAndFollowsFeed() {
        sectionTitleView.text = "❤️ 我的追剧收藏与观看记录"
        val follows = app.repository.getFollows().map { f ->
            DramaCard(
                id = f.dramaId,
                title = f.title,
                coverUrl = f.coverUrl,
                sourceId = f.sourceId,
                category = "追剧",
                totalEpisodes = f.totalEpisodes,
                latestEpisodeText = "看到第${f.lastEpisodeIndex}集"
            )
        }
        val histories = app.repository.getHistories(24).map { h ->
            DramaCard(
                id = h.dramaId,
                title = h.title,
                coverUrl = h.coverUrl,
                sourceId = h.sourceId,
                category = "历史",
                totalEpisodes = h.totalEpisodes,
                latestEpisodeText = "第${h.episodeIndex}集 (${h.progressPercent}%)"
            )
        }
        val combined = (follows + histories).distinctBy { it.id }
        posterAdapter.submitList(combined)
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "输入剧名、题材（如：逆袭 / 剑仙 / 甜宠 / 赛博）"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("🔍 全站聚合搜剧（推荐使用手机扫码打字更快）")
            .setView(input)
            .setPositiveButton("立即搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) {
                    sectionTitleView.text = "🔍 搜索结果：「$kw」"
                    bgExecutor.execute {
                        val results = app.providerRegistry.searchAll(kw, 1)
                        mainHandler.post { posterAdapter.submitList(results) }
                    }
                }
            }
            .setNeutralButton("打开手机扫码遥控") { _, _ ->
                showMobileRemoteQrDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMobileRemoteQrDialog() {
        val lanIp = NsdHelper.detectLanIpv4()
        val port = app.lanRemoteServer.listeningPort
        val remoteUrl = "http://$lanIp:$port"
        val webdavToken = app.repository.issueWebDavSessionToken()
        val webdavUrl = "$remoteUrl/webdav?t=$webdavToken"

        val msg = """
            在同一 Wi-Fi 下，用手机浏览器访问以下地址即可免安装开启：
            
            👉 手机搜剧与遥控主页：
            $remoteUrl
            
            👉 WebDAV 云同步配置页：
            $webdavUrl
            
            💡 亮点功能：
            • 手机打字秒搜全网短剧、多维找剧筛选、一键投屏点播
            • 在「自救/设置」页可随时添加自定义 JSON / CMS / M3U 站源，永不失联！
            • 手机连上后本窗口将自动关闭
        """.trimIndent()

        qrDialog = AlertDialog.Builder(this)
            .setTitle("📱 聚聚 · 手机扫码遥控与去中心化源管理")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showSettingsDialog() {
        val settings = app.repository.getSettings()
        val snap = app.memoryGovernor.pollAndroidMemory(this)
        val curDecoder = DecoderMode.fromCode(settings.decoderMode)

        val items = arrayOf(
            "解码方式：${curDecoder.label}（点击切换）",
            "下一集无缝预加载：${if (settings.preloadNextEpisode) "已开启 (仅占1集缓存)" else "已关闭"}",
            "大屏弹幕：${if (settings.danmakuEnabled) "已开启 (低内存自动挂起)" else "已关闭"}",
            "立即清理预加载缓存并释放内存（当前可用 ${snap.systemAvailRamMb}MB）",
            "重置红果虚拟设备身份（解决偶发取流受限）"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ 坚果 N1 Air (2GB RAM) 专属性能与播放设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val modes = DecoderMode.values()
                        val nextMode = modes[(curDecoder.ordinal + 1) % modes.size]
                        app.repository.updateSettings { it.copy(decoderMode = nextMode.code) }
                        Toast.makeText(this, "已切换为：${nextMode.label}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val updated = app.repository.updateSettings { it.copy(preloadNextEpisode = !it.preloadNextEpisode) }
                        Toast.makeText(this, "下一集预加载：${if (updated.preloadNextEpisode) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val updated = app.repository.updateSettings { it.copy(danmakuEnabled = !it.danmakuEnabled) }
                        Toast.makeText(this, "弹幕已${if (updated.danmakuEnabled) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        app.preloadManager.clearAll()
                        app.imageLoader.adjustCacheForWatermark(com.juju.tv.model.MemoryWatermark.MODERATE)
                        updateMemoryAndLanBadge()
                        Toast.makeText(this, "✅ 已清理预加载磁盘缓存并收缩海报池", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        val id = com.juju.tv.provider.HongguoProtocol.resetIdentity()
                        Toast.makeText(this, "✅ 已生成新设备指纹：${id.deviceId}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openDetail(dramaId: String) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("drama_id", dramaId)
        }
        startActivity(intent)
    }

    private fun openPlayer(dramaId: String, episodeIndex: Int, startPositionMs: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("drama_id", dramaId)
            putExtra("episode_index", episodeIndex)
            putExtra("start_position_ms", startPositionMs)
        }
        startActivity(intent)
    }

    /**
     * 6 列海报墙 Adapter（基于 [TvFocusCardView] 与 `RGB_565` [ImageLoader]）
     */
    private inner class DramaGridAdapter(
        private val onClick: (DramaCard) -> Unit
    ) : RecyclerView.Adapter<DramaGridAdapter.Holder>() {

        private val items = mutableListOf<DramaCard>()

        fun submitList(newList: List<DramaCard>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        inner class Holder(
            val cardRoot: TvFocusCardView,
            val posterView: ImageView,
            val titleView: TextView,
            val subView: TextView
        ) : RecyclerView.ViewHolder(cardRoot)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = TvFocusCardView(parent.context).apply {
                val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 340)
                lp.setMargins(10, 10, 10, 10)
                layoutParams = lp
            }
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val title = TextView(parent.context).apply {
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(10, 6, 10, 2)
            }
            val sub = TextView(parent.context).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(10, 0, 10, 8)
            }
            box.addView(img)
            box.addView(title)
            box.addView(sub)
            card.addView(box)
            return Holder(card, img, title, sub)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.titleView.text = item.title
            holder.subView.text = "${item.category} · ${item.latestEpisodeText}"
            app.imageLoader.loadPoster(holder.posterView, item.coverUrl, item.title, item.category)
            holder.cardRoot.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
