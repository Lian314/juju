package com.juju.tv.memory

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.juju.tv.model.MemoryStatusSnapshot
import com.juju.tv.model.MemoryWatermark
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 面向 2GB RAM（坚果 N1 Air 第四代 · Android 13）的四级内存水位自适应守护器
 *
 * 核心职责：
 * 1. 监听 Android 系统 ComponentCallbacks2.onTrimMemory(level) 信号；
 * 2. 结合系统剩余物理内存 (availMem) 与 JVM 堆占用率，计算实时 [MemoryWatermark]；
 * 3. 当水位进入 MODERATE / LOW / CRITICAL 时，自动通知：
 *    - ImageLoader 动态缩减 RGB_565 海报 LruCache 容量（12MB -> 6MB -> 2.5MB -> 512KB）；
 *    - LiteDanmakuView 在 LOW / CRITICAL 水位自动挂起弹幕绘制；
 *    - EpisodePreloadManager 清理孤儿预加载缓存文件；
 *    - ByteBufferPool 收缩空闲缓冲块。
 */
class MemoryGovernor(
    private val byteBufferPool: ByteBufferPool = ByteBufferPool.shared
) : ComponentCallbacks2 {

    fun interface WatermarkListener {
        fun onWatermarkChanged(oldLevel: MemoryWatermark, newLevel: MemoryWatermark, reason: String)
    }

    companion object {
        // 针对 2GB RAM (2048 MB) 投影仪的物理内存水位阈值（单位：MB）
        const val THRESHOLD_MODERATE_AVAIL_MB = 420L
        const val THRESHOLD_LOW_AVAIL_MB = 300L
        const val THRESHOLD_CRITICAL_AVAIL_MB = 220L
    }

    private val currentWatermarkRef = AtomicReference(MemoryWatermark.NORMAL)
    private val listeners = CopyOnWriteArrayList<WatermarkListener>()

    @Volatile
    var posterCacheUsedBytesProvider: () -> Int = { 0 }

    @Volatile
    var preloadStatsProvider: () -> Pair<Int, Long> = { 0 to 0L }

    @Volatile
    var onEvictCachesCallback: ((MemoryWatermark) -> Unit)? = null

    fun currentWatermark(): MemoryWatermark = currentWatermarkRef.get()

    fun addListener(listener: WatermarkListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WatermarkListener) {
        listeners.remove(listener)
    }

    /**
     * 根据系统可用内存（MB）、系统 lowMemory 标志、JVM 堆使用率与 Android TrimLevel 纯函数评估目标水位
     * （设计为纯方法以便于确定性单元测试）
     */
    fun evaluateWatermark(
        availRamMb: Long,
        isSystemLowMemory: Boolean,
        jvmUsedRatio: Float,
        lastTrimLevel: Int = 0
    ): MemoryWatermark {
        // 1. 优先响应危急条件
        if (isSystemLowMemory ||
            availRamMb < THRESHOLD_CRITICAL_AVAIL_MB ||
            jvmUsedRatio >= 0.88f ||
            lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            return MemoryWatermark.CRITICAL
        }

        // 2. 紧张水位
        if (availRamMb < THRESHOLD_LOW_AVAIL_MB ||
            jvmUsedRatio >= 0.76f ||
            lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            return MemoryWatermark.LOW
        }

        // 3. 温和水位
        if (availRamMb < THRESHOLD_MODERATE_AVAIL_MB ||
            jvmUsedRatio >= 0.62f ||
            lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            return MemoryWatermark.MODERATE
        }

        return MemoryWatermark.NORMAL
    }

    /**
     * 手动或定时传入当前内存指标触发水位更新
     */
    fun updateFromMetrics(
        availRamMb: Long,
        isSystemLowMemory: Boolean,
        jvmUsedHeapMb: Long,
        jvmMaxHeapMb: Long,
        trimLevel: Int = 0,
        reason: String = "metrics_poll"
    ): MemoryWatermark {
        val ratio = if (jvmMaxHeapMb > 0) jvmUsedHeapMb.toFloat() / jvmMaxHeapMb.toFloat() else 0f
        val target = evaluateWatermark(availRamMb, isSystemLowMemory, ratio, trimLevel)
        applyWatermark(target, reason)
        return target
    }

    /**
     * 在真实 Android Context 下读取 ActivityManager.MemoryInfo 并刷新水位
     */
    fun pollAndroidMemory(context: Context?): MemoryStatusSnapshot {
        val runtime = Runtime.getRuntime()
        val usedHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).coerceAtLeast(1L)
        val maxHeapMb = (runtime.maxMemory() / (1024 * 1024)).coerceAtLeast(64L)

        var totalRamMb = 2048L
        var availRamMb = 620L
        var lowMem = false

        if (context != null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val info = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(info)
                    if (info.totalMem > 0) {
                        totalRamMb = info.totalMem / (1024 * 1024)
                        availRamMb = info.availMem / (1024 * 1024)
                        lowMem = info.lowMemory
                    }
                }
            } catch (_: Throwable) {
                // Unit test environment fallback
            }
        }

        val watermark = updateFromMetrics(
            availRamMb = availRamMb,
            isSystemLowMemory = lowMem,
            jvmUsedHeapMb = usedHeapMb,
            jvmMaxHeapMb = maxHeapMb,
            reason = "pollAndroidMemory"
        )
        val (preloadFiles, preloadBytes) = preloadStatsProvider()
        val modelName = try {
            if (!Build.MODEL.isNullOrBlank()) "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            else "JMGO N1 Air (4th Gen)"
        } catch (_: Throwable) {
            "JMGO N1 Air (4th Gen)"
        }
        val osRelease = try {
            if (!Build.VERSION.RELEASE.isNullOrBlank()) "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            else "Android 13 (API 33)"
        } catch (_: Throwable) {
            "Android 13 (API 33)"
        }

        return MemoryStatusSnapshot(
            deviceModel = modelName,
            androidRelease = osRelease,
            systemTotalRamMb = totalRamMb,
            systemAvailRamMb = availRamMb,
            systemLowMemory = lowMem,
            jvmUsedHeapMb = usedHeapMb,
            jvmMaxHeapMb = maxHeapMb,
            watermark = watermark.name,
            watermarkLabel = watermark.label,
            posterCacheLimitKb = watermark.maxPosterCacheBytes / 1024,
            posterCacheUsedKb = posterCacheUsedBytesProvider() / 1024,
            preloadCacheFiles = preloadFiles,
            preloadCacheBytes = preloadBytes,
            byteBufferPoolAvailable = byteBufferPool.availableCount()
        )
    }

    private fun applyWatermark(newLevel: MemoryWatermark, reason: String) {
        val oldLevel = currentWatermarkRef.getAndSet(newLevel)
        if (newLevel == MemoryWatermark.CRITICAL) {
            byteBufferPool.trimTo(1)
        } else if (newLevel == MemoryWatermark.LOW) {
            byteBufferPool.trimTo(2)
        }
        if (oldLevel != newLevel || newLevel == MemoryWatermark.CRITICAL) {
            onEvictCachesCallback?.invoke(newLevel)
            for (listener in listeners) {
                listener.onWatermarkChanged(oldLevel, newLevel, reason)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        val runtime = Runtime.getRuntime()
        val usedHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).coerceAtLeast(1L)
        val maxHeapMb = (runtime.maxMemory() / (1024 * 1024)).coerceAtLeast(64L)
        // 根据 trimLevel 映射保守可用内存估计
        val syntheticAvailMb = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 180L
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 260L
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 360L
            else -> 500L
        }
        updateFromMetrics(
            availRamMb = syntheticAvailMb,
            isSystemLowMemory = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            jvmUsedHeapMb = usedHeapMb,
            jvmMaxHeapMb = maxHeapMb,
            trimLevel = level,
            reason = "onTrimMemory($level)"
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        applyWatermark(MemoryWatermark.CRITICAL, "onLowMemory")
    }
}
