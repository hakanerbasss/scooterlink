package com.wizaicorp.scooterlink

/** Kartlar ve komutlar (F2 Pro'da gözlenen değerler). */
object Dev {
    const val CTRL = 0x20   // motor kontrolcüsü
    const val BLE = 0x21    // BLE kartı
    const val BMS = 0x22    // batarya
    const val PHONE = 0x3E  // biz (resmi uygulama da kendini böyle tanıtıyor)
}

object Cmd {
    const val READ = 0x01
    const val WRITE_NO_REPLY = 0x03
    const val READ_ACK = 0x04
    const val NOTIFY = 0x21
    const val INIT = 0x5B
    const val SET_PWD = 0x5C
    const val AUTH = 0x5D
}

class Pkt(val src: Int, val dst: Int, val cmd: Int, val idx: Int, val data: ByteArray = ByteArray(0)) {
    fun pack(): ByteArray =
        byteArrayOf(0x5A, 0xA5.toByte(), data.size.toByte(), src.toByte(), dst.toByte(), cmd.toByte(), idx.toByte()) + data

    override fun toString() = "%02X→%02X cmd=%02X idx=%02X [%s]".format(src, dst, cmd, idx, hex(data))

    companion object {
        fun unpack(f: ByteArray): Pkt? {
            if (f.size < 7 || f[0] != 0x5A.toByte() || f[1] != 0xA5.toByte()) return null
            if (f.size != 7 + (f[2].toInt() and 0xFF)) return null
            return Pkt(f[3].toInt() and 0xFF, f[4].toInt() and 0xFF, f[5].toInt() and 0xFF, f[6].toInt() and 0xFF, f.copyOfRange(7, f.size))
        }
    }
}

/**
 * Bağlantı başına anahtar + sayaç durumu.
 *  - NAME anahtarı = SHA1(ad, FW_DATA)      → INIT (zayıf mod)
 *  - BLE  anahtarı = SHA1(ad, bleData)      → SET_PWD (zayıf mod)
 *  - APP  anahtarı = SHA1(şifre, bleData)   → AUTH ve sonrası (AES modu, sayaç 2'den başlar)
 * [name] taramada görülen reklam adı olmalı (ör. NAGT....).
 */
class NbSession(private val name: ByteArray) {
    enum class K { NAME, BLE, APP }

    var bleData: ByteArray? = null; private set
    var serial: String? = null; private set
    var password: ByteArray? = null
    var txKey = K.NAME
    var counter = 0

    private fun key(k: K): ByteArray? {
        val bd = bleData; val pw = password
        return when (k) {
            K.NAME -> NbCrypto.deriveKey(name, NbCrypto.FW_DATA)
            K.BLE -> bd?.let { NbCrypto.deriveKey(name, it) }
            K.APP -> if (bd != null && pw != null) NbCrypto.deriveKey(pw, bd) else null
        }
    }

    @Synchronized
    fun seal(p: Pkt): ByteArray {
        val k = checkNotNull(key(txKey)) { "$txKey anahtarı henüz yok" }
        return if (counter == 0) {
            NbCrypto.sealWeak(p.pack(), k)
        } else {
            check(counter < 0xFFFF) { "sayaç doldu, yeniden bağlan" }
            counter += 1
            NbCrypto.sealAes(p.pack(), k, checkNotNull(bleData), counter)
        }
    }

    /** Gelen çerçeveyi mümkün olan anahtarlarla açar; sağlama/MAC hangisinin doğru olduğuna karar verir. */
    @Synchronized
    fun open(frame: ByteArray): Pkt? {
        if (frame.size < 10) return null
        val c = NbCrypto.wireCounter(frame)
        val order = listOf(txKey) + K.values().filter { it != txKey }
        for (label in order) {
            val k = key(label) ?: continue
            val plain = if (c != 0) bleData?.let { NbCrypto.openAes(frame, k, it) } else NbCrypto.openWeak(frame, k)
            if (plain != null) {
                val p = Pkt.unpack(plain) ?: return null
                if (c != 0) counter = c
                if (p.cmd == Cmd.INIT && p.src == Dev.BLE && p.data.size >= 16) {
                    bleData = p.data.copyOfRange(0, 16)
                    serial = String(p.data, 16, p.data.size - 16, Charsets.US_ASCII)
                    txKey = K.BLE
                }
                return p
            }
        }
        return null
    }
}
