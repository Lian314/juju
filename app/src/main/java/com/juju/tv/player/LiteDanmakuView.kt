package com.juju.tv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.model.DanmakuItem

/**
 * 面向 2GB RAM 投影仪的零分配硬件加速弹幕视图 (`LiteDanmakuView`)
 *
 * 工程优化亮点：
 * 1. 预分配固定长度 `MAX_ACTIVE_SLOTS = 24` 的 [ActiveSlot] 对象池，`onDraw(Canvas)` 全程 **0 临时对象分配**，杜绝 GC 掉帧；
 * 2. 与 [MemoryGovernor] 深度联动：一旦系统进入 `LOW` 或 `CRITICAL` 内存水位，自动挂起弹幕绘制，保主视频流满帧；
 * 3. 支持显示区域比例 (`areaRatio`)、字号缩放 (`fontScale`)、不透明度 (`opacity`) 与屏蔽词过滤。
 */
class LiteDanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MAX_ACTIVE_SLOTS = 24
        const val MAX_TRACK_ROWS = 8
    }

    private class ActiveSlot {
        var active: Boolean = false
        var text: String = ""
        var x: Float = 0f
        var y: Float = 0f
        var textWidth: Float = 0f
        var speedPxPerSec: Float = 180f
        var colorRgb: Int = 0xFFFFFF
        var row: Int = 0
    }

    private val slots = Array(MAX_ACTIVE_SLOTS) { ActiveSlot() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        isFakeBoldText = true
    }

    private val pendingQueue = ArrayDeque<DanmakuItem>()
    private var lastFrameNano: Long = 0L

    var memoryGovernor: MemoryGovernor? = null
    var danmakuEnabled: Boolean = true
    var areaRatio: Float = 0.5f
    var fontScale: Float = 1.0f
    var danmakuAlpha: Int = 220
    var danmakuSpeed: Float = 1.0f
    var blockWords: List<String> = emptyList()
    private var isPlaybackPaused: Boolean = false

    fun setPlaybackPaused(paused: Boolean) {
        if (this.isPlaybackPaused != paused) {
            this.isPlaybackPaused = paused
            if (!paused) {
                lastFrameNano = 0L
                postInvalidateOnAnimation()
            } else {
                invalidate()
            }
        }
    }

    fun setDanmakuList(items: List<DanmakuItem>) {
        pendingQueue.clear()
        for (slot in slots) slot.active = false
        for (item in items) {
            if (blockWords.none { bw -> bw.isNotBlank() && item.text.contains(bw, ignoreCase = true) }) {
                pendingQueue.addLast(item)
            }
        }
        invalidate()
    }

    fun enqueueRealtimeDanmaku(text: String, colorRgb: Int = 0x38BDF8) {
        if (text.isBlank()) return
        pendingQueue.addFirst(DanmakuItem("rt_${System.nanoTime()}", 0L, text, colorRgb))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuEnabled) return
        val watermark = memoryGovernor?.currentWatermark()
        if (watermark != null && !watermark.allowDanmaku) {
            // 低内存水位下自动停止绘制弹幕，零开销退出
            return
        }

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val nowNano = System.nanoTime()
        val dtSec = if (isPlaybackPaused) {
            0f
        } else if (lastFrameNano == 0L) {
            0.016f
        } else {
            ((nowNano - lastFrameNano) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNano = nowNano

        val scaledTextSize = 28f * fontScale.coerceIn(0.75f, 1.5f)
        textPaint.textSize = scaledTextSize
        strokePaint.textSize = scaledTextSize
        strokePaint.alpha = danmakuAlpha

        val maxRows = (MAX_TRACK_ROWS * areaRatio.coerceIn(0.25f, 1.0f)).toInt().coerceAtLeast(2)
        val rowHeight = scaledTextSize * 1.45f

        // 尝试从待发队列发射新弹幕到空闲槽位
        if (!isPlaybackPaused && pendingQueue.isNotEmpty()) {
            val freeSlot = slots.firstOrNull { !it.active }
            if (freeSlot != null) {
                val candidateRow = findAvailableRow(maxRows, viewW)
                if (candidateRow >= 0) {
                    val item = pendingQueue.removeFirst()
                    // 循环追加到队尾保持大屏弹幕氛围
                    pendingQueue.addLast(item)
                    val w = textPaint.measureText(item.text)
                    val speedMul = danmakuSpeed.coerceIn(0.5f, 2.5f)
                    freeSlot.active = true
                    freeSlot.text = item.text
                    freeSlot.textWidth = w
                    freeSlot.x = viewW + 24f
                    freeSlot.row = candidateRow
                    freeSlot.y = (candidateRow + 1) * rowHeight + 16f
                    freeSlot.speedPxPerSec = (170f + (item.text.length * 4f).coerceAtMost(90f)) * speedMul
                    freeSlot.colorRgb = item.colorRgb
                }
            }
        }

        var hasActive = false
        for (slot in slots) {
            if (!slot.active) continue
            if (!isPlaybackPaused) {
                slot.x -= slot.speedPxPerSec * dtSec
            }
            if (slot.x + slot.textWidth < -20f) {
                slot.active = false
                continue
            }
            hasActive = true
            textPaint.color = (danmakuAlpha shl 24) or (slot.colorRgb and 0xFFFFFF)
            canvas.drawText(slot.text, slot.x, slot.y, strokePaint)
            canvas.drawText(slot.text, slot.x, slot.y, textPaint)
        }

        if (hasActive && !isPlaybackPaused) {
            postInvalidateOnAnimation()
        }
    }

    private fun findAvailableRow(maxRows: Int, viewWidth: Float): Int {
        for (r in 0 until maxRows) {
            var rowBusy = false
            for (slot in slots) {
                if (slot.active && slot.row == r && (slot.x + slot.textWidth > viewWidth - 80f)) {
                    rowBusy = true
                    break
                }
            }
            if (!rowBusy) return r
        }
        return -1
    }
}
