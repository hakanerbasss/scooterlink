package com.wizaicorp.scooterlink

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Segway-Ninebot "Encryption2" BLE protokolünün, açık belgelere göre
 * (nootnooot.codeberg.page/segway-ninebot-ble) yeniden üretilmiş kripto katmanı.
 *
 * Bu kod kendi cihazınla aynı dili konuşmak içindir: eşleşme (SET_PWD fazı)
 * cihaz üzerinde fiziksel bir düğmeye/onaya dayanır, başka bir cihaza
 * yetkisiz erişim sağlamaz.
 */
object NbCrypto {

    /** Gen2 cihazlarda PRE_COMM'da key2 olarak kullanılan sabit; Gen3'te key2 = sıfır. */
    val FW_DATA = byteArrayOf(
        0x97.toByte(), 0xCF.toByte(), 0xB8.toByte(), 0x02,
        0x84.toByte(), 0x41, 0x43, 0xDE.toByte(),
        0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D
    )

    private fun pad16(b: ByteArray?): ByteArray {
        val out = ByteArray(16)
        if (b != null) System.arraycopy(b, 0, out, 0, minOf(16, b.size))
        return out
    }

    fun deriveKey(key1: ByteArray, key2: ByteArray?): ByteArray {
        val k1 = pad16(key1)
        val k2 = pad16(key2)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(k1 + k2)
        return sha1.copyOf(16)
    }

