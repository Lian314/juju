package com.juju.tv

import com.juju.tv.model.FollowEntry
import com.juju.tv.model.SyncPayload
import com.juju.tv.model.WatchHistoryEntry
import com.juju.tv.sync.WebDavSyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSyncEngineTest {

    private val engine = WebDavSyncEngine()

    @Test
    fun testGzipCompressDecompressAndLwwBidirectionalMerge() {
        val local = SyncPayload(
            space = "living_room",
            histories = listOf(
                WatchHistoryEntry("hg_1001", "潜龙出渊", "", "hongguo", episodeIndex = 5, "第5集", 80, 45000L, 90000L, 50, updatedAt = 1000L),
                WatchHistoryEntry("hg_1002", "盛世娇宠", "", "hongguo", episodeIndex = 12, "第12集", 80, 80000L, 90000L, 88, updatedAt = 3000L)
            ),
            follows = listOf(
                FollowEntry("hg_1001", "潜龙出渊", "", "hongguo", 80, lastEpisodeIndex = 5, updatedAt = 1000L, deleted = false),
                FollowEntry("hg_1003", "重生1990", "", "hongguo", 80, lastEpisodeIndex = 1, updatedAt = 2500L, deleted = false)
            )
        )

        val remote = SyncPayload(
            space = "living_room",
            histories = listOf(
                // 远端设备在 updatedAt = 2000L 看到了第 9 集 -> LWW 合并后应采用第 9 集
                WatchHistoryEntry("hg_1001", "潜龙出渊", "", "hongguo", episodeIndex = 9, "第9集", 80, 60000L, 90000L, 66, updatedAt = 2000L),
                // 远端设备 hg_1002 时间戳较旧 (1500L < 3000L) -> LWW 合并后应保留本机第 12 集
                WatchHistoryEntry("hg_1002", "盛世娇宠", "", "hongguo", episodeIndex = 3, "第3集", 80, 10000L, 90000L, 11, updatedAt = 1500L)
            ),
            follows = listOf(
                // 远端在 updatedAt = 4000L 取消了 hg_1003 追剧 (deleted = true) -> LWW 墓碑应生效
                FollowEntry("hg_1003", "重生1990", "", "hongguo", 80, lastEpisodeIndex = 1, updatedAt = 4000L, deleted = true)
            )
        )

        val merged = engine.mergePayloads(local, remote)
        val gzBytes = engine.compressPayloadGzip(merged)
        assertTrue(gzBytes.isNotEmpty())
        assertEquals(0x1F, gzBytes[0].toInt() and 0xFF)
        assertEquals(0x8B, gzBytes[1].toInt() and 0xFF)

        val restored = engine.decompressPayloadGzip(gzBytes)
        assertNotNull(restored)
        val h1 = restored!!.histories.first { it.dramaId == "hg_1001" }
        val h2 = restored.histories.first { it.dramaId == "hg_1002" }
        assertEquals(9, h1.episodeIndex)
        assertEquals(12, h2.episodeIndex)

        val f3 = restored.follows.first { it.dramaId == "hg_1003" }
        assertTrue(f3.deleted)
    }
}
