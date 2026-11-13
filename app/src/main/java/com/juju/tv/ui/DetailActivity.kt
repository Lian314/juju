package com.juju.tv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juju.tv.JuJuApplication
import com.juju.tv.model.DramaDetail
import com.juju.tv.model.EpisodeItem
import com.juju.tv.model.FollowEntry
import java.util.concurrent.Executors

/**
 * 剧集详情与大屏选集网格页（支持断点续播、加入/取消追剧、查全季、遥控器网格选集）
 */
class DetailActivity : AppCompatActivity() {

    private val app: JuJuApplication by lazy { JuJuApplication.instance }
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var introView: TextView
    private lateinit var playResumeBtn: Button
    private lateinit var followBtn: Button
    private lateinit var episodeRecyclerView: RecyclerView

    private var currentDetail: DramaDetail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLayout()
        val dramaId = intent.getStringExtra("drama_id") ?: ""
        loadDetail(dramaId)
    }

    override fun onResume() {
        super.onResume()
        currentDetail?.let { bindResumeState(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
    }

    private fun setupLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(44, 32, 44, 24)
        }

        // 上半部分：左侧海报 + 右侧元数据与操作按钮
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 22
            layoutParams = lp
        }

        coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val lp = LinearLayout.LayoutParams(210, 294)
            lp.rightMargin = 28
            layoutParams = lp
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleView = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        metaView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 8)
        }

        introView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 4
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18, 0, 0)
        }

        playResumeBtn = Button(this).apply {
            text = "▶ 立即播放第1集"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF6B35"))
            setPadding(30, 12, 30, 12)
        }

        followBtn = Button(this).apply {
            text = "❤️ 加入追剧"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E2534"))
            setPadding(26, 12, 26, 12)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = 16
            layoutParams = lp
        }

        actionRow.addView(playResumeBtn)
        actionRow.addView(followBtn)

        infoCol.addView(titleView)
        infoCol.addView(metaView)
        infoCol.addView(introView)
        infoCol.addView(actionRow)

        topHeader.addView(coverView)
        topHeader.addView(infoCol)
        root.addView(topHeader)

        // 下半部分：8 列选集网格
        val epHeader = TextView(this).apply {
            text = "📺 剧集选集（遥控器方向键选集 · OK 键立即起播）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        root.addView(epHeader)

        episodeRecyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@DetailActivity, 8)
            setHasFixedSize(true)
            setItemViewCacheSize(4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(episodeRecyclerView)

        setContentView(root)
    }

    private fun loadDetail(dramaId: String) {
        if (dramaId.isBlank()) {
            titleView.text = "未指定剧集"
            introView.text = "请返回主界面重新选择剧集。"
            return
        }
        titleView.text = "正在加载剧集详情..."
        bgExecutor.execute {
            val detail = app.providerRegistry.fetchDetail(dramaId)
            mainHandler.post {
                if (detail != null) {
                    currentDetail = detail
                    bindDetail(detail)
                } else {
                    titleView.text = "加载剧集详情失败"
                    metaView.text = "请检查网络连接或返回主界面切换站源"
                    introView.text = "未获取到该剧详情；如果您在使用自建站源，请确保数据源链接有效且包含该剧目。"
                    Toast.makeText(this, "加载剧集详情失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindDetail(detail: DramaDetail) {
        val hasPlayable = detail.episodes.any { it.streamUrl.isNotBlank() }
        titleView.text = detail.title
        metaView.text = "${detail.sourceName}  ·  ${detail.category}  ·  评分 ${detail.score}  ·  共 ${detail.totalEpisodes} 集  ·  " +
            (if (hasPlayable) "✅ 直链可播" else "ℹ️ 公开目录（播放需匹配自建源）") +
            "  ·  ${detail.tags.joinToString(" / ")}"
        introView.text = detail.intro
        app.imageLoader.loadPoster(coverView, detail.coverUrl, detail.title, detail.category)

        bindResumeState(detail)
        updateFollowBtn(detail)

        followBtn.setOnClickListener {
            if (app.repository.isFollowed(detail.id)) {
                app.repository.removeFollow(detail.id)
                Toast.makeText(this, "已取消追剧", Toast.LENGTH_SHORT).show()
            } else {
                app.repository.upsertFollow(
                    FollowEntry(
                        dramaId = detail.id,
                        title = detail.title,
                        coverUrl = detail.coverUrl,
                        sourceId = detail.sourceId,
                        totalEpisodes = detail.totalEpisodes
                    )
                )
                Toast.makeText(this, "❤️ 已加入追剧收藏", Toast.LENGTH_SHORT).show()
            }
            updateFollowBtn(detail)
        }

        episodeRecyclerView.adapter = EpisodeAdapter(detail.episodes) { ep ->
            startPlayback(detail.id, ep.index, 0L)
        }
        playResumeBtn.requestFocus()
    }

    private fun bindResumeState(detail: DramaDetail) {
        val hist = app.repository.getHistoryForDrama(detail.id)
        if (hist != null) {
            playResumeBtn.text = "▶ 续播第 ${hist.episodeIndex} 集 (${hist.progressPercent}%)"
            playResumeBtn.setOnClickListener {
                startPlayback(detail.id, hist.episodeIndex, hist.positionMs)
            }
        } else {
            playResumeBtn.text = "▶ 立即播放第 1 集"
            playResumeBtn.setOnClickListener {
                startPlayback(detail.id, 1, 0L)
            }
        }
    }

    private fun updateFollowBtn(detail: DramaDetail) {
        val followed = app.repository.isFollowed(detail.id)
        followBtn.text = if (followed) "✓ 已在追剧列表" else "❤️ 加入追剧"
        followBtn.setBackgroundColor(
            if (followed) Color.parseColor("#22C55E") else Color.parseColor("#1E2534")
        )
    }

    private fun startPlayback(dramaId: String, epIndex: Int, startPosMs: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("drama_id", dramaId)
            putExtra("episode_index", epIndex)
            putExtra("start_position_ms", startPosMs)
        }
        startActivity(intent)
    }

    private inner class EpisodeAdapter(
        private val episodes: List<EpisodeItem>,
        private val onSelect: (EpisodeItem) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.EpHolder>() {

        inner class EpHolder(val btn: Button) : RecyclerView.ViewHolder(btn)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpHolder {
            val btn = Button(parent.context).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#151A24"))
                gravity = Gravity.CENTER
                val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 66)
                lp.setMargins(6, 6, 6, 6)
                layoutParams = lp
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        if (hasFocus) Color.parseColor("#FF6B35") else Color.parseColor("#151A24")
                    )
                }
            }
            return EpHolder(btn)
        }

        override fun onBindViewHolder(holder: EpHolder, position: Int) {
            val ep = episodes[position]
            holder.btn.text = "第 ${ep.index} 集"
            holder.btn.setOnClickListener { onSelect(ep) }
        }

        override fun getItemCount(): Int = episodes.size
    }
}
