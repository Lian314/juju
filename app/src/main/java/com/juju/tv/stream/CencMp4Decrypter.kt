package com.juju.tv.stream

import com.juju.tv.memory.ByteBufferPool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISO BMFF (MP4 / fMP4) Common Encryption (CENC `cenc` AES-128-CTR) 零拷贝原地流式解密引擎
 *
 * 复刻并优化自原版 Go 层 `media_cenc.go` (`cencPrepare` / `cencDecryptInPlace` / `cencReadSeeker`)：
 * 1. 递归解析 `moov`/`tenc`/`frma`/`trex`、分片 `moof`/`traf`/`senc`(含 UUID 扩展)/`trun`/`mdat`
 *    以及标准非分片 MP4 `stbl` (`stsz`/`stsc`/`stco`/`co64`/`senc`) Box 树；
 * 2. 提取每个 Sample 的独立 8/16 字节 IV、Subsample (BytesOfClearData + BytesOfProtectedData) 列表及文件绝对字节偏移；
 * 3. 原地将 `encv`/`enca` 采样描述头恢复为 `frma` 原始编码 (`avc1`/`hev1`/`hvc1`/`mp4a`) 并将 `sinf` 置为 `free`，
 *    让 ExoPlayer 硬件解码器无感直出；
 * 4. 支持任意 HTTP Range (`206 Partial Content`) 字节区间切片解密：自动计算块对齐 AES-CTR Counter 偏移并结合
 *    [ByteBufferPool] 原地异或解密，消除临时数组分配。
 */
object CencMp4Decrypter {

    // PIFF / CENC SampleEncryptionBox 16-byte UUID: a2394f52-5a9b-4f14-a244-6c427c648df4
    private val SENC_UUID_BYTES = byteArrayOf(
        0xA2.toByte(), 0x39, 0x4F, 0x52,
        0x5A, 0x9B.toByte(), 0x4F, 0x14,
        0xA2.toByte(), 0x44, 0x6C, 0x42,
        0x7C, 0x64, 0x8D.toByte(), 0xF4.toByte()
    )

    data class SubsampleEntry(
        val clearBytes: Int,
        val encryptedBytes: Long
    )

    data class CencSample(
        val sampleIndex: Int,
        val fileOffset: Long,
        val size: Long,
        val iv: ByteArray, // 16-byte AES-CTR initial counter block
        val subsamples: List<SubsampleEntry> = emptyList()
    )

    data class CencMetadata(
        val defaultIvSize: Int = 8,
        val defaultKidHex: String = "",
        val originalVideoFormat: String = "avc1",
        val originalAudioFormat: String = "mp4a",
        val headerEndOffset: Long = 0L,
        val samples: List<CencSample> = emptyList()
    )

    data class Mp4Box(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int,
        val payloadOffset: Long = offset + headerSize,
        val payloadSize: Long = (size - headerSize).coerceAtLeast(0L)
    )

    /**
     * 读取大端 32 位无符号整数（对应 Go `cencI32`）
     */
    fun readUInt32BE(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    /**
     * 读取大端 64 位整数（对应 Go `cencI64`）
     */
    fun readUInt64BE(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > data.size) return 0L
        return (readUInt32BE(data, offset) shl 32) or (readUInt32BE(data, offset + 4) and 0xFFFFFFFFL)
    }

