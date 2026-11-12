package com.juju.tv.memory

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * 零 GC 抖动的固定大小字节数组环形复用池（LIFO 栈式热缓存优先）
 *
 * 在 2GB RAM 的坚果 N1 Air 第四代上，流媒体代理（LocalStreamGateway）与
 * MP4 CENC AES-CTR 原地解密（CencMp4Decrypter）每秒需处理数 MB 的视频分片。
 * 若每次读写都分配临时 ByteArray(65536)，会频繁触发 Android ART 年轻代 GC 停顿并导致画面掉帧。
 *
 * 本对象池固定维护最多 [maxPoolSize] 个 [bufferSize]（默认 64KB）缓冲区，总占用严格不超过 512KB，
 * 并采用 LIFO（最近归还优先复用）策略提升 Cortex-A55 CPU L2 Cache 命中率。
 */
class ByteBufferPool(
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE
) {
    companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024 // 64 KB
        const val DEFAULT_MAX_POOL_SIZE = 8        // 8 * 64 KB = 512 KB 上限

        val shared = ByteBufferPool()
    }

    private val deque = ConcurrentLinkedDeque<ByteArray>()
    private val totalAllocatedCount = AtomicInteger(0)
    private val borrowCount = AtomicInteger(0)
    private val hitCount = AtomicInteger(0)

    init {
        // 预热 2 个缓冲区，其余按需延迟分配
        repeat(minOf(2, maxPoolSize)) {
            deque.offerFirst(ByteArray(bufferSize))
            totalAllocatedCount.incrementAndGet()
        }
    }

    /**
     * 借出一个长度为 [bufferSize] 的可复用缓冲区（优先取栈顶 CPU Cache 热缓冲）
     */
    fun acquire(): ByteArray {
        borrowCount.incrementAndGet()
        val pooled = deque.pollFirst()
        if (pooled != null) {
            hitCount.incrementAndGet()
            return pooled
        }
        totalAllocatedCount.incrementAndGet()
        return ByteArray(bufferSize)
    }

    /**
     * 归还缓冲区到栈顶（若池已满则直接丢弃由 GC 回收，防止池无限膨胀）
     */
    fun release(buffer: ByteArray?) {
        if (buffer == null || buffer.size != bufferSize) return
        if (deque.size < maxPoolSize) {
            deque.offerFirst(buffer)
        }
    }

    /**
     * 使用缓冲区的便捷高阶函数（保证异常安全归还）
     */
    inline fun <T> withBuffer(block: (ByteArray) -> T): T {
        val buf = acquire()
        return try {
            block(buf)
        } finally {
            release(buf)
        }
    }

    /**
     * 在 CRITICAL 内存水位下收缩池至最少保留数
     */
    fun trimTo(keepCount: Int = 1) {
        while (deque.size > keepCount.coerceAtLeast(0)) {
            deque.pollLast()
        }
    }

    fun availableCount(): Int = deque.size

    fun stats(): Map<String, Int> = mapOf(
        "available" to deque.size,
        "maxPoolSize" to maxPoolSize,
        "bufferSize" to bufferSize,
        "totalAllocated" to totalAllocatedCount.get(),
        "borrowCount" to borrowCount.get(),
        "hitCount" to hitCount.get()
    )
}
