package io.github.swieoei.fiets

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * Meet op de achtergrond, ook met het scherm uit of de app dicht:
 * - verbindt met de fiets zodra Android hem in de buurt ziet (BikePresenceService) of de app opent
 * - start de rit bij verbinden, telt GPS-afstand zoals de web-app, stopt na 5 min stilstand
 * - blijft de fiets aan na zo'n stop, dan start een nieuwe rit zodra je weer rijdt
 * - afgeronde ritten gaan in een wachtrij; de web-app neemt ze over bij het openen
 */
class RideService : Service(), BikeGatt.Listener, LocationListener {
    companion object {
        private const val TAG = "FietsRit"
        const val ACT_OPEN = "open"
        const val ACT_PRESENT = "present"
        const val ACT_GONE = "gone"
        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        const val ACT_PAUSE = "pause"
        const val ACT_RESUME = "resume"
        private const val CHANNEL = "rit"
        private const val CHANNEL_DONE = "ritten"
        private const val NOTIF_ID = 7
        private const val LINGER = 60_000L          // zo lang zoeken na openen van de app
        private const val PRESENT_WAIT = 10 * 60_000L // fiets in de buurt maar niet te verbinden: zo lang blijven proberen
        private const val OFFLINE_STOP = 2 * 60_000L // fiets uit tijdens de rit: rit na 2 min afsluiten
        private val NL: Locale = Locale.forLanguageTag("nl-NL")
        private val MODE_COLOR = mapOf("Uit" to 0xFFE5E7EB.toInt(), "Breeze" to 0xFF22C55E.toInt(), "River" to 0xFF3B82F6.toInt(), "Rocket" to 0xFFD946EF.toInt())

        fun send(ctx: Context, action: String) {
            try {
                ctx.startForegroundService(Intent(ctx, RideService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "service starten lukte niet: $e")
            }
        }
    }

    private lateinit var store: Store
    private lateinit var bike: BikeGatt
    private val engine = RideEngine()
    private val main = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var gpsOn = false
    private var manual = false
    private var anchor: Fix? = null
    private var searchSince = 0L
    private var offlineSince = 0L
    private var lastSaved = 0L
    private var lastNotif = 0L
    private var fgTypes = 0
    private var wake: PowerManager.WakeLock? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var autoStart = true      // na handmatig stoppen geen nieuwe rit tot de fiets opnieuw verbindt
    private var uiOpened = false      // app hoogstens één keer per keer fietsen vanzelf openen

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        bike = BikeGatt(this, this)
        lm = getSystemService(LocationManager::class.java)
        channels()
        Live.running = true
        Live.connected = false
        Live.status = "Zoeken…"
        restoreRide()
        // telefoon in de houder gezet terwijl de fiets verbonden is: app openen
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (bike.connected) openUi() }
        }
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), RECEIVER_NOT_EXPORTED)
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACT_START -> { manual = !bike.connected; startRide(now) }
            ACT_STOP -> { stopRide(); autoStart = false }
            ACT_PAUSE -> engine.pause()
            ACT_RESUME -> engine.resume(now)
            ACT_GONE -> if (!bike.connected && !engine.active) { shutdown(); return START_NOT_STICKY }
        }
        if (intent?.action != ACT_GONE) searchSince = now
        connectBike()
        publish(true)
        return START_STICKY
    }

    override fun onDestroy() {
        if (Live.running) cleanup()
        super.onDestroy()
    }

    // ---------- verbinden ----------

    private fun connectBike() {
        val mac = store.mac
        if (mac == null) { Live.status = "Koppel fiets"; return }
        if (!store.has(Manifest.permission.BLUETOOTH_CONNECT)) { Live.status = "Geef de app toegang tot apparaten in de buurt"; return }
        bike.connect(mac, store.addrType)
        bike.kick()
        if (!bike.connected) Live.status = "Zoeken…"
    }

    private fun goForeground() {
        val bt = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val wantLoc = store.has(Manifest.permission.ACCESS_FINE_LOCATION)
        fgTypes = try {
            startForeground(NOTIF_ID, notification(), if (wantLoc) bt or loc else bt)
            if (wantLoc) bt or loc else bt
        } catch (e: Exception) {
            // vanuit de achtergrond mag locatie alleen met 'altijd toestaan': dan voorlopig alleen Bluetooth
            Log.w(TAG, "foreground met locatie lukte niet: $e")
            try { startForeground(NOTIF_ID, notification(), bt); bt } catch (e2: Exception) { Log.w(TAG, "foreground: $e2"); 0 }
        }
        updateGps()
    }

    private fun gpsAllowed() = fgTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0

    override fun onBikeConnected(name: String?) {
        if (!name.isNullOrBlank()) store.name = name
        Live.status = "Verbonden"
        offlineSince = 0
        updateGps()
        publish(true)
    }

    override fun onBikeReady(battery: Boolean, mode: Boolean) {
        Live.status = if (!battery && !mode) "Verbonden, maar zonder accu en stand: koppel de fiets opnieuw"
        else "Verbonden" + (if (battery) " · accu" else "") + (if (mode) " · stand" else "")
        manual = false
        autoStart = true
        startRide(System.currentTimeMillis())
        openUi()
        publish(true)
    }

    override fun onBikeDisconnected(wasConnected: Boolean) {
        if (wasConnected) {
            Live.status = "Verbinding kwijt, zoeken…"
            offlineSince = System.currentTimeMillis()
            searchSince = offlineSince
        }
        updateGps()
        publish(true)
    }

    override fun onBikeBattery(pct: Int) {
        if (pct != store.bat) { store.setBattery(pct, System.currentTimeMillis()); publish(true) }
    }

    override fun onBikeMode(index: Int) {
        val m = RideEngine.MODES.getOrNull(index) ?: return
        if (m != store.mode) { store.setMode(m, System.currentTimeMillis()); publish(true) }
    }

    // ---------- rit ----------

    private fun startRide(now: Long) {
        if (engine.active) return
        engine.start(now)
        anchor = null
        engine.gps = if (gpsAllowed()) "Rit gestart. GPS zoeken…" else "Rit gestart. Sta locatie 'altijd' toe, anders meet GPS niet op de achtergrond"
        wakeOn()
        updateGps()
        saveRide()
    }

    private fun stopRide() {
        val rec = engine.finish(store.mode ?: "River")
        if (rec != null) {
            store.addPending(rec)
            notifySaved(rec)
        }
        manual = false; anchor = null
        store.activeRide = null
        wakeOff()
        updateGps()
        engine.gps = if (bike.connected) "Rit start zodra je weer rijdt" else ""
        publish(true)
    }

    private fun updateGps() {
        val want = (engine.active || bike.connected) && gpsAllowed() && store.has(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = lm ?: return
        if (want && !gpsOn) {
            val provider = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            val req = LocationRequest.Builder(1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(1000L).build()
            try {
                lm.requestLocationUpdates(provider, req, mainExecutor, this)
                gpsOn = true
            } catch (e: SecurityException) {
                engine.gps = "Geen toestemming voor locatie"
            }
        } else if (!want && gpsOn) {
            lm.removeUpdates(this)
            gpsOn = false
        }
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val f = Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 99f, if (loc.hasSpeed()) loc.speed else null, now)
        val mode = store.mode ?: "River"
        if (engine.active) {
            if (bike.connected || manual || engine.paused) engine.onFix(f, mode)
            else { engine.speed = 0.0; engine.gps = "Fiets niet verbonden: afstand telt even niet mee" }
        } else if (bike.connected && autoStart && f.accuracy <= 35) {
            // fiets staat nog aan na 5 min stilstand: nieuwe rit zodra je weer rijdt
            val a = anchor
            if (a == null) anchor = f
            else if (RideEngine.haversine(a, f) > max(30.0, 2.0 * f.accuracy) && (f.speed ?: 0f) >= 1.5f) {
                startRide(now)
                engine.onFix(f, mode)
            }
        }
        if (engine.active && now - lastSaved > 10_000) saveRide()
        publish(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { engine.gps = "Locatie staat uit op je telefoon"; publish(true) }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (engine.idleExpired(now)) stopRide()
            else if (engine.active && !manual && !bike.connected && offlineSince > 0 && now - offlineSince > OFFLINE_STOP) stopRide()
            // niets te doen: fiets weg, geen rit. Zolang Android de fiets in de buurt ziet, blijven we proberen.
            val presentWait = store.present && now - store.presentAt < PRESENT_WAIT
            if (!engine.active && !bike.connected && !presentWait && now - searchSince > LINGER) { shutdown(); return }
            if (now - lastNotif > 5000) publish(true)
            main.postDelayed(this, 1000)
        }
    }

    private fun saveRide() {
        lastSaved = System.currentTimeMillis()
        store.activeRide = if (!engine.active) null else JSONObject()
            .put("dist", engine.dist).put("moving", engine.moving).put("max", engine.maxSpeed)
            .put("started", engine.started).put("lastMove", engine.lastMove)
            .put("paused", engine.paused).put("manual", manual)
            .put("byMode", JSONObject().also { o -> engine.byMode.forEach { (k, v) -> o.put(k, v) } })
            .toString()
    }

    private fun restoreRide() {
        val s = store.activeRide ?: return
        try {
            val o = JSONObject(s)
            val bm = o.optJSONObject("byMode")
            val byMode = bm?.keys()?.asSequence()?.associateWith { bm.getDouble(it) } ?: emptyMap()
            engine.restore(o.getDouble("dist"), o.getLong("moving"), o.getDouble("max"), o.getLong("started"), o.getLong("lastMove"), o.optBoolean("paused"), byMode)
            manual = o.optBoolean("manual")
            // app was te lang weg: rit nu afsluiten (eindtijd = laatste beweging)
            if (engine.idleExpired(System.currentTimeMillis())) stopRide() else wakeOn()
        } catch (e: Exception) {
            store.activeRide = null
        }
    }

    // ---------- scherm, melding, opruimen ----------

    /** App openen als de telefoon oplaadt (in de houder op de fiets) en de app nog niet in beeld is. */
    private fun openUi() {
        if (uiOpened || MainActivity.visible || !store.charging()) return
        uiOpened = true
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "app openen lukte niet: $e")
        }
    }

    private fun publish(notify: Boolean) {
        Live.connected = bike.connected
        Live.gps = engine.gps
        Live.ride = JSONObject()
            .put("active", engine.active).put("paused", engine.paused)
            .put("dist", engine.dist).put("moving", engine.moving).put("max", engine.maxSpeed)
            .put("started", engine.started).put("lastMove", engine.lastMove)
            .put("speed", engine.speed).put("manual", manual)
            .toString()
        if (notify) {
            lastNotif = System.currentTimeMillis()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rit en fiets", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Opgeslagen ritten", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun f1(v: Double) = String.format(NL, "%.1f", v)

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(NL, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun notification(): Notification {
        val mode = store.mode
        val accu = store.bat.takeIf { it >= 0 }?.let { "accu $it%" }
        val (title, text) = when {
            engine.active -> "Rit · ${f1(engine.dist / 1000)} km · ${hms(engine.moving)}" to
                listOfNotNull(mode, accu, if (engine.paused) "gepauzeerd" else "${f1(engine.speed)} km/u").joinToString(" · ")
            bike.connected -> "Fiets verbonden" to listOfNotNull(mode, accu, "rit start zodra je rijdt").joinToString(" · ")
            else -> "Fiets zoeken…" to "De rit start vanzelf zodra je fiets aan staat"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(MODE_COLOR[mode] ?: Color.WHITE)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifySaved(r: RideRecord) {
        val avg = if (r.mins > 0) r.km / (r.mins / 60) else 0.0
        val n = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(MODE_COLOR[r.mode] ?: Color.WHITE)
            .setContentTitle("Rit opgeslagen: ${String.format(NL, "%.2f", r.km)} km")
            .setContentText("${Math.round(r.mins)} min · gem. ${f1(avg)} km/u · max ${f1(r.max)} km/u")
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify((r.id % 100_000).toInt() + 100, n)
    }

    private fun wakeOn() {
        if (wake != null) return
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fiets:rit").apply {
            setReferenceCounted(false)
            acquire(8 * 3600_000L)
        }
    }

    private fun wakeOff() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun cleanup() {
        if (engine.active) saveRide()
        bike.close()
        lm?.removeUpdates(this); gpsOn = false
        wakeOff()
        main.removeCallbacksAndMessages(null)
        powerReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        powerReceiver = null
        Live.running = false
        Live.connected = false
        Live.status = ""
    }

    private fun shutdown() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
