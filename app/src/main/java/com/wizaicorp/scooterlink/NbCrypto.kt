package com.wizaicorp.scooterlink

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Segway-Ninebot BLE şifreleme katmanı (açık belge: nootnooot.codeberg.page/segway-ninebot-ble).
 *
 * Çerçeve (havada): 5A A5 | LEN | gövde (LEN+4 bayt, şifreli: SRC DST CMD IDX DATA) | kuyruk (6 bayt)
 *  - Zayıf mod (sayaç 0): gövde XOR AES(key, FW_DATA); kuyruk = 00 00 crcLo crcHi 00 00
 *  - AES modu (sayaç >= 2): AES-CTR + 4 bayt CBC-MAC; kuyruk = MAC(4) + sayaç(2, big-endian)
 *
 * F2 Pro'da gerçek çerçeveyle doğrulandı: zayıf modda anahtar akışı AES(key, FW_DATA)'dır
 * (eski sürümümüz yanlışlıkla AES(key, 0) kullanıyordu).
 */
object NbCrypto {

    val FW_DATA = byteArrayOf(
        0x97.toByte(), 0xCF.toByte(), 0xB8.toByte(), 0x02,
        0x84.toByte(), 0x41, 0x43, 0xDE.toByte(),
        0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D
    )

    /** SHA1(pad16(k1) || pad16(k2)) ilk 16 bayt. */
    fun deriveKey(k1: ByteArray, k2: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(k1.copyOf(16) + k2.copyOf(16)).copyOf(16)

    private fun aes(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block)
    }

    private fun block(prefix: Int, counter: Int, bleData: ByteArray, last: Int): ByteArray {
        val b = ByteArray(16)
        b[0] = prefix.toByte()
        b[1] = (counter ushr 24).toByte(); b[2] = (counter ushr 16).toByte()
        b[3] = (counter ushr 8).toByte(); b[4] = counter.toByte()
        System.arraycopy(bleData, 0, b, 5, 8)
        b[15] = last.toByte()
        return b
    }

    private fun xor16(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun crc(body: ByteArray): Int = body.sumOf { it.toInt() and 0xFF }.inv() and 0xFFFF

    private fun ctr(body: ByteArray, key: ByteArray, counter: Int, bleData: ByteArray): ByteArray {
        val out = ByteArray(body.size)
        var off = 0; var i = 1
        while (off < body.size) {
            val ks = aes(key, block(0x01, counter, bleData, i))
            for (j in 0 until minOf(16, body.size - off)) out[off + j] = (body[off + j].toInt() xor ks[j].toInt()).toByte()
            off += 16; i++
        }
        return out
    }

    private fun mac(key: ByteArray, header: ByteArray, body: ByteArray, counter: Int, bleData: ByteArray): ByteArray {
        var x = aes(key, block(0x59, counter, bleData, body.size))
        x = aes(key, xor16(x, header.copyOf(16)))
        var off = 0
        while (off < body.size) {
            x = aes(key, xor16(x, body.copyOfRange(off, minOf(off + 16, body.size)).copyOf(16)))
            off += 16
        }
        val s = aes(key, block(0x01, counter, bleData, 0))
        return ByteArray(4) { (x[it].toInt() xor s[it].toInt()).toByte() }
    }

    /** plain = 5A A5 LEN SRC DST CMD IDX DATA.. */
    fun sealWeak(plain: ByteArray, key: ByteArray): ByteArray {
        val body = plain.copyOfRange(3, plain.size)
        val ks = aes(key, FW_DATA)
        val enc = ByteArray(body.size) { (body[it].toInt() xor ks[it % 16].toInt()).toByte() }
        val c = crc(body)
        return plain.copyOfRange(0, 3) + enc + byteArrayOf(0, 0, c.toByte(), (c ushr 8).toByte(), 0, 0)
    }

    fun sealAes(plain: ByteArray, key: ByteArray, bleData: ByteArray, counter: Int): ByteArray {
        val header = plain.copyOfRange(0, 3)
        val body = plain.copyOfRange(3, plain.size)
        return header + ctr(body, key, counter, bleData) + mac(key, header, body, counter, bleData) +
            byteArrayOf((counter ushr 8).toByte(), counter.toByte())
    }

    fun wireCounter(frame: ByteArray): Int =
        ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or (frame[frame.size - 1].toInt() and 0xFF)

    /** Sağlama tutmazsa null (yanlış anahtar). Dönüş: düz çerçeve (5A A5 LEN ...). */
    fun openWeak(frame: ByteArray, key: ByteArray): ByteArray? {
        if (frame.size < 10) return null
        val n = frame.size - 9
        val ks = aes(key, FW_DATA)
        val body = ByteArray(n) { (frame[3 + it].toInt() xor ks[it % 16].toInt()).toByte() }
        val c = crc(body)
        if (frame[3 + n + 2] != c.toByte() || frame[3 + n + 3] != (c ushr 8).toByte()) return null
        return frame.copyOfRange(0, 3) + body
    }

    /** MAC doğrulanmazsa null. */
    fun openAes(frame: ByteArray, key: ByteArray, bleData: ByteArray): ByteArray? {
        if (frame.size < 10) return null
        val n = frame.size - 9
        val counter = wireCounter(frame)
        val body = ctr(frame.copyOfRange(3, 3 + n), key, counter, bleData)
        val tag = mac(key, frame.copyOfRange(0, 3), body, counter, bleData)
        var diff = 0
        for (i in 0 until 4) diff = diff or (tag[i].toInt() xor frame[3 + n + i].toInt())
        if (diff != 0) return null
        return frame.copyOfRange(0, 3) + body
    }

    /** Açılışta çalışan küçük öz-test: şifrele/çöz turu her iki modda da tutmalı. */
    fun selfTest(): Boolean {
        val name = "NAGTX0000X0000".toByteArray()
        val k = deriveKey(name, FW_DATA)
        val plain = byteArrayOf(0x5A, 0xA5.toByte(), 2, 0x3E, 0x21, 0x01, 0x22, 0x02, 0x00)
        val w = openWeak(sealWeak(plain, k), k) ?: return false
        val bd = ByteArray(16) { it.toByte() }
        val a = openAes(sealAes(plain, k, bd, 7), k, bd) ?: return false
        val tampered = sealAes(plain, k, bd, 7).also { it[4] = (it[4] + 1).toByte() }
        return w.contentEquals(plain) && a.contentEquals(plain) && openAes(tampered, k, bd) == null
    }
}
