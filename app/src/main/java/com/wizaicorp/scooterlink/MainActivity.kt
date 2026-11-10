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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var scanning by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var authed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var needPair by mutableStateOf(false)
    var serialText by mutableStateOf("")
    var powerOn by mutableStateOf<Boolean?>(null)

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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs = ctx.getSharedPreferences("creds", Context.MODE_PRIVATE)

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    init {
        say(if (NbCrypto.selfTest()) "Kripto öz-testi: OK" else "Kripto öz-testi: HATA!")
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
        ui { connected = false; authed = false; needPair = false; powerOn = null; tele.clear() }
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
            scope.launch { runBusy { startSession() } }
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
        say("ŞİMDİ scooter'ın güç düğmesine bir kez kısa bas (60 sn bekliyorum)...")
        repeat(30) {
            val r = request(Pkt(Dev.PHONE, Dev.BLE, Cmd.SET_PWD, 0, pw), 2000) { it.src == Dev.BLE && it.cmd == Cmd.SET_PWD }
            if (r != null && r.idx == 1) {
                savePassword(serial, pw)
                say("Eşleşme kabul edildi ✓ Şifre bu telefona kaydedildi. Yeniden bağlanılıyor...")
                val addr = lastAddress
                main.post { disconnect() }
                delay(2000)
                if (addr != null) main.post { connect(addr) }
                return
            }
            if (r != null) say("Bekleniyor — düğmeye bas...")
        }
        say("Eşleşme zaman aşımı (düğmeye basılmadı mı?).")
    }

    // ---------- Gösterge paneli ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive && authed) {
                try { pollOnce(tick % 5 == 0) } catch (e: Exception) { say("Okuma hatası: ${e.message}") }
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

    // ---------- Güç kontrolü (resmi uygulamanın kilit/aç komutları) ----------

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

    DisposableEffect(Unit) {
        onDispose { ble.stopScan(); ble.disconnect() }
    }

    val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) ble.startScan() else ble.say("İzin verilmedi.")
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ScooterLink", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (ble.scanning) ble.stopScan() else launcher.launch(perms) }) {
                Text(if (ble.scanning) "Durdur" else "Tara")
            }
            OutlinedButton(onClick = { ble.disconnect(); ble.say("Bağlantı kesildi.") }) { Text("Kes") }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ble.lines.joinToString("\n"))) }) { Text("Logu kopyala") }
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(ble.devices.values.sortedByDescending { it.rssi }) { d ->
                    val hint = d.name.uppercase().let { it.startsWith("NAG") || "NINEBOT" in it || "SEGWAY" in it }
                    Text(
                        "${if (hint) "★ " else ""}${d.name}  ${d.address}  ${d.rssi} dBm",
                        modifier = Modifier.fillMaxWidth().clickable { ble.connect(d.address) }.padding(vertical = 6.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }

        Text("Log:", fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ble.lines.toList().asReversed()) { Text(it, fontSize = 11.sp) }
        }
    }
}
