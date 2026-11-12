package com.juju.tv

import com.juju.tv.memory.ByteBufferPool
import com.juju.tv.stream.CencMp4Decrypter
import com.juju.tv.stream.LocalStreamGateway
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CencMp4DecrypterTest {

    private val testKey = CencMp4Decrypter.hexToBytes("00112233445566778899aabbccddeeff")
    private val testIv8 = CencMp4Decrypter.hexToBytes("0102030405060708")

    @Test
    fun testFullSampleDecryptInPlace() {
        val plain = ByteArray(100) { idx -> (idx * 3 + 7).toByte() }
        val iv16 = testIv8.copyOf(16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), IvParameterSpec(iv16))
        val encrypted = cipher.doFinal(plain)

        val working = encrypted.copyOf()
        CencMp4Decrypter.decryptSampleInPlace(
            sampleBytes = working,
            keyBytes = testKey,
            iv16 = iv16,
            subsamples = emptyList()
        )
        assertArrayEquals(plain, working)
    }

    @Test
    fun testKeyframeWithZeroEncryptedBytesSpsPpsSubsamples() {
        // 真实 H.264/HEVC CENC 关键帧场景：
        // Subsample 0 (SPS NALU): clearBytes = 16, encryptedBytes = 0
        // Subsample 1 (PPS NALU): clearBytes = 8, encryptedBytes = 0
        // Subsample 2 (IDR Slice): clearBytes = 5, encryptedBytes = 55
        val plainSample = ByteArray(16 + 8 + 5 + 55) { idx -> (idx * 5 + 11).toByte() }
        val iv16 = testIv8.copyOf(16)
        val subsamples = listOf(
            CencMp4Decrypter.SubsampleEntry(clearBytes = 16, encryptedBytes = 0L),
            CencMp4Decrypter.SubsampleEntry(clearBytes = 8, encryptedBytes = 0L),
            CencMp4Decrypter.SubsampleEntry(clearBytes = 5, encryptedBytes = 55L)
        )

        val encryptedSample = plainSample.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), IvParameterSpec(iv16))
        val encIdr = cipher.doFinal(plainSample, 29, 55)
        System.arraycopy(encIdr, 0, encryptedSample, 29, 55)

        val working = encryptedSample.copyOf()
        CencMp4Decrypter.decryptSampleInPlace(
            sampleBytes = working,
            keyBytes = testKey,
            iv16 = iv16,
            subsamples = subsamples
        )
        assertArrayEquals("IDR slice after clear-only SPS/PPS subsamples must be decrypted", plainSample, working)
    }

    @Test
    fun testSubsampleDecryptInPlaceAndArbitraryRangeSlice() {
        val plainSample = ByteArray(89) { idx -> (idx + 1).toByte() }
        val iv16 = testIv8.copyOf(16)
        val subsamples = listOf(
            CencMp4Decrypter.SubsampleEntry(clearBytes = 5, encryptedBytes = 35L),
            CencMp4Decrypter.SubsampleEntry(clearBytes = 4, encryptedBytes = 45L)
        )

        val encryptedSample = plainSample.copyOf()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), IvParameterSpec(iv16))
        val encPart1 = cipher.update(plainSample, 5, 35)
        System.arraycopy(encPart1, 0, encryptedSample, 5, 35)
        val encPart2 = cipher.doFinal(plainSample, 44, 45)
        System.arraycopy(encPart2, 0, encryptedSample, 44, 45)

        // 1. 验证完整 Sample 原地解密
        val fullCopy = encryptedSample.copyOf()
        CencMp4Decrypter.decryptSampleInPlace(
            sampleBytes = fullCopy,
            keyBytes = testKey,
            iv16 = iv16,
            subsamples = subsamples
        )
        assertArrayEquals(plainSample, fullCopy)

        // 2. 验证任意非 16 字节对齐的 HTTP Range 切片原地解密
        val baseFileOffset = 1000L
        val cencSample = CencMp4Decrypter.CencSample(
            sampleIndex = 0,
            fileOffset = baseFileOffset,
            size = 89L,
            iv = iv16,
            subsamples = subsamples
        )

        for (sliceStart in listOf(0, 3, 5, 17, 39, 41, 44, 59)) {
            for (sliceEnd in listOf(22, 40, 44, 68, 89)) {
                if (sliceEnd <= sliceStart) continue
                val sliceLen = sliceEnd - sliceStart
                val rangeChunk = encryptedSample.copyOfRange(sliceStart, sliceEnd)
                CencMp4Decrypter.decryptStreamRangeInPlace(
                    buffer = rangeChunk,
                    chunkLength = sliceLen,
                    chunkFileOffset = baseFileOffset + sliceStart,
                    keyBytes = testKey,
                    samples = listOf(cencSample)
                )
                val expectedSlice = plainSample.copyOfRange(sliceStart, sliceEnd)
                assertArrayEquals("Mismatch for slice [$sliceStart, $sliceEnd)", expectedSlice, rangeChunk)
            }
        }
    }

    @Test
    fun testParseFmp4BoxesAndPatchSchemeBoxesWithFrmaHevc() {
        val fmp4Bytes = buildSyntheticCencFmp4(sampleSize = 48, iv8 = testIv8, originalFormat = "hev1")
        val meta = CencMp4Decrypter.parseCencMetadata(fmp4Bytes)

        assertEquals(8, meta.defaultIvSize)
        assertEquals("11223344556677889900aabbccddeeff", meta.defaultKidHex)
        assertEquals("hev1", meta.originalVideoFormat)
        assertEquals(1, meta.samples.size)
        assertEquals(48L, meta.samples[0].size)

        // 验证 encv / sinf 被原地伪装为 frma 指定的 hev1 / free
        val patched = fmp4Bytes.copyOf()
        CencMp4Decrypter.patchSchemeBoxesToClearInPlace(
            buffer = patched,
            videoFormat = meta.originalVideoFormat,
            audioFormat = meta.originalAudioFormat
        )
        val ascii = String(patched, Charsets.ISO_8859_1)
        assertTrue(ascii.contains("hev1"))
        assertTrue(!ascii.contains("encv"))
    }

    @Test
    fun testParseStandardNonFragmentedStblMp4AndStreamingDecrypter() {
        // 构造一个标准非分片 MP4 (moov -> trak -> mdia -> minf -> stbl [stsz + stsc + stco + senc]) + mdat
        val plainSample1 = ByteArray(40) { (it + 10).toByte() }
        val plainSample2 = ByteArray(56) { (it + 60).toByte() }
        val iv1 = CencMp4Decrypter.hexToBytes("0101010101010101").copyOf(16)
        val iv2 = CencMp4Decrypter.hexToBytes("0202020202020202").copyOf(16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), IvParameterSpec(iv1))
        val encSample1 = cipher.doFinal(plainSample1)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), IvParameterSpec(iv2))
        val encSample2 = cipher.doFinal(plainSample2)

        val stszBox = makeBox("stsz", ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version/flags
            putInt(0) // uniform size = 0 (variable)
            putInt(2) // sample_count = 2
            putInt(40)
            putInt(56)
        }.array())

        val stscBox = makeBox("stsc", ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1) // 1 entry
            putInt(1) // first_chunk = 1
            putInt(2) // samples_per_chunk = 2
            putInt(1) // sample_description_index = 1
        }.array())

        val sencBox = makeBox("senc", ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(2) // 2 samples
            put(iv1, 0, 8)
            put(iv2, 0, 8)
        }.array())

        // 先占位 stco 以便计算 moov 总长
        val placeholderStco = makeBox("stco", ByteBuffer.allocate(12).array())
        val tempMoov = makeBox("moov", makeBox("trak", makeBox("mdia", makeBox("minf", makeBox("stbl", stszBox + stscBox + placeholderStco + sencBox)))))
        val mdatPayloadOffset = tempMoov.size + 8 // mdat header is 8 bytes

        val realStco = makeBox("stco", ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1) // 1 chunk
            putInt(mdatPayloadOffset)
        }.array())
        val moovBox = makeBox("moov", makeBox("trak", makeBox("mdia", makeBox("minf", makeBox("stbl", stszBox + stscBox + realStco + sencBox)))))
        val mdatBox = makeBox("mdat", encSample1 + encSample2)
        val fullMp4 = moovBox + mdatBox

        val meta = CencMp4Decrypter.parseCencMetadata(fullMp4)
        assertEquals(2, meta.samples.size)
        assertEquals(mdatPayloadOffset.toLong(), meta.samples[0].fileOffset)
        assertEquals(40L, meta.samples[0].size)
        assertEquals((mdatPayloadOffset + 40).toLong(), meta.samples[1].fileOffset)
        assertEquals(56L, meta.samples[1].size)

        // 使用 CencDecryptingInputStream（小缓冲 32B 池模拟多分片流式读取）验证流式解密
        val smallPool = ByteBufferPool(bufferSize = 32, maxPoolSize = 2)
        val startOffset = 10L // 模拟非 0 起始 Range 请求
        val stream = LocalStreamGateway.CencDecryptingInputStream(
            upstream = ByteArrayInputStream(fullMp4, startOffset.toInt(), fullMp4.size - startOffset.toInt()),
            startFileOffset = startOffset,
            keyBytes = testKey,
            meta = meta,
            bufferPool = smallPool
        )
        val decryptedStreamBytes = stream.use { it.readBytes() }
        val decryptedMdatPayload = decryptedStreamBytes.copyOfRange(
            mdatPayloadOffset - startOffset.toInt(),
            decryptedStreamBytes.size
        )
        assertArrayEquals(plainSample1 + plainSample2, decryptedMdatPayload)
        assertEquals(2, smallPool.availableCount())
    }

    private fun buildSyntheticCencFmp4(sampleSize: Int, iv8: ByteArray, originalFormat: String = "avc1"): ByteArray {
        val kid = CencMp4Decrypter.hexToBytes("11223344556677889900aabbccddeeff")
        val tencPayload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            put(0)
            put(0)
            put(1)
            put(8)
            put(kid)
        }.array()
        val frmaBox = makeBox("frma", originalFormat.toByteArray(Charsets.ISO_8859_1))
        val tencBox = makeBox("tenc", tencPayload)
        val schiBox = makeBox("schi", tencBox)
        val sinfBox = makeBox("sinf", frmaBox + schiBox)
        val moovBox = makeBox("moov", makeBox("trak", sinfBox))

        val trunPayload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x000200)
            putInt(1)
            putInt(sampleSize)
        }.array()
        val trunBox = makeBox("trun", trunPayload)

        val sencPayload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1)
            put(iv8)
        }.array()
        val sencBox = makeBox("senc", sencPayload)

        val trafBox = makeBox("traf", trunBox + sencBox)
        val moofBox = makeBox("moof", trafBox)
        val mdatBox = makeBox("mdat", ByteArray(sampleSize) { 0x42 })
        val encvMarker = makeBox("encv", ByteArray(8))

        val bos = ByteArrayOutputStream()
        bos.write(moovBox)
        bos.write(encvMarker)
        bos.write(moofBox)
        bos.write(mdatBox)
        return bos.toByteArray()
    }

    private fun makeBox(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(total)
        bb.put(type.toByteArray(Charsets.ISO_8859_1), 0, 4)
        bb.put(payload)
        return bb.array()
    }
}
