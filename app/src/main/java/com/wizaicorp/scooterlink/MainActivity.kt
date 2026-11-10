package com.wizaicorp.scooterlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val NORDIC_UART_SVC = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
private const val NUS_TX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // telefon -> scooter
private const val NUS_RX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // scooter -> telefon
private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
private const val WRITE_CHUNK = 20

data class Found(val name: String, val address: String, val rssi: Int)

fun hex(b: ByteArray?): String = b?.joinToString(" ") { "%02X".format(it) } ?: "(boş)"

private fun le(b: ByteArray?, signed: Boolean = false): Long? {
    if (b == null || b.isEmpty()) return null
    var v = 0L
    for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xFF)
    if (signed && b.size == 2 && v >= 0x8000) v -= 0x10000
    return v
}

/** Seri önekinden model (F2 serisi firmware araştırmasına göre). */
fun modelOf(serial: String): String {
    val p = serial.take(4).uppercase()
    return when (p) {
        "NAGR", "NAGV", "NAGU", "NAGT", "NAGS" -> "Ninebot F2 Pro"
        "NAGF", "NAGK", "NAGJ", "NAGH", "NAGG" -> "Ninebot F2 Plus"
        "NAGA", "NAGE", "NAGD", "NAGC", "NAGB" -> "Ninebot F2"
        else -> "bilinmeyen model ($p)"
    }
}

/** Gösterge panelindeki sıralı alanlar. */
val TELE_KEYS = listOf("Güç", "Hız", "Batarya", "Mod", "Voltaj", "Akım", "Sıcaklık", "Toplam km", "KERS", "TCS", "Yürüme modu", "Hücreler")

@SuppressLint("MissingPermission")
class BleController(private val ctx: Context) {
    val devices = mutableStateMapOf<String, Found>()
    val lines = mutableStateListOf<String>()
    val tele = mutableStateMapOf<String, String>()
    val diagnostics = mutableStateMapOf<String, String>()
    var scanning by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var authed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var needPair by mutableStateOf(false)
    var serialText by mutableStateOf("")
    var powerOn by mutableStateOf<Boolean?>(null)
    var experimentalResult by mutableStateOf("Henüz denenmedi")
    // Oturumsuz (INIT/şifre öncesi) ham komut: topluluk yöntemi nRF Connect ile, resmi uygulama
    // bağlı değilken, şifreli oturum AÇILMADAN yazıyor. Bekleyen komut bağlantı kurulunca gönderilir.
    @Volatile private var pendingRaw: ByteArray? = null
    @Volatile private var pendingRawLabel = ""
    val canRaw: Boolean get() = lastAddress != null

    private val raw = HashMap<String, BluetoothDevice>()
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var connectedName = ""
    private var lastAddress: String? = null

    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var notifyBuf = ArrayList<Byte>()
    private var writeDeferred: CompletableDeferred<Boolean>? = null

    private var session: NbSession? = null
    private var replies = Channel<Pkt>(Channel.UNLIMITED)
    private val gate = Mutex()
    private var pollJob: Job? = null
    @Volatile private var pairDeadline = 0L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs = ctx.getSharedPreferences("creds", Context.MODE_PRIVATE)

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    init {
        say(if (NbCrypto.selfTest()) "Kripto öz-testi: OK" else "Kripto öz-testi: HATA!")
    }

    /** Panel + tanılama + log: kopyalanacak tam rapor. */
    fun report(): String = buildString {
        if (serialText.isNotEmpty()) appendLine("Cihaz: $serialText (${modelOf(serialText)})")
        if (tele.isNotEmpty()) {
            appendLine("--- Panel ---")
            for (k in TELE_KEYS) tele[k]?.let { appendLine("$k: $it") }
        }
        if (diagnostics.isNotEmpty()) {
            appendLine("--- Tanılama ---")
            for ((k, v) in diagnostics) appendLine("$k: $v")
        }
        appendLine("--- Log ---")
        append(lines.joinToString("\n"))
    }

    fun say(s: String) { main.post { lines.add(s) } }
    private fun ui(f: () -> Unit) { main.post(f) }

