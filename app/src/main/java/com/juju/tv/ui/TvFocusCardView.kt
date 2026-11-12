package com.juju.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 面向坚果 N1 Air 遥控器导航的零离屏 (`Zero-saveLayer`) 硬件加速焦点卡片容器
 *
 * 为什么这样设计？
 * - 低配投影仪/电视盒子的 Mali-G31 GPU 填充率有限，使用 `RenderEffect` 毛玻璃或 `CardView` 阴影会触发每帧离屏纹理合成；
 * - 本组件直接使用硬件层属性动画 (`scaleX`/`scaleY`) + 原生 Canvas 3dp 霓虹边框绘制，
 *   在不分配任何离屏缓冲的前提下提供极佳的大屏遥控器焦点反馈。
 */
class TvFocusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val borderRect = RectF()
    private val focusBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B35")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val normalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2694A3B8")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#151A24"))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val targetScale = if (gainFocus) 1.06f else 1.0f
        val targetZ = if (gainFocus) 16f else 0f
        animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationZ(targetZ)
            .setDuration(140L)
            .start()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = 2.5f
        borderRect.set(inset, inset, width - inset, height - inset)
        if (isFocused) {
            canvas.drawRoundRect(borderRect, 14f, 14f, focusBorderPaint)
        } else {
            canvas.drawRoundRect(borderRect, 14f, 14f, normalBorderPaint)
        }
    }
}
