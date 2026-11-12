package com.juju.tv

import com.juju.tv.provider.HongguoProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HongguoProtocolTest {

    @Test
    fun testSm3StandardVector() {
        // GM/T 0004-2012 国家密码管理局标准测试向量：SM3("abc")
        val digest = HongguoProtocol.sm3Hex("abc")
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            digest
        )
    }

    @Test
    fun testAesV3AndTtEncryptRoundTrip() {
        val original = "JuJuTV_Hongguo_Playlet_Payload_2026".toByteArray(Charsets.UTF_8)
        val cipherBytes = HongguoProtocol.aesV3Encrypt(original)
        val decrypted = HongguoProtocol.aesV3Decrypt(cipherBytes)
        assertArrayEquals(original, decrypted)

        val ttPacket = HongguoProtocol.ttEncrypt(original)
        assertEquals(0x74.toByte(), ttPacket[0])
        assertEquals(0x63.toByte(), ttPacket[1])
        val ttRestored = HongguoProtocol.ttDecrypt(ttPacket)
        assertArrayEquals(original, ttRestored)
    }

    @Test
    fun testGorgonHeliosArgusLadonSignaturesAndDeviceProto() {
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/multi-detail/v1/?book_id=1001&aid=1967"
        val body = """{"book_id":"1001"}""".toByteArray(Charsets.UTF_8)
        val headers = HongguoProtocol.buildSignedHeaders(url, body, khronosUnixSec = 1760000000L)

        val gorgon = headers["X-Gorgon"] ?: ""
        assertTrue(gorgon.startsWith("0404"))
        assertEquals(52, gorgon.length)
        assertEquals("1760000000", headers["X-Khronos"])
        assertEquals(48, headers["X-Helios"]?.length)
        assertTrue(!headers["X-Argus"].isNullOrBlank())
        assertTrue(!headers["X-Ladon"].isNullOrBlank())
        assertEquals(64, headers["X-SS-SM3"]?.length)

        // 验证 Protobuf 设备注册二进制编码与重置身份
        val oldId = HongguoProtocol.currentIdentity()
        val protoBytes = HongguoProtocol.encodeDeviceRegisterProto(oldId)
        assertTrue(protoBytes.size > 40)

        val newId = HongguoProtocol.resetIdentity()
        assertNotEquals(oldId.deviceId, newId.deviceId)
    }
}
