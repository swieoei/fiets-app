package io.github.swieoei.fiets

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Bluetooth-verbinding met de Fazua-aandrijving (Gazelle Cayo):
 * accu via Battery Service 0x180F, stand via de open dienst 0xFD6D (byte 0 van characteristic 7).
 * De versleutelde Fazua-dienst (f1480001…) blijft onaangeroerd: die vraagt om koppelen.
 */
@SuppressLint("MissingPermission")
class BikeGatt(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onBikeConnected(name: String?)
        fun onBikeReady(battery: Boolean, mode: Boolean)
        fun onBikeDisconnected(wasConnected: Boolean)
        fun onBikeBattery(pct: Int)
        fun onBikeMode(index: Int)
    }

    companion object {
        private const val TAG = "FietsBle"
        val BAT_SVC: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BAT_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val FAZUA_SVC: UUID = UUID.fromString("0000fd6d-0000-1000-8000-00805f9b34fb")
        val FAZUA_MODE: UUID = UUID.fromString("00000007-abab-4499-b9d7-d0efc0d06477")
        val CYCLING_POWER: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var wanted: String? = null
    private var addrType = -1
    private var failures = 0
    private var autoMode = false
    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var opToken = 0
    var connected = false; private set

    /** Verbinden en verbonden blijven; na een onderbreking verbindt hij vanzelf opnieuw. */
    fun connect(mac: String, type: Int) {
        if (wanted == mac && gatt != null) return
        if (wanted != mac) close()
        wanted = mac; addrType = type
        open()
    }

    /** Fiets is net gezien of de app is geopend: niet wachten, meteen een snelle poging. */
    fun kick() {
        if (connected || wanted == null) return
        if (gatt != null && !autoMode) return // er loopt al een snelle poging
        failures = 0
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        open()
    }

    fun close() {
        wanted = null
        main.removeCallbacksAndMessages(null)
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        connected = false; busy = false; ops.clear()
    }

    private fun open() {
        val mac = wanted ?: return
        if (gatt != null) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            main.postDelayed({ open() }, 10_000) // Bluetooth staat uit: later opnieuw
            return
        }
        // adrestype van de scan bij het koppelen meegeven: de Fazua gebruikt mogelijk een 'random' adres
        val dev = try {
            when (addrType) {
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
                else -> adapter.getRemoteDevice(mac)
            }
        } catch (e: Exception) { Log.w(TAG, "adres: $e"); return }
        // eerst twee snelle pogingen; daarna laat Android zelf verbinden zodra de fiets in de buurt is
        autoMode = failures >= 2
        Log.d(TAG, "verbinden met $mac (auto=$autoMode)")
        gatt = dev.connectGatt(ctx, autoMode, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { g.close(); return@post }
                Log.d(TAG, "status=$status state=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true; failures = 0
                    listener.onBikeConnected(g.device.name)
                    main.postDelayed({ if (gatt === g && connected) g.discoverServices() }, 300)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = connected
                    connected = false; busy = false; ops.clear()
                    g.close(); gatt = null
                    if (!was) failures++
                    listener.onBikeDisconnected(was)
                    if (wanted != null) main.postDelayed({ open() }, if (was) 500 else 2000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return@post }
                val bat = g.getService(BAT_SVC)?.getCharacteristic(BAT_LEVEL)
                val mode = g.getService(FAZUA_SVC)?.getCharacteristic(FAZUA_MODE)
                bat?.let { c -> op { g.readCharacteristic(c) }; op { subscribe(g, c) } }
                mode?.let { c -> op { g.readCharacteristic(c) }; op { subscribe(g, c) } }
                listener.onBikeReady(bat != null, mode != null)
                next()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) deliver(c.uuid, value)
                done()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { deliver(c.uuid, value) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { done() }
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(CCCD) ?: return false
        return g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    }

    // Android kan maar één GATT-actie tegelijk: in de rij, met een time-out per actie
    private fun op(f: () -> Boolean) = ops.addLast(f)

    private fun next() {
        while (!busy && ops.isNotEmpty()) {
            busy = try { ops.removeFirst()() } catch (e: Exception) { false }
            if (busy) {
                val t = ++opToken
                main.postDelayed({ if (busy && opToken == t) done() }, 4000)
            }
        }
    }

    private fun done() {
        busy = false; opToken++
        next()
    }

    private fun deliver(uuid: UUID, v: ByteArray) {
        if (v.isEmpty()) return
        val b = v[0].toInt() and 0xff
        when (uuid) {
            BAT_LEVEL -> if (b in 0..100) listener.onBikeBattery(b)
            FAZUA_MODE -> listener.onBikeMode(b)
        }
    }
}
