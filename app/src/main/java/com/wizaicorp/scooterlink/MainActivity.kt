package com.wizaicorp.scooterlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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

private const val NINEBOT_SVC = "6e400001-0000-0000-006e-696e65626f74"
private const val NORDIC_UART_SVC = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

data class Found(val name: String, val address: String, val rssi: Int)

@SuppressLint("MissingPermission")
class BleController(private val ctx: Context) {
    val devices = mutableStateMapOf<String, Found>()
    val lines = mutableStateListOf<String>()
    var scanning by mutableStateOf(false)

    private val raw = HashMap<String, BluetoothDevice>()
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

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
        gatt?.close()
        say("Bağlanılıyor: $address")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun props(p: Int): String = buildList {
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("R")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("W")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("Wn")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("N")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("I")
    }.joinToString("/")

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            say("Bağlantı durumu: status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                say("Bağlandı, servisler aranıyor...")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            say("Servisler bulundu (status=$status):")
            var ninebot = false
            for (s in g.services) {
                val u = s.uuid.toString().lowercase()
                val tag = when (u) {
                    NINEBOT_SVC -> { ninebot = true; "  <-- NINEBOT servisi" }
                    NORDIC_UART_SVC -> "  <-- Nordic UART"
                    else -> ""
                }
                say("S $u$tag")
                for (c in s.characteristics) {
                    say("   C ${c.uuid.toString().lowercase()} [${props(c.properties)}]")
                }
            }
            say(if (ninebot) "SONUÇ: Ninebot servisi var, protokol aşamasına geçilebilir." else "SONUÇ: Ninebot servisi görünmüyor.")
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
        Text("ScooterLink - bağlantı testi", fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ble.scanning) ble.stopScan() else launcher.launch(perms)
            }) { Text(if (ble.scanning) "Durdur" else "Tara") }
            OutlinedButton(onClick = { ble.disconnect(); ble.say("Bağlantı kesildi.") }) { Text("Kes") }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(ble.lines.joinToString("\n")))
            }) { Text("Logu kopyala") }
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
