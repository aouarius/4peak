package ch.trainingskompass

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Verbindet einen Bluetooth-Pulsgurt (Standard-Profil "Heart Rate", z. B. Decathlon HRM, Polar H10, Garmin HRM)
 * und meldet jeden Pulswert. Berechtigungen fragt die MainActivity vorher ab.
 */
@SuppressLint("MissingPermission")
class HrSensor(
    private val context: Context,
    private val onHr: (Int) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("hr", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    val connected: Boolean get() = gatt != null

    fun connect() {
        val a = adapter ?: return onStatus("Dieses Handy hat kein Bluetooth.", false)
        if (!a.isEnabled) return onStatus("Bitte zuerst Bluetooth einschalten.", false)
        val scanner = a.bluetoothLeScanner ?: return onStatus("Bluetooth ist gerade nicht bereit.", false)
        disconnect()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onStatus("Suche Brustgurt … Gurt anfeuchten und anlegen.", false)
        runCatching { scanner.startScan(listOf(filter), settings, scanCb) }
            .onFailure { scanning = false; onStatus("Suche konnte nicht starten.", false); return }
        handler.postDelayed({
            if (scanning) {
                stopScan()
                onStatus("Kein Brustgurt gefunden. Er sendet nur, wenn er auf der Haut sitzt.", false)
            }
        }, 15_000)
    }

    fun disconnect() {
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("Suche fehlgeschlagen (Fehler $errorCode).", false)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        onStatus("Verbinde mit ${device.name ?: "Brustgurt"} …", false)
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                if (gatt === g) gatt = null
                onHr(0)
                onStatus("Verbindung zum Brustgurt getrennt.", false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                onStatus("Dieses Gerät liefert keinen Puls.", false)
                return
            }
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD)?.let { d ->
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
            prefs.edit().putString("addr", g.device.address).apply()
            onStatus("Verbunden mit ${g.device.name ?: "Brustgurt"}", true)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            parse(value)
        }

        @Deprecated("Bis Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                ch.value?.let { parse(it) }
            }
        }
    }

    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val sixteenBit = (v[0].toInt() and 1) != 0
        val hr = if (sixteenBit && v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) else v[1].toInt() and 0xff
        if (hr in 25..250) onHr(hr)
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
