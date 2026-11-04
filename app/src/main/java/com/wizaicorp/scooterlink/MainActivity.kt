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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val NORDIC_UART_SVC = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
private const val NUS_TX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // telefon -> scooter (write)
private const val NUS_RX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // scooter -> telefon (notify)
private const val NINEBOT_SVC = "6e400001-0000-0000-006e-696e65626f74"
private const val NINEBOT_TX = "6e400002-0000-0000-006e-696e65626f74"
private const val NINEBOT_RX = "6e400004-0000-0000-006e-696e65626f74"
private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
private const val GAP_SVC = "00001800-0000-1000-8000-00805f9b34fb"
private const val DEVICE_NAME_CHAR = "00002a00-0000-1000-8000-00805f9b34fb"
private const val WRITE_CHUNK = 20 // MTU pazarlığı yapmadan güvenli üst sınır

data class Found(val name: String, val address: String, val rssi: Int)

fun hex(b: ByteArray?): String = b?.joinToString(" ") { "%02X".format(it) } ?: "(boş)"

@SuppressLint("MissingPermission")
class BleController(private val ctx: Context) {
    val devices = mutableStateMapOf<String, Found>()
    val lines = mutableStateListOf<String>()
    var scanning by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var handshaking by mutableStateOf(false)

    private val raw = HashMap<String, BluetoothDevice>()
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var connectedName: String = "Ninebot"

    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private var notifyBuf = ArrayList<Byte>()
    private var frameDeferred: CompletableDeferred<ByteArray>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var readDeferred: CompletableDeferred<ByteArray?>? = null
    private var gapDeviceName: String? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun say(s: String) {
        main.post { lines.add(s) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: d.name ?: "?"
            raw[d.address] = d
            devices[d.address] = Found(name, d.address, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            say("Tarama hatası: $errorCode")
            scanning = false
        }
    }

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            say("Bluetooth kapalı. Açıp tekrar dene.")
            return
        }
        devices.clear()
        raw.clear()
        a.bluetoothLeScanner.startScan(scanCb)
        scanning = true
        say("Tarama başladı.")
    }

    fun stopScan() {
        if (scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
            scanning = false
            say("Tarama durdu.")
        }
    }

    fun connect(address: String) {
        stopScan()
        val dev = raw[address] ?: return
        connectedName = dev.name ?: "Ninebot"
        gatt?.close()
        txChar = null; rxChar = null; connected = false
        notifyBuf = ArrayList()
        frameDeferred?.cancel(); frameDeferred = null
        writeDeferred?.cancel(); writeDeferred = null
        readDeferred?.cancel(); readDeferred = null
        gapDeviceName = null
        handshaking = false
        say("Bağlanılıyor: $address (ad: $connectedName)")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txChar = null; rxChar = null; connected = false
    }

    private fun props(p: Int): String = buildList {
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("R")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("W")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("Wn")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("N")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("I")
    }.joinToString("/")

    // ---------- GATT callback ----------

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            say("Bağlantı durumu: status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                say("Bağlandı, servisler aranıyor...")
                g.discoverServices()
            } else {
                connected = false
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            say("Servisler bulundu (status=$status):")
            for (s in g.services) {
                val u = s.uuid.toString().lowercase()
                val tag = when (u) {
                    NINEBOT_SVC -> "  <-- NINEBOT servisi"
                    NORDIC_UART_SVC -> "  <-- Nordic UART"
                    else -> ""
                }
                say("S $u$tag")
                for (c in s.characteristics) say("   C ${c.uuid.toString().lowercase()} [${props(c.properties)}]")
            }

            val nbSvc = g.getService(UUID.fromString(NINEBOT_SVC))
            val nuSvc = g.getService(UUID.fromString(NORDIC_UART_SVC))
            when {
                nbSvc != null -> {
                    txChar = nbSvc.getCharacteristic(UUID.fromString(NINEBOT_TX))
                    rxChar = nbSvc.getCharacteristic(UUID.fromString(NINEBOT_RX))
                    say("Kanal: Ninebot özel servisi seçildi.")
                }
                nuSvc != null -> {
                    txChar = nuSvc.getCharacteristic(UUID.fromString(NUS_TX))
                    rxChar = nuSvc.getCharacteristic(UUID.fromString(NUS_RX))
                    say("Kanal: Nordic UART seçildi.")
                }
                else -> {
                    say("SONUÇ: Uygun servis bulunamadı.")
                    return
                }
            }

            val rx = rxChar
            if (rx == null) { say("Bildirim karakteristiği yok."); return }
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(UUID.fromString(CCCD))
            if (cccd == null) { say("CCCD bulunamadı, bildirimler açılamayabilir."); return }
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
            say(if (status == 0) "Bildirimler açıldı." else "Bildirim açma hatası: $status")
            connected = (status == 0)
            if (status == 0) {
                scope.launch {
                    val nameBytes = readDeviceNameChar()
                    if (nameBytes != null) {
                        gapDeviceName = String(nameBytes, Charsets.UTF_8)
                        say("GATT cihaz adı (2a00): \"$gapDeviceName\" — el sıkışmasında bu kullanılacak.")
                    } else {
                        say("GATT cihaz adı okunamadı, taramadaki ad kullanılacak: \"$connectedName\"")
                    }
                    say("Hazır: Eşleş'e basabilirsin.")
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDeferred?.complete(status == 0)
            writeDeferred = null
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readDeferred?.complete(if (status == 0) value else null)
            readDeferred = null
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readDeferred?.complete(if (status == 0) c.value else null)
            readDeferred = null
        }

        private fun handleIncoming(value: ByteArray) {
            notifyBuf.addAll(value.toList())
            while (notifyBuf.size >= 3 && notifyBuf[0] == 0x5A.toByte() && notifyBuf[1] == 0xA5.toByte()) {
                val len = notifyBuf[2].toInt() and 0xFF
                val total = len + 13
                if (notifyBuf.size < total) break
                val frame = notifyBuf.subList(0, total).toByteArray()
                notifyBuf = ArrayList(notifyBuf.subList(total, notifyBuf.size))
                val d = frameDeferred
                if (d != null && !d.isCompleted) {
                    d.complete(frame)
                } else {
                    say("CEVAP (ham): ${hex(frame)}")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleIncoming(c.value ?: ByteArray(0))
        }
    }

    // ---------- Düşük seviye yazma/okuma ----------

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

    private suspend fun readDeviceNameChar(): ByteArray? {
        val g = gatt ?: return null
        val svc = g.getService(UUID.fromString(GAP_SVC)) ?: return null
        val char = svc.getCharacteristic(UUID.fromString(DEVICE_NAME_CHAR)) ?: return null
        val d = CompletableDeferred<ByteArray?>()
        readDeferred = d
        if (!g.readCharacteristic(char)) { readDeferred = null; return null }
        return withTimeoutOrNull(3000) { d.await() }
    }

    private suspend fun waitFrame(timeoutMs: Long): ByteArray? {
        val d = CompletableDeferred<ByteArray>()
        frameDeferred = d
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        frameDeferred = null
        return r
    }

    // ---------- El sıkışma (PRE_COMM / SET_PWD / AUTH) ----------

    fun startHandshake() {
        if (handshaking) {
            say("Zaten çalışıyor, bekle (düğmeye tekrar basmaya gerek yok)."); return
        }
        handshaking = true
        scope.launch {
            try {
                handshake()
            } finally {
                handshaking = false
            }
        }
    }

    private suspend fun handshake() {
        if (gatt == null || txChar == null || rxChar == null) {
            say("Önce bağlan ve bildirimlerin açılmasını bekle."); return
        }
        notifyBuf = ArrayList() // önceki denemeden kalmış olabilecek baytları at

        // Hangi "cihaz adı" anahtar türetiminde doğru, bilmiyoruz: GATT'taki resmi
        // ad varsa önce onu dene, olmazsa tarama sırasında görülen adı dene.
        val nameCandidates = linkedSetOf<String>()
        gapDeviceName?.let { nameCandidates.add(it) }
        nameCandidates.add(connectedName)

        var key: ByteArray? = null
        var preResp: ByteArray? = null
        var usedName = ""
        for (name in nameCandidates) {
            say("PRE_COMM deneniyor (cihaz adı: \"$name\")")
            val btName = name.toByteArray(Charsets.UTF_8)
            val k = NbCrypto.deriveKey(btName, null)
            val preHeader = byteArrayOf(0x5A, 0xA5.toByte(), 0x00)
            val prePayload = byteArrayOf(0x3E, 0x04, 0x5B, 0x00)
            if (!writeFrame(NbCrypto.encryptNonSn(k, preHeader, prePayload))) {
                say("PRE_COMM: yazma başarısız."); continue
            }
            val resp = waitFrame(8000)
            if (resp == null) { say("PRE_COMM: cevap yok (zaman aşımı)."); continue }
            val plain = NbCrypto.decryptNonSn(k, resp)
            if (plain == null || plain.size < 34) {
                say("PRE_COMM: bu adla deşifre/uzunluk tutmadı (ad=\"$name\"). Ham cevap: ${hex(resp)}")
                continue
            }
            key = k; preResp = resp; usedName = name
            break
        }
        if (key == null || preResp == null) {
            say("PRE_COMM: hiçbir aday adla sonuç alınamadı."); return
        }
        val btName = usedName.toByteArray(Charsets.UTF_8)
        val prePlain = NbCrypto.decryptNonSn(key, preResp)!!
        val storedFlag = prePlain[3].toInt()
        val authParam = prePlain.copyOfRange(4, 20)
        val serial = prePlain.copyOfRange(20, 34)
        say("PRE_COMM tamam (ad: \"$usedName\"). Seri: ${String(serial, Charsets.US_ASCII)}  kayıtlı_şifre=${if (storedFlag != 0) "var" else "yok"}")
        say("auth_param: ${hex(authParam)}")

        var counter = 1 // start_sn

        // --- SET_PWD ---
        counter++ // 2
        key = NbCrypto.deriveKey(btName, authParam)
        val password = NbCrypto.generatePassword(authParam)
        val setHeader = byteArrayOf(0x5A, 0xA5.toByte(), 16)
        val setPayload = byteArrayOf(0x3E, 0x04, 0x5C, 0x00) + password
        say("SET_PWD gönderiliyor. Scooter üzerinde onay/düğme istiyorsa şimdi bas (60 sn içinde dener).")
        var accepted = false
        var tries = 0
        while (!accepted && tries < 28) {
            if (!writeFrame(NbCrypto.encryptSn(key, setHeader, setPayload, counter, authParam))) {
                say("SET_PWD: yazma başarısız."); return
            }
            val resp = waitFrame(2200)
            if (resp != null) {
                val dec = NbCrypto.decryptSn(key, resp, authParam)
                if (dec != null) {
                    val plain = dec.first
                    if (plain.size >= 4 && plain[3].toInt() == 1) { accepted = true } else say("SET_PWD bekleniyor...")
                } else say("SET_PWD: MAC doğrulanamadı.")
            } else say("SET_PWD: cevap yok, tekrar deneniyor...")
            tries++
        }
        if (!accepted) { say("SET_PWD başarısız / zaman aşımı."); return }
        say("SET_PWD kabul edildi. Şifre: ${hex(password)}")

        // --- AUTH ---
        counter++ // 3
        key = NbCrypto.deriveKey(password, authParam)
        val authHeader = byteArrayOf(0x5A, 0xA5.toByte(), 14)
        val authPayload = byteArrayOf(0x3E, 0x04, 0x5D, 0x00) + serial
        if (!writeFrame(NbCrypto.encryptSn(key, authHeader, authPayload, counter, authParam))) {
            say("AUTH: yazma başarısız."); return
        }
        val authResp = waitFrame(3000) ?: run { say("AUTH: cevap yok."); return }
        val authDec = NbCrypto.decryptSn(key, authResp, authParam) ?: run { say("AUTH: MAC doğrulanamadı."); return }
        if (authDec.first.size >= 4 && authDec.first[3].toInt() == 1) {
            say("AUTH BAŞARILI — oturum kuruldu.")
            say("Sakla: seri=${String(serial, Charsets.US_ASCII)}  şifre(hex)=${hex(password)}")
        } else {
            say("AUTH reddedildi.")
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
        onDispose {
            ble.stopScan()
            ble.disconnect()
        }
    }

    val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) ble.startScan() else ble.say("İzin verilmedi.")
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("ScooterLink - el sıkışma testi", fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ble.scanning) ble.stopScan() else launcher.launch(perms)
            }) { Text(if (ble.scanning) "Durdur" else "Tara") }
            OutlinedButton(onClick = { ble.disconnect(); ble.say("Bağlantı kesildi.") }) { Text("Kes") }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(ble.lines.joinToString("\n")))
            }) { Text("Logu kopyala") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ble.startHandshake() }, enabled = !ble.handshaking) {
                Text(if (ble.handshaking) "Çalışıyor..." else "Eşleş (El sıkışması)")
            }
        }

        Text("Cihazlar (dokununca bağlanır):")
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ble.devices.values.sortedByDescending { it.rssi }) { d ->
                val hint = d.name.lowercase().let { "ninebot" in it || "segway" in it || it.startsWith("nb") }
                Text(
                    "${if (hint) "* " else ""}${d.name}  ${d.address}  ${d.rssi} dBm",
                    modifier = Modifier.fillMaxWidth().clickable { ble.connect(d.address) }.padding(vertical = 6.dp),
                    fontSize = 14.sp
                )
            }
        }

        Text("Log:")
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ble.lines.toList()) { Text(it, fontSize = 11.sp) }
        }
    }
}