    fun readUInt16BE(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    /**
     * 解析指定区间内的同级 MP4 Box 列表（对应 Go `cencBoxAt` / `cencFind`）
     */
    fun listBoxes(data: ByteArray, startOffset: Int = 0, endOffset: Int = data.size): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var pos = startOffset.coerceAtLeast(0)
        val limit = endOffset.coerceAtMost(data.size)
        while (pos + 8 <= limit) {
            val rawSize = readUInt32BE(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var boxSize = rawSize
            var headerSize = 8
            if (rawSize == 1L) {
                if (pos + 16 > limit) break
                boxSize = readUInt64BE(data, pos + 8)
                headerSize = 16
            } else if (rawSize == 0L) {
                boxSize = (limit - pos).toLong()
            }
            if (boxSize < headerSize) break
            boxes.add(
                Mp4Box(
                    type = type,
                    offset = pos.toLong(),
                    size = boxSize,
                    headerSize = headerSize
                )
            )
            val nextPos = pos + boxSize
            if (nextPos <= pos || nextPos > Int.MAX_VALUE) break
            pos = nextPos.toInt()
        }
        return boxes
    }

    /**
     * 解析 MP4 / fMP4 二进制数据，提取 tenc/frma 默认配置、分片 moof/senc/trun 与标准 stbl 采样加密表
     */
    fun parseCencMetadata(data: ByteArray, baseFileOffset: Long = 0L): CencMetadata {
        val rootBoxes = listBoxes(data, 0, data.size)
        var defaultIvSize = 8
        var defaultKidHex = ""
        var originalVideoFormat = "avc1"
        var originalAudioFormat = "mp4a"
        var headerEndOffset = 0L
        var trexDefaultSampleSize = 0L

        // 1. 查找 tenc (Track Encryption Box)
        val tencBox = findBoxRecursive(data, 0, data.size, "tenc")
        if (tencBox != null) {
            val p = tencBox.payloadOffset.toInt()
            // FullBox: version(1) + flags(3) + reserved(1) + default_crypt_byte_block/skip(1) +
            // default_isProtected(1) + default_Per_Sample_IV_Size(1) + default_KID(16)
            if (p + 24 <= data.size) {
                val ivSizeCandidate = data[p + 7].toInt() and 0xFF
                if (ivSizeCandidate == 8 || ivSizeCandidate == 16) {
                    defaultIvSize = ivSizeCandidate
                }
                defaultKidHex = bytesToHex(data, p + 8, 16)
            }
        }

        // 查找 frma (Original Format Box) 以还原 hev1 / hvc1 / avc1 / mp4a
        val frmaBox = findBoxRecursive(data, 0, data.size, "frma")
        if (frmaBox != null && frmaBox.payloadOffset + 4 <= data.size) {
            val fmt = String(data, frmaBox.payloadOffset.toInt(), 4, Charsets.ISO_8859_1)
            if (fmt in setOf("avc1", "avc3", "hev1", "hvc1", "vp09", "av01")) {
                originalVideoFormat = fmt
            } else if (fmt in setOf("mp4a", "ac-3", "ec-3", "Opus")) {
                originalAudioFormat = fmt
            }
        }

        // 查找 trex (Track Extends Box) 获取全局默认 sample size
        val trexBox = findBoxRecursive(data, 0, data.size, "trex")
        if (trexBox != null && trexBox.payloadOffset + 20 <= data.size) {
            val tp = trexBox.payloadOffset.toInt()
            trexDefaultSampleSize = readUInt32BE(data, tp + 12)
        }

        val allSamples = mutableListOf<CencSample>()
        var globalSampleIdx = 0

        // 2. 遍历所有 moof + mdat 分片对 (fMP4 模式)
        for (i in rootBoxes.indices) {
            val box = rootBoxes[i]
            if (box.type == "moov" || box.type == "ftyp" || box.type == "sidx") {
                headerEndOffset = maxOf(headerEndOffset, baseFileOffset + box.offset + box.size)
            }
            if (box.type == "moof") {
                val moofAbsStart = baseFileOffset + box.offset
                val moofPayloadStart = box.payloadOffset.toInt()
                val moofEnd = (box.offset + box.size).toInt().coerceAtMost(data.size)

                // 查找紧随其后的 mdat 作为默认数据基准
                val nextMdat = rootBoxes.drop(i + 1).firstOrNull { it.type == "mdat" }
                val defaultMdatPayloadOffset = if (nextMdat != null) {
                    baseFileOffset + nextMdat.payloadOffset
                } else {
                    moofAbsStart + box.size + 8L
                }

                val trafBoxes = listBoxes(data, moofPayloadStart, moofEnd).filter { it.type == "traf" }
                for (traf in trafBoxes) {
                    val trafStart = traf.payloadOffset.toInt()
                    val trafEnd = (traf.offset + traf.size).toInt().coerceAtMost(data.size)
                    val trafChildren = listBoxes(data, trafStart, trafEnd)

                    // 解析 tfhd 获取 baseDataOffset / defaultSampleSize
                    var baseDataOffset = moofAbsStart
                    var defaultSampleSize = trexDefaultSampleSize
                    val tfhd = trafChildren.firstOrNull { it.type == "tfhd" }
                    if (tfhd != null) {
                        val tp = tfhd.payloadOffset.toInt()
                        if (tp + 8 <= data.size) {
                            val tfFlags = ((data[tp + 1].toInt() and 0xFF) shl 16) or
                                    ((data[tp + 2].toInt() and 0xFF) shl 8) or
                                    (data[tp + 3].toInt() and 0xFF)
                            var cursor = tp + 8 // version/flags(4) + track_ID(4)
                            if ((tfFlags and 0x000001) != 0 && cursor + 8 <= data.size) {
                                baseDataOffset = readUInt64BE(data, cursor)
                                cursor += 8
                            }
                            if ((tfFlags and 0x000002) != 0) cursor += 4 // sample_description_index
                            if ((tfFlags and 0x000008) != 0) cursor += 4 // default_sample_duration
                            if ((tfFlags and 0x000010) != 0 && cursor + 4 <= data.size) {
                                defaultSampleSize = readUInt32BE(data, cursor)
                            }
                        }
                    }

                    // 解析所有 trun 获取每个 sample 的大小与文件偏移
                    val sampleSizes = mutableListOf<Long>()
                    var trunDataOffset: Long? = null
                    val trunBoxes = trafChildren.filter { it.type == "trun" }
                    for (trun in trunBoxes) {
                        val trp = trun.payloadOffset.toInt()
                        if (trp + 8 <= data.size) {
                            val trFlags = ((data[trp + 1].toInt() and 0xFF) shl 16) or
                                    ((data[trp + 2].toInt() and 0xFF) shl 8) or
                                    (data[trp + 3].toInt() and 0xFF)
                            val sampleCount = readUInt32BE(data, trp + 4).toInt().coerceIn(0, 100_000)
                            var cursor = trp + 8
                            if ((trFlags and 0x000001) != 0 && cursor + 4 <= data.size) {
                                val signedOffset = ByteBuffer.wrap(data, cursor, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
                                if (trunDataOffset == null) {
                                    trunDataOffset = baseDataOffset + signedOffset
                                }
                                cursor += 4
                            }
                            if ((trFlags and 0x000004) != 0) cursor += 4 // first_sample_flags
                            val hasDuration = (trFlags and 0x000100) != 0
                            val hasSize = (trFlags and 0x000200) != 0
                            val hasFlags = (trFlags and 0x000400) != 0
                            val hasCts = (trFlags and 0x000800) != 0

                            for (s in 0 until sampleCount) {
                                if (hasDuration) cursor += 4
                                val sSize = if (hasSize && cursor + 4 <= data.size) {
                                    val v = readUInt32BE(data, cursor)
                                    cursor += 4
                                    v
                                } else {
                                    defaultSampleSize
                                }
                                if (hasFlags) cursor += 4
                                if (hasCts) cursor += 4
                                sampleSizes.add(sSize)
                            }
                        }
                    }

                    // 解析 senc（或 PIFF UUID senc）获取每个 sample 的 IV 与 Subsample 列表
                    val sencBox = trafChildren.firstOrNull { isSencOrUuidSenc(data, it) }
                    val (sencIvs, sencSubsamples) = if (sencBox != null) {
                        parseSencPayload(data, sencBox, defaultIvSize)
                    } else {
                        emptyList<ByteArray>() to emptyList<List<SubsampleEntry>>()
                    }

                    // 组装 CencSample 列表
                    var runningOffset = trunDataOffset ?: defaultMdatPayloadOffset
                    val count = maxOf(sampleSizes.size, sencIvs.size)
                    for (idx in 0 until count) {
                        val subs = sencSubsamples.getOrNull(idx) ?: emptyList()
                        val subTotalSize = subs.sumOf { it.clearBytes.toLong() + it.encryptedBytes }
                        val sz = sampleSizes.getOrNull(idx)?.takeIf { it > 0 } ?: subTotalSize
                        val iv = sencIvs.getOrNull(idx) ?: ByteArray(16)
                        if (sz > 0) {
                            allSamples.add(
                                CencSample(
                                    sampleIndex = globalSampleIdx++,
                                    fileOffset = runningOffset,
                                    size = sz,
                                    iv = iv,
                                    subsamples = subs
                                )
                            )
                            runningOffset += sz
                        }
                    }
                }
            }
        }

        // 3. 若未发现 fMP4 moof 采样，则尝试解析标准非分片 MP4 (`moov -> trak -> mdia -> minf -> stbl`)
        if (allSamples.isEmpty()) {
            val stblSamples = parseStblCencSamples(data, baseFileOffset, defaultIvSize)
            allSamples.addAll(stblSamples)
        }

        return CencMetadata(
            defaultIvSize = defaultIvSize,
            defaultKidHex = defaultKidHex,
            originalVideoFormat = originalVideoFormat,
            originalAudioFormat = originalAudioFormat,
            headerEndOffset = headerEndOffset,
            samples = allSamples
        )
    }

    private fun isSencOrUuidSenc(data: ByteArray, box: Mp4Box): Boolean {
        if (box.type == "senc") return true
        if (box.type == "uuid") {
            val p = box.payloadOffset.toInt()
            if (p + 16 <= data.size) {
                for (i in 0 until 16) {
                    if (data[p + i] != SENC_UUID_BYTES[i]) return false
                }
                return true
            }
        }
        return false
    }

    private fun parseSencPayload(
        data: ByteArray,
        senc: Mp4Box,
        defaultIvSize: Int
    ): Pair<List<ByteArray>, List<List<SubsampleEntry>>> {
        val sencIvs = mutableListOf<ByteArray>()
        val sencSubsamples = mutableListOf<List<SubsampleEntry>>()
        val sp = (senc.payloadOffset + if (senc.type == "uuid") 16L else 0L).toInt()
        val sEnd = (senc.offset + senc.size).toInt().coerceAtMost(data.size)
        if (sp + 8 > sEnd) return sencIvs to sencSubsamples

        val sencFlags = ((data[sp + 1].toInt() and 0xFF) shl 16) or
                ((data[sp + 2].toInt() and 0xFF) shl 8) or
                (data[sp + 3].toInt() and 0xFF)
        var cursor = sp + 4
        var ivSize = defaultIvSize
        // ISO/IEC 23001-7: flags & 0x01 表示 Override TrackEncryptionBox parameters (AlgorithmID 3B + IV_Size 1B + KID 16B)
        if ((sencFlags and 0x000001) != 0) {
            if (cursor + 20 > sEnd) return sencIvs to sencSubsamples
            val overrideIvSize = data[cursor + 3].toInt() and 0xFF
            if (overrideIvSize == 8 || overrideIvSize == 16) {
                ivSize = overrideIvSize
            }
            cursor += 20
        }
        if (cursor + 4 > sEnd) return sencIvs to sencSubsamples
        val useSubsamples = (sencFlags and 0x000002) != 0
        val sampleCount = readUInt32BE(data, cursor).toInt().coerceIn(0, 100_000)
        cursor += 4

        for (s in 0 until sampleCount) {
            val iv16 = ByteArray(16)
            if (ivSize > 0) {
                if (cursor + ivSize > sEnd) break
                System.arraycopy(data, cursor, iv16, 0, ivSize.coerceAtMost(16))
                cursor += ivSize
            }
            sencIvs.add(iv16)

            if (useSubsamples) {
                if (cursor + 2 > sEnd) break
                val subCount = readUInt16BE(data, cursor)
                cursor += 2
                val subs = mutableListOf<SubsampleEntry>()
                for (k in 0 until subCount) {
                    if (cursor + 6 > sEnd) break
                    val clearB = readUInt16BE(data, cursor)
                    val encB = readUInt32BE(data, cursor + 2)
                    cursor += 6
                    subs.add(SubsampleEntry(clearB, encB))
                }
                sencSubsamples.add(subs)
            } else {
                sencSubsamples.add(emptyList())
            }
        }
        return sencIvs to sencSubsamples
    }

    /**
     * 解析标准非分片 MP4 (`moov -> trak -> mdia -> minf -> stbl`) 的 CENC 采样表
     * 支持 `stsz` + `stsc` + (`stco` | `co64`) + `senc`
     */
    private fun parseStblCencSamples(data: ByteArray, baseFileOffset: Long, defaultIvSize: Int): List<CencSample> {
        val stblBox = findBoxRecursive(data, 0, data.size, "stbl") ?: return emptyList()
        val stblStart = stblBox.payloadOffset.toInt()
        val stblEnd = (stblBox.offset + stblBox.size).toInt().coerceAtMost(data.size)
        val children = listBoxes(data, stblStart, stblEnd)

        // 1. 解析 stsz 获取每个 sample 的字节长度
        val stsz = children.firstOrNull { it.type == "stsz" } ?: return emptyList()
        val szp = stsz.payloadOffset.toInt()
        if (szp + 12 > data.size) return emptyList()
        val uniformSize = readUInt32BE(data, szp + 4)
        val sampleCount = readUInt32BE(data, szp + 8).toInt().coerceIn(0, 200_000)
        val sampleSizes = LongArray(sampleCount)
        var szCursor = szp + 12
        for (i in 0 until sampleCount) {
            sampleSizes[i] = if (uniformSize > 0L) {
                uniformSize
            } else if (szCursor + 4 <= data.size) {
                val v = readUInt32BE(data, szCursor)
                szCursor += 4
                v
            } else 0L
        }

        // 2. 解析 stco (32-bit) 或 co64 (64-bit) 获取每个 Chunk 的起始偏移
        val chunkOffsets = mutableListOf<Long>()
        val stco = children.firstOrNull { it.type == "stco" }
        val co64 = children.firstOrNull { it.type == "co64" }
        if (stco != null && stco.payloadOffset + 8 <= data.size) {
            val cp = stco.payloadOffset.toInt()
            val count = readUInt32BE(data, cp + 4).toInt().coerceIn(0, 200_000)
            var cCursor = cp + 8
            for (i in 0 until count) {
                if (cCursor + 4 > data.size) break
                chunkOffsets.add(baseFileOffset + readUInt32BE(data, cCursor))
                cCursor += 4
            }
        } else if (co64 != null && co64.payloadOffset + 8 <= data.size) {
            val cp = co64.payloadOffset.toInt()
            val count = readUInt32BE(data, cp + 4).toInt().coerceIn(0, 200_000)
            var cCursor = cp + 8
            for (i in 0 until count) {
                if (cCursor + 8 > data.size) break
                chunkOffsets.add(baseFileOffset + readUInt64BE(data, cCursor))
                cCursor += 8
            }
        }
        if (chunkOffsets.isEmpty()) return emptyList()

        // 3. 解析 stsc (Sample-to-Chunk) 计算每个 Chunk 包含多少个 Sample
        data class StscRun(val firstChunkOneBased: Int, val samplesPerChunk: Int)
        val stscRuns = mutableListOf<StscRun>()
        val stsc = children.firstOrNull { it.type == "stsc" }
        if (stsc != null && stsc.payloadOffset + 8 <= data.size) {
            val scp = stsc.payloadOffset.toInt()
            val entryCount = readUInt32BE(data, scp + 4).toInt().coerceIn(0, 100_000)
            var scCursor = scp + 8
            for (i in 0 until entryCount) {
                if (scCursor + 12 > data.size) break
                val firstChunk = readUInt32BE(data, scCursor).toInt().coerceAtLeast(1)
                val spc = readUInt32BE(data, scCursor + 4).toInt().coerceAtLeast(1)
                stscRuns.add(StscRun(firstChunk, spc))
                scCursor += 12
            }
        }
        if (stscRuns.isEmpty()) {
            stscRuns.add(StscRun(1, sampleCount.coerceAtLeast(1)))
        }

        // 4. 查找 senc（可在 stbl 内或 trak 内）
        val sencBox = children.firstOrNull { isSencOrUuidSenc(data, it) }
            ?: findBoxRecursive(data, 0, data.size, "senc")
        val (sencIvs, sencSubsamples) = if (sencBox != null) {
            parseSencPayload(data, sencBox, defaultIvSize)
        } else {
            emptyList<ByteArray>() to emptyList<List<SubsampleEntry>>()
        }
        if (sencIvs.isEmpty()) return emptyList()

        // 5. 计算每个 Sample 的绝对文件偏移
        val result = mutableListOf<CencSample>()
        var sampleIdx = 0
        for (chunkIdxZeroBased in chunkOffsets.indices) {
            if (sampleIdx >= sampleCount) break
            val chunkOneBased = chunkIdxZeroBased + 1
            val activeRun = stscRuns.lastOrNull { it.firstChunkOneBased <= chunkOneBased } ?: stscRuns.first()
            var offsetInChunk = chunkOffsets[chunkIdxZeroBased]
            for (k in 0 until activeRun.samplesPerChunk) {
                if (sampleIdx >= sampleCount) break
                val sz = sampleSizes[sampleIdx]
                val iv = sencIvs.getOrNull(sampleIdx) ?: ByteArray(16)
                val subs = sencSubsamples.getOrNull(sampleIdx) ?: emptyList()
                if (sz > 0) {
                    result.add(
                        CencSample(
                            sampleIndex = sampleIdx,
                            fileOffset = offsetInChunk,
                            size = sz,
                            iv = iv,
                            subsamples = subs
                        )
                    )
                    offsetInChunk += sz
                }
                sampleIdx++
            }
        }
        return result
    }

    private fun findBoxRecursive(data: ByteArray, start: Int, end: Int, targetType: String): Mp4Box? {
        val boxes = listBoxes(data, start, end)
        for (b in boxes) {
            if (b.type == targetType) return b
            if (targetType == "senc" && isSencOrUuidSenc(data, b)) return b
            if (b.type in setOf("moov", "mvex", "trak", "mdia", "minf", "stbl", "sinf", "schi", "moof", "traf")) {
                val childStart = b.payloadOffset.toInt()
                val childEnd = (b.offset + b.size).toInt().coerceAtMost(data.size)
                if (childStart < childEnd) {
                    val found = findBoxRecursive(data, childStart, childEnd, targetType)
                    if (found != null) return found
                }
            } else if (b.type == "stsd") {
                val pStart = (b.payloadOffset + 8).toInt()
                val pEnd = (b.offset + b.size).toInt().coerceAtMost(data.size)
                val entries = listBoxes(data, pStart, pEnd)
                for (entry in entries) {
                    val innerOffset = when (entry.type) {
                        "encv", "avc1", "hev1", "hvc1" -> (entry.payloadOffset + 78).toInt()
                        "enca", "mp4a" -> (entry.payloadOffset + 28).toInt()
                        else -> entry.payloadOffset.toInt()
                    }
                    val innerEnd = (entry.offset + entry.size).toInt().coerceAtMost(data.size)
                    if (innerOffset < innerEnd) {
                        val found = findBoxRecursive(data, innerOffset, innerEnd, targetType)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    /**
     * 原地解密单个完整 Sample（对应原版 Go `cencDecryptInPlace`）
     *
     * 修复重点：
     * 1. 当关键帧的首个 Subsample 为纯明文 SPS/PPS NALU (`clearBytes > 0, encryptedBytes == 0`) 时，
     *    必须仅跳过 `clearBytes` 并继续解密后续 IDR Slice Subsample，绝不能提前 `break`！
     * 2. 统一通过连续 `cipherStreamOffset` 调用 [xorAesCtrSliceInPlace]，彻底规避不同 Android JCE Provider
     *    在非 16 字节对齐 `cipher.update()` 时的内部缓冲差异。
     */
    fun decryptSampleInPlace(
        sampleBytes: ByteArray,
        offset: Int = 0,
        length: Int = sampleBytes.size,
        keyBytes: ByteArray,
        iv16: ByteArray,
        subsamples: List<SubsampleEntry> = emptyList(),
        bufferPool: ByteBufferPool = ByteBufferPool.shared
    ) {
        require(keyBytes.size == 16) { "CENC AES-128-CTR key must be 16 bytes" }
        val fullIv = if (iv16.size == 16) iv16 else iv16.copyOf(16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")

        if (subsamples.isEmpty()) {
            xorAesCtrSliceInPlace(
                cipher = cipher,
                keySpec = keySpec,
                baseIv16 = fullIv,
                cipherStreamOffset = 0L,
                buffer = sampleBytes,
                bufferOffset = offset,
                length = length.coerceAtMost(sampleBytes.size - offset),
                bufferPool = bufferPool
            )
            return
        }

        // Subsample 模式：所有 encryptedBytes 在逻辑上共享同一个连续递增的 AES-CTR 密钥流
        var cursor = offset
        val limit = (offset + length).coerceAtMost(sampleBytes.size)
        var cipherStreamOffset = 0L
        for (sub in subsamples) {
            cursor += sub.clearBytes
            if (cursor > limit) break
            val encLen = sub.encryptedBytes.toInt()
            if (encLen <= 0) {
                // 纯明文 Subsample（如 H.264/HEVC 的 SPS/PPS/SEI NALU），继续处理下一个 Subsample
                continue
            }
            val safeEncLen = encLen.coerceAtMost(limit - cursor)
            if (safeEncLen <= 0) break
            xorAesCtrSliceInPlace(
                cipher = cipher,
                keySpec = keySpec,
                baseIv16 = fullIv,
                cipherStreamOffset = cipherStreamOffset,
                buffer = sampleBytes,
                bufferOffset = cursor,
                length = safeEncLen,
                bufferPool = bufferPool
            )
            cursor += safeEncLen
            cipherStreamOffset += encLen
            if (safeEncLen < encLen) break
        }
    }

    /**
     * 针对任意 HTTP Range 字节块 (`[chunkFileOffset, chunkFileOffset + chunkLength)`) 进行原地解密
     */
    fun decryptStreamRangeInPlace(
        buffer: ByteArray,
        chunkLength: Int,
        chunkFileOffset: Long,
        keyBytes: ByteArray,
        samples: List<CencSample>,
        bufferPool: ByteBufferPool = ByteBufferPool.shared
    ) {
        if (chunkLength <= 0 || samples.isEmpty() || keyBytes.size != 16) return
        val chunkEnd = chunkFileOffset + chunkLength
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        // 二分查找第一个结束位置 > chunkFileOffset 的 Sample
        var low = 0
        var high = samples.size - 1
        var firstIdx = samples.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            val s = samples[mid]
            if (s.fileOffset + s.size > chunkFileOffset) {
                firstIdx = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        for (i in firstIdx until samples.size) {
            val sample = samples[i]
            if (sample.fileOffset >= chunkEnd) break

            if (sample.subsamples.isEmpty()) {
                val intersectStart = maxOf(chunkFileOffset, sample.fileOffset)
                val intersectEnd = minOf(chunkEnd, sample.fileOffset + sample.size)
                if (intersectEnd > intersectStart) {
                    val cipherOffsetInSample = intersectStart - sample.fileOffset
                    val bufPos = (intersectStart - chunkFileOffset).toInt()
                    val len = (intersectEnd - intersectStart).toInt()
                    xorAesCtrSliceInPlace(
                        cipher = cipher,
                        keySpec = keySpec,
                        baseIv16 = sample.iv,
                        cipherStreamOffset = cipherOffsetInSample,
                        buffer = buffer,
                        bufferOffset = bufPos,
                        length = len,
                        bufferPool = bufferPool
                    )
                }
            } else {
                var sampleCursorFileOffset = sample.fileOffset
                var cipherStreamOffset = 0L
                for (sub in sample.subsamples) {
                    sampleCursorFileOffset += sub.clearBytes
                    val encStart = sampleCursorFileOffset
                    val encEnd = encStart + sub.encryptedBytes
                    if (sub.encryptedBytes > 0) {
                        val intersectStart = maxOf(chunkFileOffset, encStart)
                        val intersectEnd = minOf(chunkEnd, encEnd)
                        if (intersectEnd > intersectStart) {
                            val offsetInsideSub = intersectStart - encStart
                            val bufPos = (intersectStart - chunkFileOffset).toInt()
                            val len = (intersectEnd - intersectStart).toInt()
                            xorAesCtrSliceInPlace(
                                cipher = cipher,
                                keySpec = keySpec,
                                baseIv16 = sample.iv,
                                cipherStreamOffset = cipherStreamOffset + offsetInsideSub,
                                buffer = buffer,
                                bufferOffset = bufPos,
                                length = len,
                                bufferPool = bufferPool
                            )
                        }
                    }
                    sampleCursorFileOffset = encEnd
                    cipherStreamOffset += sub.encryptedBytes
                }
            }
        }
    }

    /**
     * 对连续 AES-CTR 密文流中任意偏移 [cipherStreamOffset] 处的 [length] 字节进行零分配原地解密
     *
     * 复用 [ByteBufferPool] 中的 64KB 缓冲块消除 `doFinal` 临时数组分配，实现真正的 Zero-GC。
     */
    private fun xorAesCtrSliceInPlace(
        cipher: Cipher,
        keySpec: SecretKeySpec,
        baseIv16: ByteArray,
        cipherStreamOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
        bufferPool: ByteBufferPool = ByteBufferPool.shared
    ) {
        if (length <= 0) return
        var remaining = length
        var curBufOffset = bufferOffset
        var curStreamOffset = cipherStreamOffset

        bufferPool.withBuffer { scratch ->
            val maxChunk = (scratch.size - 16).coerceAtLeast(16)
            while (remaining > 0) {
                val blockIndex = curStreamOffset / 16L
                val byteInBlock = (curStreamOffset % 16L).toInt()
                val stepLen = minOf(remaining, maxChunk)
                val counterIv = incrementIvCounter(baseIv16, blockIndex)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(counterIv))

                if (byteInBlock == 0) {
                    val produced = cipher.doFinal(buffer, curBufOffset, stepLen, scratch, 0)
                    System.arraycopy(scratch, 0, buffer, curBufOffset, minOf(stepLen, produced))
                } else {
                    for (z in 0 until byteInBlock) scratch[z] = 0
                    System.arraycopy(buffer, curBufOffset, scratch, byteInBlock, stepLen)
                    val totalIn = byteInBlock + stepLen
                    cipher.doFinal(scratch, 0, totalIn, scratch, 0)
                    System.arraycopy(scratch, byteInBlock, buffer, curBufOffset, stepLen)
                }

                curBufOffset += stepLen
                curStreamOffset += stepLen
                remaining -= stepLen
            }
        }
    }

    /**
     * 将 16 字节 IV 的后 8 字节（64-bit Big-Endian Counter）加上 [blockDelta]
     */
    fun incrementIvCounter(iv16: ByteArray, blockDelta: Long): ByteArray {
        val out = if (iv16.size == 16) iv16.copyOf() else iv16.copyOf(16)
        if (blockDelta == 0L) return out
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        val high = bb.getLong(0)
        val low = bb.getLong(8)
        bb.putLong(0, high)
        bb.putLong(8, low + blockDelta)
        return out
    }

    /**
     * 原地将 MP4 头部中的 `encv` / `enca` 改写为原始编码（如 `avc1` / `hev1` / `hvc1` / `mp4a`），
     * 并将 `sinf` 改写为 `free` Box，使得标准无 DRM 的 ExoPlayer Mp4Extractor 直接将其当作明文流硬解！
     */
    fun patchSchemeBoxesToClearInPlace(
        buffer: ByteArray,
        length: Int = buffer.size,
        videoFormat: String = "avc1",
        audioFormat: String = "mp4a"
    ) {
        val limit = (length - 8).coerceAtMost(buffer.size - 8)
        if (limit < 0) return
        val vBytes = (if (videoFormat.length == 4) videoFormat else "avc1").toByteArray(Charsets.ISO_8859_1)
        val aBytes = (if (audioFormat.length == 4) audioFormat else "mp4a").toByteArray(Charsets.ISO_8859_1)

        var i = 0
        while (i <= limit) {
            if (buffer[i + 4] == 'e'.code.toByte() &&
                buffer[i + 5] == 'n'.code.toByte() &&
                buffer[i + 6] == 'c'.code.toByte()
            ) {
                if (buffer[i + 7] == 'v'.code.toByte()) {
                    buffer[i + 4] = vBytes[0]
                    buffer[i + 5] = vBytes[1]
                    buffer[i + 6] = vBytes[2]
                    buffer[i + 7] = vBytes[3]
                } else if (buffer[i + 7] == 'a'.code.toByte()) {
                    buffer[i + 4] = aBytes[0]
                    buffer[i + 5] = aBytes[1]
                    buffer[i + 6] = aBytes[2]
                    buffer[i + 7] = aBytes[3]
                }
            } else if (
                buffer[i + 4] == 's'.code.toByte() &&
                buffer[i + 5] == 'i'.code.toByte() &&
                buffer[i + 6] == 'n'.code.toByte() &&
                buffer[i + 7] == 'f'.code.toByte()
            ) {
                buffer[i + 4] = 'f'.code.toByte()
                buffer[i + 5] = 'r'.code.toByte()
                buffer[i + 6] = 'e'.code.toByte()
                buffer[i + 7] = 'e'.code.toByte()
            }
            i++
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace("-", "")
        if (clean.length % 2 != 0) return ByteArray(0)
        return try {
            ByteArray(clean.length / 2) { idx ->
                clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            ByteArray(0)
        }
    }

    fun bytesToHex(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until (offset + length).coerceAtMost(bytes.size)) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }
}
