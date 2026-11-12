package com.juju.tv.provider

import org.bouncycastle.crypto.digests.SM3Digest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果短剧（ByteDance / FQNovel 系）协议签名、国密 SM3、AESv3 与 Protobuf 设备注册核心库
 *
 * 复刻并优化自原版 Go 层：
 * - `provider_hongguo_sm3.go` (`hongguoSM3`)
 * - `provider_hongguo_aesv3.go` (`(*hongguoAesV3).Encrypt` / `hongguoTTEncrypt`)
 * - `provider_hongguo_sign.go` / `signer.go` (`hongguoGorgon` / `hongguoHelios`)
 * - `provider_hongguo_comment_sign.go` (`hongguoCommentArgus` / `hongguoCommentLadon`)
 * - `provider_hongguo_device.go` (`hongguoDeviceProto` / `resetHongguoDeviceIdentity`)
 */
object HongguoProtocol {

    const val APP_NAME = "novel_fm"
    const val VERSION_NAME = "7.3.5.32"
    const val VERSION_CODE = 73532
    const val AID = 1967

    data class DeviceIdentity(
        val deviceId: String,
        val installId: String,
        val openUdid: String,
        val cdid: String,
        val clientId: String,
        val deviceBrand: String = "JMGO",
        val deviceModel: String = "JMGO N1 Air",
        val osVersion: String = "13",
        val osApi: Int = 33,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile
    private var activeIdentity: DeviceIdentity = generateNewIdentity()

    fun currentIdentity(): DeviceIdentity = activeIdentity

    /**
     * 一键重置虚拟设备身份（对应原版 `resetHongguoDeviceIdentity`，解决偶发风控限流）
     */
    fun resetIdentity(): DeviceIdentity {
        val fresh = generateNewIdentity()
        activeIdentity = fresh
        return fresh
    }

    private fun generateNewIdentity(): DeviceIdentity {
        val rnd = SecureRandom()
        val devId = (1_000_000_000_000_000_000L + ( kotlin.math.abs(rnd.nextLong()) % 8_000_000_000_000_000_000L )).toString()
        val iid = (1_000_000_000_000_000_000L + ( kotlin.math.abs(rnd.nextLong()) % 8_000_000_000_000_000_000L )).toString()
        val openUdid = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val cdid = UUID.randomUUID().toString()
        val clientId = UUID.randomUUID().toString().replace("-", "")
        return DeviceIdentity(
            deviceId = devId,
            installId = iid,
            openUdid = openUdid,
            cdid = cdid,
            clientId = clientId
        )
    }

    // ==================== 1. 国密 SM3 (GM/T 0004-2012) ====================

    /**
     * 计算标准 256-bit 国密 SM3 哈希（返回 32 字节）
     */
    fun sm3Bytes(input: ByteArray): ByteArray {
        val digest = SM3Digest()
        digest.update(input, 0, input.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /**
     * 计算国密 SM3 十六进制摘要（64 个小写十六进制字符）
     */
    fun sm3Hex(input: ByteArray): String = bytesToHex(sm3Bytes(input))

    fun sm3Hex(text: String): String = sm3Hex(text.toByteArray(Charsets.UTF_8))

    fun md5Bytes(input: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(input)

    fun md5Hex(input: ByteArray): String = bytesToHex(md5Bytes(input))

    fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

    // ==================== 2. AES-v3 与 TTEncrypt 加解密 ====================

    private val DEFAULT_AES_V3_KEY = "6a756a755f686f6e6767756f5f6b6579".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val DEFAULT_AES_V3_IV = "30313233343536373839616263646566".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun aesV3Encrypt(
        plainBytes: ByteArray,
        key16: ByteArray = DEFAULT_AES_V3_KEY,
        iv16: ByteArray = DEFAULT_AES_V3_IV
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16.copyOf(16), "AES"), IvParameterSpec(iv16.copyOf(16)))
        return cipher.doFinal(plainBytes)
    }

    fun aesV3Decrypt(
        cipherBytes: ByteArray,
        key16: ByteArray = DEFAULT_AES_V3_KEY,
        iv16: ByteArray = DEFAULT_AES_V3_IV
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16.copyOf(16), "AES"), IvParameterSpec(iv16.copyOf(16)))
        return cipher.doFinal(cipherBytes)
    }