    // ---------- Tarama / bağlantı ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: d.name ?: "?"
            raw[d.address] = d
            devices[d.address] = Found(name, d.address, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            say("Tarama hatası: $errorCode"); scanning = false
        }
    }

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { say("Bluetooth kapalı. Açıp tekrar dene."); return }
        devices.clear(); raw.clear()
        a.bluetoothLeScanner.startScan(scanCb)
        scanning = true
        say("Tarama başladı.")
    }

    fun stopScan() {
        if (scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
            scanning = false
        }
    }

    fun connect(address: String) {
        stopScan()
        val dev = raw[address] ?: return
        lastAddress = address
        // Anahtar türetimi reklamdaki adla yapılır (ör. NAGT....).
        connectedName = devices[address]?.name?.takeIf { it != "?" } ?: dev.name ?: ""
        resetLink()
        say("Bağlanılıyor: $connectedName ($address)")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun resetLink() {
        pollJob?.cancel(); pollJob = null
        gatt?.close(); gatt = null
        txChar = null; rxChar = null
        notifyBuf = ArrayList()
        writeDeferred?.cancel(); writeDeferred = null
        replies.close(); replies = Channel(Channel.UNLIMITED)
        session = null
        ui { connected = false; authed = false; needPair = false; powerOn = null; tele.clear(); diagnostics.clear() }
    }

    fun disconnect() {
        gatt?.disconnect()
        resetLink()
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                say("Bağlandı, servisler aranıyor...")
                g.discoverServices()
            } else {
                say("Bağlantı koptu (status=$status).")
                pollJob?.cancel()
                ui { connected = false; authed = false }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(NORDIC_UART_SVC))
            if (svc == null) { say("Nordic UART servisi yok — bu bir Ninebot değil gibi."); return }
            txChar = svc.getCharacteristic(UUID.fromString(NUS_TX))
            val rx = svc.getCharacteristic(UUID.fromString(NUS_RX))
            rxChar = rx
            if (rx == null) { say("Bildirim karakteristiği yok."); return }
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(UUID.fromString(CCCD)) ?: run { say("CCCD yok."); return }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != 0) { say("Bildirim açma hatası: $status"); return }
            ui { connected = true }
            val raw = pendingRaw
            if (raw != null) {
                pendingRaw = null
                val label = pendingRawLabel
                scope.launch { runBusy { runSessionlessRaw(raw, label) } }
            } else {
                scope.launch { runBusy { startSession() } }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDeferred?.complete(status == 0)
            writeDeferred = null
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleIncoming(c.value ?: ByteArray(0))
        }
    }

    @Synchronized
    private fun handleIncoming(value: ByteArray) {
        notifyBuf.addAll(value.toList())
        // Senkron kaybı olursa 5A A5'e kadar at
        while (notifyBuf.size >= 2 && !(notifyBuf[0] == 0x5A.toByte() && notifyBuf[1] == 0xA5.toByte())) notifyBuf.removeAt(0)
        while (notifyBuf.size >= 3) {
            val total = (notifyBuf[2].toInt() and 0xFF) + 13
            if (notifyBuf.size < total) break
            val frame = notifyBuf.subList(0, total).toByteArray()
            notifyBuf = ArrayList(notifyBuf.subList(total, notifyBuf.size))
            val p = session?.open(frame)
            when {
                p == null -> say("Çözülemeyen çerçeve: ${hex(frame)}")
                p.cmd == Cmd.NOTIFY && p.src == Dev.BLE -> onNotify(p)
                else -> replies.trySend(p)
            }
        }
    }

    /** Scooter'ın kendiliğinden gönderdiği değişiklik bildirimi: [02, reg, değer_le16] */
    private fun onNotify(p: Pkt) {
        if (p.data.size >= 4 && p.data[0].toInt() == 2 && (p.data[1].toInt() and 0xFF) == 0x4D) {
            val on = p.data[2].toInt() != 0
            ui { powerOn = on; tele["Güç"] = if (on) "Açık" else "Kapalı" }
            say("Bildirim: güç ${if (on) "AÇILDI" else "KAPANDI"}")
            if (on && pairDeadline > 0) {
                pairDeadline = System.currentTimeMillis() + 60_000
                say("Scooter açıldı (bu basış onay sayılmadı). Ekran yanıp sönüp bip ederken güç düğmesine TEKRAR bir kez kısa bas.")
            }
        } else say("Bildirim: $p")
    }

    // ---------- İstek / cevap ----------

    private suspend fun writeChunk(chunk: ByteArray): Boolean {
        val g = gatt ?: return false
        val tx = txChar ?: return false
        val d = CompletableDeferred<Boolean>()
        writeDeferred = d
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            tx.value = chunk
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(tx)
        }
        return withTimeoutOrNull(3000) { d.await() } ?: false
    }

    private suspend fun writeFrame(data: ByteArray): Boolean {
        var off = 0
        while (off < data.size) {
            val n = minOf(WRITE_CHUNK, data.size - off)
            if (!writeChunk(data.copyOfRange(off, off + n))) return false
            off += n
        }
        return true
    }

    /** Tek seferde tek istek. Eşleşen cevabı döndürür, yoksa null. */
    private suspend fun request(p: Pkt, timeoutMs: Long, match: (Pkt) -> Boolean): Pkt? = gate.withLock {
        val s = session ?: return@withLock null
        while (replies.tryReceive().isSuccess) { /* bayat cevapları at */ }
        if (!writeFrame(s.seal(p))) { say("Yazma başarısız."); return@withLock null }
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val r = replies.receive()
                if (match(r)) return@withTimeoutOrNull r
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    /** Cevap beklenmeyen yazma (güç komutları). */
    private suspend fun sendNoReply(p: Pkt): Boolean = gate.withLock {
        val s = session ?: return@withLock false
        writeFrame(s.seal(p))
    }

    private suspend fun read(dev: Int, idx: Int, len: Int = 2): ByteArray? =
        request(Pkt(Dev.PHONE, dev, Cmd.READ, idx, byteArrayOf(len.toByte())), 1500) {
            it.src == dev && it.cmd == Cmd.READ_ACK && it.idx == idx
        }?.data

    private suspend fun runBusy(block: suspend () -> Unit) {
        ui { busy = true }
        try { block() } catch (e: Exception) { say("Hata: ${e.message}") } finally { ui { busy = false } }
    }

    // ---------- El sıkışma ----------

    private fun loadPassword(serial: String): ByteArray? =
        prefs.getString("pw_$serial", null)?.let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }

    private fun savePassword(serial: String, pw: ByteArray) {
        prefs.edit().putString("pw_$serial", pw.joinToString("") { "%02X".format(it) }).apply()
    }

    private suspend fun startSession() {
        if (connectedName.isEmpty()) { say("Cihaz adı bilinmiyor; yeniden tara."); return }
        val s = NbSession(connectedName.toByteArray(Charsets.US_ASCII))
        session = s
        say("INIT gönderiliyor...")
        val r = request(Pkt(Dev.PHONE, Dev.BLE, Cmd.INIT, 0), 5000) { it.src == Dev.BLE && it.cmd == Cmd.INIT }
        if (r == null) { say("INIT: cevap yok."); return }
        val serial = s.serial ?: run { say("INIT cevabında seri yok."); return }
        ui { serialText = serial }
        say("INIT tamam ✓  Seri: $serial  (${modelOf(serial)})")
        val saved = loadPassword(serial)
        if (saved == null) {
            say("Bu telefonda kayıtlı şifre yok. Kontrol için 'Eşleştir'e bas.")
            ui { needPair = true }
            return
        }
        login(s, serial, saved)
    }

    private suspend fun login(s: NbSession, serial: String, pw: ByteArray): Boolean {
        s.password = pw
        s.txKey = NbSession.K.APP
        s.counter = 1 // ilk AES çerçevesi sayaç 2 ile gider
        val r = request(Pkt(Dev.PHONE, Dev.BLE, Cmd.AUTH, 0, serial.toByteArray(Charsets.US_ASCII)), 4000) {
            it.src == Dev.BLE && it.cmd == Cmd.AUTH && it.idx == 1
        }
        if (r == null) {
            say("Giriş reddedildi. Scooter başka bir telefonla/resmi uygulamayla yeniden eşleşmiş olabilir. 'Eşleştir'i kullan.")
            ui { needPair = true }
            return false
        }
        say("OTURUM AÇILDI ✓ — veriler okunuyor.")
        ui { authed = true; needPair = false }
        startPolling()
        return true
    }

    fun startPairing() {
        if (busy) return
        scope.launch { runBusy { pair() } }
    }

    private suspend fun pair() {
        val s = session ?: run { say("Önce bağlan."); return }
        val serial = s.serial ?: run { say("Önce INIT tamamlanmalı."); return }
        val pw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        s.txKey = NbSession.K.BLE
        s.counter = 0
        // Scooter "kabul edildi" cevabını YENİ şifreyle (APP anahtarı, AES) şifreliyor: çözebilmek için aday olarak ver.
        s.password = pw
        // Cevap kaybolsa bile şifre elde kalsın (onaylanmazsa giriş zaten reddedilir ve yeniden eşleştirme istenir).
        savePassword(serial, pw)
        say("Scooter AÇIK olmalı. Ekran yanıp sönüp bip edince güç düğmesine bir kez KISA bas (basılı tutma). 60 sn bekliyorum...")
        pairDeadline = System.currentTimeMillis() + 60_000
        var told = false
        try {
            while (System.currentTimeMillis() < pairDeadline) {
                s.txKey = NbSession.K.BLE
                s.counter = 0 // SET_PWD her zaman zayıf modda gider
                val r = request(Pkt(Dev.PHONE, Dev.BLE, Cmd.SET_PWD, 0, pw), 3000) { it.src == Dev.BLE && it.cmd == Cmd.SET_PWD }
                if (r != null && r.idx == 1) {
                    savePassword(serial, pw)
                    say("Eşleşme kabul edildi ✓ Şifre bu telefona kaydedildi. Yeniden bağlanılıyor...")
                    val addr = lastAddress
                    main.post { disconnect() }
                    delay(2000)
                    if (addr != null) main.post { connect(addr) }
                    return
                }
                if (r != null && !told) { told = true; say("Scooter onay bekliyor — güç düğmesine kısa bas.") }
                delay(2000)
            }
        } finally { pairDeadline = 0L }
        say("Eşleşme zaman aşımı (düğmeye basılmadı mı?).")
    }

    // ---------- Gösterge paneli ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive && authed) {
                try { pollOnce(tick % 5 == 0) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; say("Okuma hatası: ${e.message}") }
                tick++
                delay(800)
            }
        }
    }

    private fun put(k: String, v: String?) { if (v != null) ui { tele[k] = v } }

    private suspend fun pollOnce(slow: Boolean) {
        val pw = le(read(Dev.BLE, 0x4D))
        if (pw != null) { ui { powerOn = pw != 0L }; put("Güç", if (pw != 0L) "Açık" else "Kapalı") }
        if (pw == 0L) return // kapalıyken kontrolcü ve batarya cevap vermez
        put("Hız", le(read(Dev.CTRL, 0x26), signed = true)?.let { "%.1f km/s".format(it / 10.0) })
        put("Batarya", le(read(Dev.CTRL, 0x22))?.let { "%$it" })
        put("Mod", le(read(Dev.CTRL, 0x75))?.let { when (it) { 0L -> "Normal"; 1L -> "Eco"; 2L -> "Spor"; else -> "? ($it)" } })
        put("Voltaj", le(read(Dev.BMS, 0x34))?.let { "%.2f V".format(it / 100.0) })
        put("Akım", le(read(Dev.BMS, 0x33), signed = true)?.let { "%.2f A".format(it / 100.0) + if (it < 0) " (şarj)" else "" })
        if (slow) {
            put("Sıcaklık", read(Dev.BMS, 0x35)?.let { b -> b.joinToString(" / ") { "${(it.toInt() and 0xFF) - 20}°C" } })
            put("Toplam km", le(read(Dev.CTRL, 0x29, 4))?.let { "%.1f km".format(it / 1000.0) })
            put("KERS", le(read(Dev.CTRL, 0x7B))?.let { when (it) { 0L -> "Zayıf"; 1L -> "Orta"; 2L -> "Güçlü"; else -> "? ($it)" } })
            put("TCS", le(read(Dev.CTRL, 0xF3))?.let { if (it != 0L) "Açık" else "Kapalı" })
            put("Yürüme modu", le(read(Dev.CTRL, 0x77))?.let { if (it != 0L) "Açık" else "Kapalı" })
            read(Dev.BMS, 0x40, 20)?.let { b ->
                val mv = (0 until b.size / 2).map { le(b.copyOfRange(it * 2, it * 2 + 2)) ?: 0L }
                if (mv.isNotEmpty()) put("Hücreler", "${mv.min()}–${mv.max()} mV (fark ${mv.max() - mv.min()})")
            }
        }
    }


    // -------- F2 Pro kontrolcü tanılama: TAMAMEN SALT OKUNUR --------
    // 0x1A/0x1B/0x1D/0x10 adresleri eski Ninebot protokolünde belgeli.
    // F2 Pro bazı sürümlerde kayıtları yanıtlamayabilir; yanıt yoksa tahmin üretme.
    // Kesinlikle WRITE_NO_REPLY veya firmware güncelleme komutu gönderilmez.
    private fun firmwareVersion(bytes: ByteArray?): String {
        val raw = le(bytes)?.toInt() ?: return "Yanıt yok"
        if (bytes?.size != 2 || raw == 0 || raw == 0xFFFF) return "Bilinmeyen (${hex(bytes)})"
        return "${raw ushr 8}.${(raw ushr 4) and 0x0F}.${raw and 0x0F} (ham: ${hex(bytes)})"
    }

    fun readControllerDiagnostics() {
        if (busy || !authed) return
        scope.launch {
            runBusy {
                // Telemetri sorguları ile karışmadan tek seferlik tanılama yap.
                pollJob?.cancelAndJoin()
                pollJob = null
                ui { diagnostics.clear() }
                say("F2 Pro tanılama başladı (salt okunur).")
                try {
                    val fw = firmwareVersion(read(Dev.CTRL, 0x1A))
                    val controllerSerialBytes = read(Dev.CTRL, 0x10, 14)
                    val controllerSerial = controllerSerialBytes
                        ?.takeIf { it.isNotEmpty() && it.all { c -> (c.toInt() and 0xFF) == 0 || (c.toInt() and 0xFF) in 32..126 } }
                        ?.let { String(it, Charsets.US_ASCII).trimEnd('\u0000', ' ') }
                        ?.takeIf { it.isNotEmpty() }
                        ?: "Yanıt yok / desteklenmiyor"
                    val error = le(read(Dev.CTRL, 0x1B))?.toString() ?: "Yanıt yok"
                    val flags = read(Dev.CTRL, 0x1D)?.let { hex(it) } ?: "Yanıt yok"
                    val bleVersion = firmwareVersion(read(Dev.BLE, 0x1A))
                    val bmsRawLegacy = read(Dev.BMS, 0x1A)?.let { hex(it) } ?: "Yanıt yok"
                    val bmsRawFirmware = read(Dev.BMS, 0x17)?.let { hex(it) } ?: "Yanıt yok"
                    val results = linkedMapOf(
                        "Kontrolcü (DRV)" to fw,
                        "Kontrolcü seri" to controllerSerial,
                        "Ekran/BLE sürüm" to bleVersion,
                        "BMS 0x1A (ham)" to bmsRawLegacy,
                        "BMS 0x17 (ham / sürüm adayı)" to bmsRawFirmware,
                        "Hata kodu" to error,
                        "Durum bayrakları (ham)" to flags,
                        "Bölge yazma desteği" to "Doğrulanmadı — komut gönderilmedi"
                    )
                    ui { diagnostics.putAll(results) }
                    results.forEach { (key, value) -> say("$key: $value") }
                    say("Tanılama tamamlandı. Hiçbir ayar değiştirilmedi.")
                } finally {
                    if (authed) startPolling()
                }
            }
        }
    }

    /**
     * Stok F2 Pro'ya ait belgelerde hız/bölge parametreleri farklı adreslerle
     * anılabiliyor. Burada yalnızca okuma (Cmd.READ) yapılır.
     * Sonuçlar herhangi bir hız/bölge kontrolünün mevcut olduğunu kanıtlamaz.
     */
    fun probeSpeedRegisters() {
        if (busy || !authed) return
        scope.launch {
            runBusy {
                pollJob?.cancelAndJoin()
                pollJob = null
                say("Aday hız kayıtları salt okunur taranıyor...")
                try {
                    val candidates = listOf(
                        0x48 to "Bilinmeyen / nominal hız adayı",
                        0x67 to "Bilinmeyen parametre",
                        0x68 to "Bilinmeyen / sürüm adayı",
                        0x73 to "Bilinmeyen / mod sınırı adayı",
                        0x74 to "Bilinmeyen / mod sınırı adayı",
                        0x93 to "Bilinmeyen / hız sınırı adayı",
                        0xF1 to "Bilinmeyen / uygulama azami hızı adayı"
                    )
                    for ((idx, label) in candidates) {
                        val bytes = read(Dev.CTRL, idx)
                        val addr = "0x" + idx.toString(16).uppercase().padStart(2, '0')
                        val result = if (bytes == null || bytes.isEmpty()) {
                            "Yanıt yok / desteklenmiyor"
                        } else {
                            "Ham: ${hex(bytes)} | LE değer: ${le(bytes)} | Ölçek doğrulanmadı"
                        }
                        val key = "$addr — $label"
                        ui { diagnostics[key] = result }
                        say("$key: $result")
                    }
                    say("Aday hız kaydı taraması bitti. HİÇBİR YAZMA KOMUTU GÖNDERİLMEDİ.")
                } finally {
                    if (authed) startPolling()
                }
            }
        }
    }


    // F2 ailesi için topluluk tarafından raporlanan 55 AB "F2Scooter_1/0"
    // doğrudan NUS_TX bildirimi. Bu, F2 Pro DRV 1.8.9 üzerinde DOĞRULANMAMIŞTIR.
    // Seri numarası, bölge kaydı ve kontrolcü firmware'i değiştirilmiyor.
    // Yalnızca kullanıcının açık onayı ve scooter HAREKETSİZKEN 1 kez gönderilir.
    private suspend fun writeRawSpeedPacket(data: ByteArray): Boolean = gate.withLock {
        val g = gatt ?: return@withLock false
        val tx = txChar ?: return@withLock false
        if ((tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            say("Doğrudan BLE komutu iptal: yazma-yanıtsız NUS_TX desteklenmiyor.")
            return@withLock false
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(tx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = data
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(tx)
        }
        // BLE yazma-yanıtsızda ACK yok; Android'in kuyruk kabulü başarı kanıtı değildir.
        delay(500)
        ok
    }

    private fun speedCmd(enable: Boolean) = byteArrayOf(
        0x55, 0xAB.toByte(), 0x46, 0x32, 0x53, 0x63, 0x6F,
        0x6F, 0x74, 0x65, 0x72, 0x5F,
        if (enable) 0x31 else 0x30
    )

    /**
     * Topluluk yöntemini birebir taklit eder: temiz bağlantı, INIT/şifre YOK, tek no-response yazım.
     * Ardından bağlantı kesilip normal oturum açılır ve 0xF1 okunur. Hız artışı yalnızca
     * scooter göstergesinden/tekerlek havadayken doğrulanır.
     */
    fun sendSessionlessSpeedCommand(enable: Boolean) {
        val addr = lastAddress ?: run { say("Önce bir cihaza bağlan (adres yok)."); return }
        if (busy) return
        pendingRaw = speedCmd(enable)
        pendingRawLabel = if (enable) "Oturumsuz hız açma" else "Oturumsuz geri alma"
        say("$pendingRawLabel: temiz bağlantı kuruluyor (INIT gönderilmeyecek)...")
        gatt?.disconnect()
        resetLink()
        scope.launch {
            delay(1500)
            connect(addr)
        }
    }

    private suspend fun runSessionlessRaw(data: ByteArray, label: String) {
        try {
            delay(800) // CCCD sonrası cihazın sakinleşmesi için
            val queued = writeRawSpeedPacket(data)
            say("$label: ham paket ${hex(data)} gönderildi, kuyruk=${queued}.")
            delay(1500)
            ui { experimentalResult = "$label: gönderildi (kuyruk=$queued). Doğrulama için tekerlek havadayken göstergeden maksimum hızı oku." }
        } finally {
            val addr = lastAddress
            gatt?.disconnect()
            resetLink()
            if (addr != null) {
                say("Normal oturum 2 sn sonra açılacak; 0xF1 tekrar okunacak.")
                scope.launch { delay(2000); connect(addr) }
            }
        }
    }

    fun sendExperimentalSpeedCommand(enable: Boolean) {
        if (busy || !connected || !authed) return
        scope.launch {
            runBusy {
                pollJob?.cancelAndJoin()
                pollJob = null
                try {
                    val speedRaw = le(read(Dev.CTRL, 0x26), signed = true)
                    if (speedRaw != 0L || powerOn != true) {
                        val why = "İşlem iptal: motor hızı sıfır okunmalı ve scooter açık olmalı. Hız ham=$speedRaw."
                        ui { experimentalResult = why }
                        say(why)
                        return@runBusy
                    }
                    if (!serialText.uppercase().startsWith("NAG")) {
                        say("İşlem iptal: cihaz Ninebot F2 ailesi olarak tanınmadı.")
                        return@runBusy
                    }
                    val before = le(read(Dev.CTRL, 0xF1))
                    val cmd = speedCmd(enable)
                    val label = if (enable) "Deneysel hız açma" else "Normal moda geri alma"
                    say("$label: tek BLE ham komutu gönderiliyor; DRV 1.8.9 desteği belirsiz.")
                    val queued = writeRawSpeedPacket(cmd)
                    if (!queued) {
                        ui { experimentalResult = "Bluetooth komutu kuyruğa alınamadı / desteklenmiyor." }
                        say(experimentalResult)
                        return@runBusy
                    }
                    delay(900)
                    val after = le(read(Dev.CTRL, 0xF1))
                    val result = "$label: BLE kuyruğa alındı. F1 önce=$before, sonra=$after. " +
                        "Kontrolcünün komutu kabul ettiği veya hızın değiştiği doğrulanmadı."
                    ui { experimentalResult = result }
                    say(result)
                } finally {
                    if (authed) startPolling()
                }
            }
        }
    }

    // ---------- Güç kontrolü (resmi uygulamanın kilit/aç komutları) ----------

    /** Ayar yaz → geri oku → doğrula. Değişmezse tekrar göndermez. Ayarlar duruyorken değiştirilir. */
    fun setCtrl(label: String, idx: Int, value: Int) {
        if (busy || !authed) return
        scope.launch {
            runBusy {
                val sp = le(read(Dev.CTRL, 0x26), signed = true)
                if (sp == null || sp != 0L) { say("$label: sadece dururken değiştirilir (hız=${sp ?: "okunamadı"})."); return@runBusy }
                val before = le(read(Dev.CTRL, idx))
                if (before == value.toLong()) { say("$label zaten $value."); return@runBusy }
                say("$label: $before → $value yazılıyor...")
                sendNoReply(Pkt(Dev.PHONE, Dev.CTRL, Cmd.WRITE_NO_REPLY, idx, byteArrayOf(value.toByte(), 0)))
                delay(1200)
                val after = le(read(Dev.CTRL, idx))
                say(if (after == value.toLong()) "$label değişti ✓ ($after)" else "$label değişmedi (okunan: $after). Bu kayıt yazmayı kabul etmiyor olabilir.")
            }
        }
    }

    fun setPower(on: Boolean) {
        if (busy || !authed) return
        scope.launch {
            runBusy {
                if (!on) {
                    // Güvenlik kilidi: hız okunamazsa ya da 0 değilse kapatma
                    val sp = le(read(Dev.CTRL, 0x26), signed = true)
                    if (sp == null || sp != 0L) { say("Kapatma reddedildi: scooter duruyor mu bilinmiyor (hız=${sp ?: "okunamadı"})."); return@runBusy }
                }
                val p = if (on) Pkt(Dev.PHONE, Dev.BLE, Cmd.WRITE_NO_REPLY, 0x1E, byteArrayOf(1, 0))
                else Pkt(Dev.PHONE, Dev.CTRL, Cmd.WRITE_NO_REPLY, 0x79, byteArrayOf(1, 0))
                say(if (on) "Güç AÇ komutu gönderildi (3-4 sn sürer)..." else "Güç KAPAT komutu gönderildi (3-4 sn sürer)...")
                sendNoReply(p)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

@Composable
fun Screen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ble = remember { BleController(context.applicationContext) }
    var experimentalChoice by remember { mutableStateOf<Boolean?>(null) }
    var sessionless by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { ble.stopScan(); ble.disconnect() }
    }

    if (experimentalChoice != null) {
        val enable = experimentalChoice == true
        AlertDialog(
            onDismissRequest = { experimentalChoice = null },
            title = { Text(if (enable) "Deneysel hız komutu" else "Normal moda geri alma") },
            text = {
                Text(
                    "Bu komut F2 Pro DRV 1.8.9 üzerinde doğrulanmadı. " +
                    "Bluetooth yazımı kabul edilse bile hızın değiştiği anlamına gelmez. " +
                    "Scooter tamamen sabit, frenleri hazır ve tekerler yerde yük altında değilken " +
                    "deneyin. Seri numarası ve firmware değiştirilmeyecek. " +
                    "Hatalı/uyumsuz komut cihaz işlevini etkileyebilir. Devam edilsin mi?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (sessionless) ble.sendSessionlessSpeedCommand(enable)
                    else ble.sendExperimentalSpeedCommand(enable)
                    experimentalChoice = null
                }) { Text("Komutu bir kez gönder") }
            },
            dismissButton = {
                OutlinedButton(onClick = { experimentalChoice = null }) { Text("Vazgeç") }
            }
        )
    }

    val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) ble.startScan() else ble.say("İzin verilmedi.")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ScooterLink", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (ble.scanning) ble.stopScan() else launcher.launch(perms) }) {
                Text(if (ble.scanning) "Durdur" else "Tara")
            }
            OutlinedButton(onClick = { ble.disconnect(); ble.say("Bağlantı kesildi.") }) { Text("Kes") }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ble.report())) }) { Text("Logu kopyala") }
        }

        if (ble.connected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            ble.authed -> "Bağlı ✓  ${ble.serialText}  ${modelOf(ble.serialText)}"
                            ble.serialText.isNotEmpty() -> "Tanındı: ${ble.serialText}  ${modelOf(ble.serialText)}"
                            else -> "Bağlı, tanınıyor..."
                        },
                        fontSize = 13.sp
                    )
                    if (ble.authed) {
                        Text(ble.tele["Hız"] ?: "– km/s", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        for (k in TELE_KEYS) {
                            if (k == "Hız") continue
                            val v = ble.tele[k] ?: continue
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, fontSize = 15.sp)
                                Text(v, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("F2 Pro kontrolcü tanılama", fontWeight = FontWeight.Bold)
                                Text(
                                    "Yalnızca sürüm ve durum kayıtlarını okur. " +
                                        "Bölge veya hız sınırı değiştirme komutu göndermez.",
                                    fontSize = 12.sp
                                )
                                Button(
                                    onClick = { ble.readControllerDiagnostics() },
                                    enabled = !ble.busy
                                ) { Text("Kontrolcüyü analiz et") }
                                OutlinedButton(
                                    onClick = { ble.probeSpeedRegisters() },
                                    enabled = !ble.busy
                                ) { Text("Hız kayıtlarını tara (salt okunur)") }
                                for ((label, value) in ble.diagnostics) {
                                    Column {
                                        Text(label, fontSize = 12.sp)
                                        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Text(
                                    "Sonuçları paylaşmak için yukarıdaki 'Logu kopyala' düğmesini kullan. " +
                                        "Seri numarası kişisel cihaz bilgisidir; yayımlamadan önce gizle.",
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Deneysel F2 Pro BLE hız modu", fontWeight = FontWeight.Bold)
                                Text(
                                    "Topluluk yöntemi; DRV 1.8.9 uyumluluğu bilinmiyor. " +
                                    "Mod değiştirme komutundan farklıdır. Tek seferlik ham BLE yazımıdır.",
                                    fontSize = 12.sp
                                )
                                Button(
                                    onClick = { sessionless = false; experimentalChoice = true },
                                    enabled = !ble.busy && ble.powerOn == true
                                ) { Text("Deneysel hız açma komutu") }
                                Button(
                                    onClick = { sessionless = true; experimentalChoice = true },
                                    enabled = !ble.busy && ble.canRaw
                                ) { Text("Oturumsuz gönder (nRF Connect gibi)") }
                                OutlinedButton(
                                    onClick = { sessionless = true; experimentalChoice = false },
                                    enabled = !ble.busy && ble.canRaw
                                ) { Text("Oturumsuz geri alma") }
                                OutlinedButton(
                                    onClick = { experimentalChoice = false },
                                    enabled = !ble.busy && ble.powerOn == true
                                ) { Text("Normal moda geri alma komutu") }
                                Text(ble.experimentalResult, fontSize = 11.sp)
                            }
                        }
                        Text("Sürüş modu:", fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Eco" to 1, "Normal" to 0, "Spor" to 2).forEach { (n, v) ->
                                OutlinedButton(onClick = { ble.setCtrl("Mod", 0x75, v) }, enabled = !ble.busy) { Text(n) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { ble.setCtrl("Yürüme modu", 0x77, 1) }, enabled = !ble.busy) { Text("Yürüme aç") }
                            OutlinedButton(onClick = { ble.setCtrl("Yürüme modu", 0x77, 0) }, enabled = !ble.busy) { Text("Yürüme kapa") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { ble.setPower(true) }, enabled = !ble.busy && ble.powerOn != true) { Text("Gücü aç") }
                            OutlinedButton(onClick = { ble.setPower(false) }, enabled = !ble.busy && ble.powerOn != false) { Text("Gücü kapat") }
                        }
                    }
                    if (ble.needPair) {
                        Text(
                            "Eşleştirme: scooter tek bir telefonu hatırlar. Bu telefonu eşleştirirsen resmi uygulama " +
                                "bir sonraki girişte yeniden eşleşme ister (geri alınabilir).",
                            fontSize = 12.sp
                        )
                        Button(onClick = { ble.startPairing() }, enabled = !ble.busy) {
                            Text(if (ble.busy) "Bekleniyor..." else "Eşleştir")
                        }
                    }
                }
            }
        }

        if (!ble.connected) {
            Text("Cihazlar (dokununca bağlanır):")
            for (d in ble.devices.values.sortedByDescending { it.rssi }) {
                val hint = d.name.uppercase().let { it.startsWith("NAG") || "NINEBOT" in it || "SEGWAY" in it }
                Text(
                    "${if (hint) "★ " else ""}${d.name}  ${d.address}  ${d.rssi} dBm",
                    modifier = Modifier.fillMaxWidth().clickable { ble.connect(d.address) }.padding(vertical = 6.dp),
                    fontSize = 14.sp
                )
            }
        }

        Text("Log (en yeni üstte, son 150 satır):", fontSize = 12.sp)
        for (line in ble.lines.toList().asReversed().take(150)) Text(line, fontSize = 11.sp)
    }
}
