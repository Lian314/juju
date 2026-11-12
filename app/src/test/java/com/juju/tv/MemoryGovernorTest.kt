package com.juju.tv

import android.content.ComponentCallbacks2
import com.juju.tv.memory.ByteBufferPool
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.model.MemoryWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGovernorTest {

    @Test
    fun testFourLevelMemoryWatermarkTransitionsOn2GbRam() {
        val pool = ByteBufferPool(bufferSize = 65536, maxPoolSize = 6)
        val governor = MemoryGovernor(pool)

        // 1. 600MB 可用内存 -> NORMAL 水位（允许弹幕，12MB 海报缓存）
        val wNormal = governor.updateFromMetrics(
            availRamMb = 600L,
            isSystemLowMemory = false,
            jvmUsedHeapMb = 40L,
            jvmMaxHeapMb = 192L
        )
        assertEquals(MemoryWatermark.NORMAL, wNormal)
        assertTrue(wNormal.allowDanmaku)
        assertEquals(12 * 1024 * 1024, wNormal.maxPosterCacheBytes)

        // 2. 360MB 可用内存 -> MODERATE 水位（缩容至 6MB 海报缓存）
        val wModerate = governor.updateFromMetrics(
            availRamMb = 360L,
            isSystemLowMemory = false,
            jvmUsedHeapMb = 50L,
            jvmMaxHeapMb = 192L
        )
        assertEquals(MemoryWatermark.MODERATE, wModerate)
        assertTrue(wModerate.allowDanmaku)
        assertEquals(6 * 1024 * 1024, wModerate.maxPosterCacheBytes)

        // 3. 250MB 可用内存 -> LOW 水位（自动挂起弹幕，缩容至 2.5MB 海报缓存）
        val wLow = governor.updateFromMetrics(
            availRamMb = 250L,
            isSystemLowMemory = false,
            jvmUsedHeapMb = 60L,
            jvmMaxHeapMb = 192L
        )
        assertEquals(MemoryWatermark.LOW, wLow)
        assertFalse(wLow.allowDanmaku)

        // 4. 180MB 可用内存或系统 TRIM_MEMORY_RUNNING_CRITICAL -> CRITICAL 水位
        val wCritical = governor.updateFromMetrics(
            availRamMb = 180L,
            isSystemLowMemory = true,
            jvmUsedHeapMb = 120L,
            jvmMaxHeapMb = 192L,
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        )
        assertEquals(MemoryWatermark.CRITICAL, wCritical)
        assertFalse(wCritical.allowDanmaku)
        assertTrue(pool.availableCount() <= 1)
    }

    @Test
    fun testByteBufferPoolZeroLeakReuse() {
        val pool = ByteBufferPool(bufferSize = 1024, maxPoolSize = 4)
        val b1 = pool.acquire()
        pool.release(b1)
        val b2 = pool.acquire()
        assertSame(b1, b2)
        pool.release(b2)
        assertTrue(pool.stats()["hitCount"]!! >= 2)
    }
}
