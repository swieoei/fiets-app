package io.github.swieoei.fiets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Wat de app onthoudt: gekoppelde fiets, laatste stand en accu, lopende rit en nog niet getoonde ritten. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("fiets", Context.MODE_PRIVATE)
    private val pendingFile = File(app.filesDir, "ritten-wachtrij.json")

    var mac: String?
        get() = p.getString("mac", null)
        set(v) = p.edit().putString("mac", v).apply()
    var addrType: Int
        get() = p.getInt("addrType", -1)
        set(v) = p.edit().putInt("addrType", v).apply()
    var assocId: Int
        get() = p.getInt("assocId", -1)
        set(v) = p.edit().putInt("assocId", v).apply()
    var name: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()

    val mode: String? get() = p.getString("mode", null)
    fun setMode(m: String, at: Long) = p.edit().putString("mode", m).putLong("modeAt", at).apply()

    val bat: Int get() = p.getInt("bat", -1)
    val batAt: Long get() = p.getLong("batAt", 0)
    fun setBattery(pct: Int, at: Long) = p.edit().putInt("bat", pct).putLong("batAt", at).apply()

    /** Fiets in de buurt volgens Android (Companion Device Manager). */
    val present: Boolean get() = p.getBoolean("present", false)
    val presentAt: Long get() = p.getLong("presentAt", 0)
    fun setPresent(yes: Boolean, at: Long) = p.edit().putBoolean("present", yes).putLong("presentAt", at).apply()

    var activeRide: String?
        get() = p.getString("activeRide", null)
        set(v) = p.edit().putString("activeRide", v).apply()

    fun addPending(r: RideRecord) = synchronized(LOCK) {
        val arr = readPending()
        arr.put(r.toJson())
        writePending(arr)
    }

    fun pendingCount(): Int = synchronized(LOCK) { readPending().length() }
    fun pendingJson(): String = synchronized(LOCK) { readPending().toString() }

    fun ackPending(idsJson: String) = synchronized(LOCK) {
        val a = JSONArray(idsJson)
        val ids = (0 until a.length()).map { a.getLong(it) }.toSet()
        val arr = readPending()
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") !in ids) keep.put(o)
        }
        writePending(keep)
    }

    private fun readPending(): JSONArray =
        try { JSONArray(pendingFile.readText()) } catch (e: Exception) { JSONArray() }

    private fun writePending(a: JSONArray) {
        val tmp = File(pendingFile.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(pendingFile)
    }

    fun charging(): Boolean {
        val st = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    fun has(perm: String) = app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    fun backgroundLocation() = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    companion object {
        private val LOCK = Any()
    }
}

fun RideRecord.toJson(): JSONObject = JSONObject()
    .put("id", id).put("at", at).put("end", end).put("km", km).put("mins", mins)
    .put("max", max).put("mode", mode)
    .put("modes", JSONObject().also { o -> modes.forEach { (k, v) -> o.put(k, v) } })

/** Levende toestand, gedeeld tussen de achtergrondservice en het scherm (zelfde proces). */
object Live {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var status = ""
    @Volatile var gps = ""
    @Volatile var ride = "{}"

    fun json(store: Store): String {
        val paired = store.mac != null
        return JSONObject()
            .put("paired", paired)
            .put("running", running)
            .put("connected", running && connected)
            .put("name", store.name ?: JSONObject.NULL)
            .put("status", if (running && status.isNotEmpty()) status else if (paired) "Wacht op fiets" else "Koppel fiets")
            .put("mode", store.mode ?: JSONObject.NULL)
            .put("bat", store.bat)
            .put("batAt", store.batAt)
            .put("charging", store.charging())
            .put("ride", JSONObject(if (running) ride else "{}"))
            .put("gps", if (running) gps else if (paired) "Rit start vanzelf zodra je fiets aan staat" else "Tik op het Bluetooth-icoon om je fiets te koppelen")
            .put("pending", store.pendingCount())
            .put("bgLocation", store.backgroundLocation())
            .toString()
    }
}
