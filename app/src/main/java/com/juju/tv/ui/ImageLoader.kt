package com.juju.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.model.MemoryWatermark
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 面向 2GB RAM 投影仪的 `RGB_565` 降维采样海报加载器
 *
 * 内存优化核心：
 * 1. 强制采用 `Bitmap.Config.RGB_565`（每像素 2 字节，相比默认 ARGB_8888 节省 50% 内存）；
 * 2. 自动按 TV 卡片实际物理尺寸 (`240 × 336 px`) 计算 `inSampleSize` 降维解码，单张海报仅占 ~160KB；
 * 3. 与 [MemoryGovernor] 实时联动：当系统进入 MODERATE / LOW / CRITICAL 水位时动态调用 `resize()` 或 `evictAll()`；
 * 4. 固定 3 线程轻量解码池，防止滑动海报墙时并发创建大量线程。
 */
class ImageLoader(
    memoryGovernor: MemoryGovernor,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val TARGET_POSTER_WIDTH = 240
        const val TARGET_POSTER_HEIGHT = 336

        /**
         * 计算最佳 2 的幂次降采样倍率 `inSampleSize`
         */
        fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)

    private val memoryCache = object : LruCache<String, Bitmap>(MemoryWatermark.NORMAL.maxPosterCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount
        }
    }

    init {
        adjustCacheForWatermark(memoryGovernor.currentWatermark())
    }

    fun currentCacheSizeBytes(): Int = synchronized(memoryCache) { memoryCache.size() }

    fun adjustCacheForWatermark(watermark: MemoryWatermark) {
        synchronized(memoryCache) {
            if (watermark == MemoryWatermark.CRITICAL) {
                memoryCache.evictAll()
            } else {
                memoryCache.resize(watermark.maxPosterCacheBytes.coerceAtLeast(512 * 1024))
            }
        }
    }

    fun loadPoster(imageView: ImageView, url: String, titleFallback: String, categoryBadge: String = "") {
        val cacheKey = url.ifBlank { "fallback:$categoryBadge:${titleFallback.take(14)}" }
        imageView.tag = cacheKey

        val cached = synchronized(memoryCache) { memoryCache.get(cacheKey) }
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        // 复用或生成深色渐变占位图，避免在主线程重复分配 Bitmap
        val placeholderKey = "fallback:$categoryBadge:${titleFallback.take(14)}"
        val placeholder = synchronized(memoryCache) {
            memoryCache.get(placeholderKey)?.takeIf { !it.isRecycled }
        } ?: renderStylizedPosterBitmap(titleFallback, categoryBadge).also { bmp ->
            synchronized(memoryCache) { memoryCache.put(placeholderKey, bmp) }
        }
        imageView.setImageBitmap(placeholder)

        if (url.isBlank() || url.startsWith("local://")) {
            return
        }

        executor.execute {
            val bmp = fetchAndDecodeRgb565(url) ?: placeholder
            synchronized(memoryCache) {
                memoryCache.put(cacheKey, bmp)
            }
            mainHandler.post {
                if (imageView.tag == cacheKey) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun fetchAndDecodeRgb565(url: String): Bitmap? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "JuJuTV/1.0 (Android 13; JMGO N1 Air)")
                .build()
            val bytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body()?.bytes()
            } ?: return null
            if (bytes.isEmpty()) return null

            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    boundsOpts.outWidth,
                    boundsOpts.outHeight,
                    TARGET_POSTER_WIDTH,
                    TARGET_POSTER_HEIGHT
                )
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 生成高质感 RGB_565 渐变电影海报（仅占 150KB，保证弱网/内网环境下大屏依然美观）
     */
    private fun renderStylizedPosterBitmap(title: String, category: String): Bitmap {
        val w = TARGET_POSTER_WIDTH
        val h = TARGET_POSTER_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)

        val hash = kotlin.math.abs(title.hashCode())
        val palettes = arrayOf(
            intArrayOf(0xFF1E293B.toInt(), 0xFF0F172A.toInt(), 0xFFFF6B35.toInt()),
            intArrayOf(0xFF31102F.toInt(), 0xFF0F172A.toInt(), 0xFFF472B6.toInt()),
            intArrayOf(0xFF0F2942.toInt(), 0xFF09131C.toInt(), 0xFF38BDF8.toInt()),
            intArrayOf(0xFF1F2416.toInt(), 0xFF0C120C.toInt(), 0xFF4ADE80.toInt())
        )
        val palette = palettes[hash % palettes.size]

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), palette[0], palette[1], Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 装饰光环
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette[2]
            alpha = 45
        }
        canvas.drawCircle(w * 0.78f, h * 0.24f, 68f, glowPaint)

        // 分类角标
        if (category.isNotBlank()) {
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette[2]
            }
            canvas.drawRoundRect(16f, 16f, 96f, 44f, 8f, 8f, badgePaint)
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f
                isFakeBoldText = true
            }
            canvas.drawText(category.take(4), 26f, 36f, badgeTextPaint)
        }

        // 绘制剧名（自动分行）
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            isFakeBoldText = true
        }
        val line1 = title.take(7)
        val line2 = title.drop(7).take(7)
        canvas.drawText(line1, 18f, h - 72f, titlePaint)
        if (line2.isNotEmpty()) {
            canvas.drawText(line2, 18f, h - 42f, titlePaint)
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 14f
        }
        canvas.drawText("聚聚 · 1080P 直出", 18f, h - 16f, subPaint)
        return bmp
    }
}