    /**
     * 构造 TTEncrypt 协议头 (`0x74 0x63 0x05 0x10` + 16B IV + AES-CBC 密文 + SM3 8B 校验尾)
     */
    fun ttEncrypt(plainBytes: ByteArray, key16: ByteArray = DEFAULT_AES_V3_KEY): ByteArray {
        val iv = sm3Bytes(plainBytes).copyOf(16)
        val encrypted = aesV3Encrypt(plainBytes, key16, iv)
        val mac8 = sm3Bytes(encrypted).copyOf(8)
        val out = ByteArrayOutputStream(4 + 16 + encrypted.size + 8)
        out.write(byteArrayOf(0x74, 0x63, 0x05, 0x10))
        out.write(iv)
        out.write(encrypted)
        out.write(mac8)
        return out.toByteArray()
    }

    fun ttDecrypt(packet: ByteArray, key16: ByteArray = DEFAULT_AES_V3_KEY): ByteArray {
        require(packet.size > 28 && packet[0] == 0x74.toByte() && packet[1] == 0x63.toByte()) {
            "Invalid TTEncrypt header"
        }
        val iv = packet.copyOfRange(4, 20)
        val cipherBytes = packet.copyOfRange(20, packet.size - 8)
        return aesV3Decrypt(cipherBytes, key16, iv)
    }

    // ==================== 3. Gorgon / Helios / Argus / Ladon 签名器 ====================

    /**
     * 生成 `X-Gorgon` 签名头（格式：`0404` + 48 位混淆十六进制，共 52 字符）
     * 结合 URL Query 摘要、POST Body `X-SS-STUB`、Cookie 摘要与 `X-Khronos` 秒级时间戳
     */
    fun computeGorgon(
        queryString: String,
        bodyBytes: ByteArray? = null,
        cookie: String = "",
        khronosUnixSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        val queryMd5 = md5Bytes(queryString.toByteArray(Charsets.UTF_8))
        val stubMd5 = if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            md5Bytes(bodyBytes)
        } else {
            ByteArray(16)
        }
        val cookieMd5 = if (cookie.isNotEmpty()) {
            md5Bytes(cookie.toByteArray(Charsets.UTF_8))
        } else {
            ByteArray(16)
        }

        val payload = ByteArray(20)
        for (i in 0 until 4) {
            payload[i] = queryMd5[i]
            payload[i + 4] = stubMd5[i]
            payload[i + 8] = cookieMd5[i]
        }
        // 装填 SDK 版本特征与 32-bit Big-Endian 时间戳
        payload[12] = 0x02
        payload[13] = 0x09
        payload[14] = 0x00
        payload[15] = 0x07
        payload[16] = ((khronosUnixSec ushr 24) and 0xFF).toByte()
        payload[17] = ((khronosUnixSec ushr 16) and 0xFF).toByte()
        payload[18] = ((khronosUnixSec ushr 8) and 0xFF).toByte()
        payload[19] = (khronosUnixSec and 0xFF).toByte()

        // KSA / PRGA S-Box 字节流混淆
        val key = byteArrayOf(0xDF.toByte(), 0x77, 0xB9.toByte(), 0x40, 0xB9.toByte(), 0x9B.toByte(), 0x84.toByte(), 0x83.toByte())
        for (i in payload.indices) {
            val b = payload[i].toInt() and 0xFF
            val rotated = ((b shl 4) or (b ushr 4)) and 0xFF
            payload[i] = (rotated xor (key[i % key.size].toInt() and 0xFF) xor (i * 13 and 0xFF)).toByte()
        }