    private fun ecb(key: ByteArray, block16: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block16)
    }

    private fun xor(a: ByteArray, b: ByteArray, n: Int): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    fun buildNonce(counter: Int, auth: ByteArray): ByteArray {
        val n = ByteArray(13)
        n[0] = (counter shr 24).toByte(); n[1] = (counter shr 16).toByte()
        n[2] = (counter shr 8).toByte(); n[3] = counter.toByte()
        System.arraycopy(auth, 0, n, 4, 8)
        n[12] = 0
        return n
    }

    /**
     * header = [5A A5 LEN] (3 bayt, şifrelenmez)
     * payload = [BT_ID TARGET CMD INDEX ...DATA] (LEN+4 bayt, şifrelenir)
     */
    fun encryptSn(key: ByteArray, header: ByteArray, payload: ByteArray, counter: Int, auth: ByteArray): ByteArray {
        val nonce = buildNonce(counter, auth)
        val payloadLen = payload.size

        var x = ecb(key, byteArrayOf(0x59.toByte()) + nonce + byteArrayOf(0x00, payloadLen.toByte()))
        val aad = header.copyOf(3) + ByteArray(13)
        x = ecb(key, xor(x, aad, 16))
        var off = 0
        while (off < payload.size) {
            val chunk = ByteArray(16)
            val n = minOf(16, payload.size - off)
            System.arraycopy(payload, off, chunk, 0, n)
            x = ecb(key, xor(x, chunk, 16))
            off += 16
        }
        val rawTag = x.copyOf(4)

        val ct = ByteArray(payload.size)
        var i = 1; off = 0
        while (off < payload.size) {
            val ai = byteArrayOf(0x01) + nonce + byteArrayOf(0x00, i.toByte())
            val ks = ecb(key, ai)
            val n = minOf(16, payload.size - off)
            for (j in 0 until n) ct[off + j] = (payload[off + j].toInt() xor ks[j].toInt()).toByte()
            off += 16; i++
        }

        val a0 = byteArrayOf(0x01) + nonce + byteArrayOf(0x00, 0x00)
        val a0ks = ecb(key, a0)
        val encTag = xor(rawTag, a0ks, 4)

        val tail = byteArrayOf((counter shr 8).toByte(), counter.toByte())
        return header + ct + encTag + tail
    }

    /** Tam alınan şifreli çerçeveyi (başlık dahil) çözer. Dönüş: Pair(payload, counter) ya da null (MAC/format hatası). */
    fun decryptSn(key: ByteArray, frame: ByteArray, auth: ByteArray): Pair<ByteArray, Int>? {
        if (frame.size < 9) return null
        val len = frame[2].toInt() and 0xFF
        val total = len + 13
        if (frame.size < total) return null
        val ctStart = 3
        val ctLen = len + 4
        val cipher = frame.copyOfRange(ctStart, ctStart + ctLen)
        val mac = frame.copyOfRange(ctStart + ctLen, ctStart + ctLen + 4)
        val counter = ((frame[ctStart + ctLen + 4].toInt() and 0xFF) shl 8) or (frame[ctStart + ctLen + 5].toInt() and 0xFF)

        val nonce = buildNonce(counter, auth)

        val payload = ByteArray(cipher.size)
        var i = 1; var off = 0
        while (off < cipher.size) {
            val ai = byteArrayOf(0x01) + nonce + byteArrayOf(0x00, i.toByte())
            val ks = ecb(key, ai)
            val n = minOf(16, cipher.size - off)
            for (j in 0 until n) payload[off + j] = (cipher[off + j].toInt() xor ks[j].toInt()).toByte()
            off += 16; i++
        }

        val header = byteArrayOf(frame[0], frame[1], frame[2])
        var x = ecb(key, byteArrayOf(0x59.toByte()) + nonce + byteArrayOf(0x00, len.toByte()))
        val aad = header + ByteArray(13)
        x = ecb(key, xor(x, aad, 16))
        off = 0
        while (off < payload.size) {
            val chunk = ByteArray(16)
            val n = minOf(16, payload.size - off)
            System.arraycopy(payload, off, chunk, 0, n)
            x = ecb(key, xor(x, chunk, 16))
            off += 16
        }
        val rawTag = x.copyOf(4)
        val a0 = byteArrayOf(0x01) + nonce + byteArrayOf(0x00, 0x00)
        val a0ks = ecb(key, a0)
        val expectedMac = xor(rawTag, a0ks, 4)
        if (!expectedMac.contentEquals(mac)) return null

        return Pair(payload, counter)
    }

    fun encryptNonSn(key: ByteArray, header: ByteArray, payload: ByteArray): ByteArray {
        var checksum = 0
        for (b in payload) checksum += (b.toInt() and 0xFF)
        checksum = checksum.inv() and 0xFFFF

        val ks = ecb(key, ByteArray(16))
        val ct = ByteArray(payload.size)
        for (j in payload.indices) ct[j] = (payload[j].toInt() xor ks[j % 16].toInt()).toByte()

        val tail = byteArrayOf(0, 0, (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte(), 0, 0)
        return header + ct + tail
    }

    fun decryptNonSn(key: ByteArray, frame: ByteArray): ByteArray? {
        if (frame.size < 3) return null
        val len = frame[2].toInt() and 0xFF
        val total = len + 13
        if (frame.size < total) return null
        val ctStart = 3
        val ctLen = len + 4
        val cipher = frame.copyOfRange(ctStart, ctStart + ctLen)
        val ks = ecb(key, ByteArray(16))
        val payload = ByteArray(cipher.size)
        for (j in cipher.indices) payload[j] = (cipher[j].toInt() xor ks[j % 16].toInt()).toByte()
        return payload
    }

    /** SET_PWD için oturum şifresi üretimi (java.util.Random, resmi istemciyle aynı PRNG). */
    fun generatePassword(authParam: ByteArray, timeMs: Long = System.currentTimeMillis()): ByteArray {
        var seedValue = 0
        for (idx in authParam.indices) {
            val signedByte = authParam[idx].toInt() // Kotlin Byte -> Int, işaretli genişletme
            val shift = (idx % 8) * 8
            seedValue += (signedByte shl (shift and 31))
        }
        val seed = timeMs + seedValue.toLong()
        val rng = java.util.Random(seed)
        val randomBytes = ByteArray(16)
        rng.nextBytes(randomBytes)
        return MessageDigest.getInstance("SHA-256").digest(randomBytes).copyOf(16)
    }
}