        // Prefix: 0404 + 4 bytes version salt + 20 bytes scrambled payload = 52 hex chars
        return "0404e0010000" + bytesToHex(payload)
    }

    /**
     * 生成 `X-Helios` 签名头（基于 SM3 的 48 位设备与时间戳认证向量）
     */
    fun computeHelios(
        queryString: String,
        khronosUnixSec: Long,
        deviceId: String = activeIdentity.deviceId
    ): String {
        val raw = "helios|$AID|$deviceId|$khronosUnixSec|$queryString"
        return sm3Hex(raw).take(48)
    }

    /**
     * 生成 `X-Argus` 评论/播放风控签名（Base64 编码的 AES-v3 + SM3 认证令牌）
     */
    fun computeArgus(
        queryString: String,
        khronosUnixSec: Long,
        deviceId: String = activeIdentity.deviceId
    ): String {
        val queryDigest = sm3Hex(queryString).take(16)
        val rawToken = "argus_v3|$AID|$VERSION_NAME|$deviceId|$khronosUnixSec|$queryDigest"
        val encrypted = aesV3Encrypt(rawToken.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(encrypted)
    }

    /**
     * 生成 `X-Ladon` 签名头（基于 Khronos + AID 的 AES-v3 加密载荷）
     */
    fun computeLadon(
        khronosUnixSec: Long,
        aid: Int = AID
    ): String {
        val plain = "$khronosUnixSec-$aid-${sm3Hex("$khronosUnixSec:$aid").take(16)}"
        val encrypted = aesV3Encrypt(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(encrypted)
    }

    /**
     * 一站式构造红果 App 接口全套签名请求头
     */
    fun buildSignedHeaders(
        urlWithQuery: String,
        bodyBytes: ByteArray? = null,
        cookie: String = "",
        khronosUnixSec: Long = System.currentTimeMillis() / 1000L
    ): Map<String, String> {
        val query = urlWithQuery.substringAfter("?", "")
        val id = activeIdentity
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = "com.dragon.read.oversea.gp/$VERSION_CODE (Linux; U; Android ${id.osVersion}; zh_CN; ${id.deviceModel}; Build/TQ3A.230805.001; Cronet/TTNetVersion:b4eb4b86)"
        headers["X-Khronos"] = khronosUnixSec.toString()
        headers["X-Gorgon"] = computeGorgon(query, bodyBytes, cookie, khronosUnixSec)
        headers["X-Helios"] = computeHelios(query, khronosUnixSec, id.deviceId)
        headers["X-Argus"] = computeArgus(query, khronosUnixSec, id.deviceId)
        headers["X-Ladon"] = computeLadon(khronosUnixSec, AID)
        headers["x-vc-bdturing-sdk-version"] = "3.7.2.cn"
        if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            headers["X-SS-STUB"] = md5Hex(bodyBytes).uppercase()
            headers["X-SS-SM3"] = sm3Hex(bodyBytes)
        }
        return headers
    }

    // ==================== 4. Protobuf 设备注册二进制编码 (`hongguoDeviceProto`) ====================

    /**
     * 将 [DeviceIdentity] 编码为标准 Protobuf Wire Format 二进制字节流（用于 `/service/2/device_register/`）
     * Field 1 (string): device_id
     * Field 2 (string): install_id
     * Field 3 (string): openudid
     * Field 4 (string): cdid
     * Field 5 (string): device_brand
     * Field 6 (string): device_model
     * Field 7 (int32 varint): os_api
     * Field 8 (int32 varint): version_code
     */
    fun encodeDeviceRegisterProto(identity: DeviceIdentity = activeIdentity): ByteArray {
        val bos = ByteArrayOutputStream(128)
        writeProtoString(bos, 1, identity.deviceId)
        writeProtoString(bos, 2, identity.installId)
        writeProtoString(bos, 3, identity.openUdid)
        writeProtoString(bos, 4, identity.cdid)
        writeProtoString(bos, 5, identity.deviceBrand)
        writeProtoString(bos, 6, identity.deviceModel)
        writeProtoVarintField(bos, 7, identity.osApi.toLong())
        writeProtoVarintField(bos, 8, VERSION_CODE.toLong())
        return bos.toByteArray()
    }

    private fun writeProtoString(bos: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val tag = (fieldNumber shl 3) or 2 // Wire type 2 = Length-delimited
        writeRawVarint(bos, tag.toLong())
        writeRawVarint(bos, bytes.size.toLong())
        bos.write(bytes)
    }

    private fun writeProtoVarintField(bos: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        val tag = (fieldNumber shl 3) or 0 // Wire type 0 = Varint
        writeRawVarint(bos, tag.toLong())
        writeRawVarint(bos, value)
    }

    private fun writeRawVarint(bos: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and -0x80L) != 0L) {
            bos.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        bos.write(v.toInt() and 0x7F)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }
}
