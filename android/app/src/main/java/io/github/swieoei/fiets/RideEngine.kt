package io.github.swieoei.fiets

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Eén GPS-meting, los van android.location zodat de rit-logica zonder telefoon te testen is. */
data class Fix(val lat: Double, val lon: Double, val accuracy: Float, val speed: Float?, val time: Long)

/** Een afgeronde rit, in dezelfde vorm als de web-app opslaat (state.rides). */
data class RideRecord(
    val id: Long,
    val at: Long,
    val end: Long,
    val km: Double,
    val mins: Double,
    val max: Double,
    val mode: String,
    val modes: Map<String, Double>,
)

/**
 * Rit-logica, gelijk aan de web-app (index.html: onPos, startRide, stopRide):
 * - punten met een nauwkeurigheid slechter dan 35 m tellen niet mee
 * - afstand telt alleen als de GPS beweging meldt (snelheid >= 1 m/s, of zonder snelheid:
 *   nauwkeurigheid <= 12 m), de verplaatsing groter is dan de onnauwkeurigheid (min. 5 m)
 *   en de berekende snelheid onder 80 km/u blijft
 * - rijtijd telt alleen bij beweging (> 2 km/u), max. 30 s per stap
 * - na 5 minuten zonder beweging stopt de rit; de eindtijd is de laatste beweging,
 *   dus de stilstand valt eraf
 */
class RideEngine {
    companion object {
        const val IDLE_STOP = 5 * 60_000L
        const val MIN_KM = 0.05
        val MODES = listOf("Uit", "Breeze", "River", "Rocket")

        fun haversine(a: Fix, b: Fix): Double {
            val r = PI / 180
            val dLat = (b.lat - a.lat) * r
            val dLon = (b.lon - a.lon) * r
            val x = sin(dLat / 2).pow(2) + cos(a.lat * r) * cos(b.lat * r) * sin(dLon / 2).pow(2)
            return 2 * 6_371_000.0 * asin(sqrt(x))
        }
    }

    var active = false; private set
    var paused = false; private set
    var dist = 0.0; private set          // meter
    var moving = 0L; private set         // ms rijtijd
    var maxSpeed = 0.0; private set      // km/u
    var started = 0L; private set
    var lastMove = 0L; private set
    var speed = 0.0                      // km/u, voor de weergave
    var gps = ""                         // statusregel voor het scherm
    val byMode = linkedMapOf<String, Double>() // km per stand
    private var last: Fix? = null

    fun start(now: Long) {
        if (active) return
        active = true; paused = false
        dist = 0.0; moving = 0; maxSpeed = 0.0; speed = 0.0
        started = now; lastMove = now
        byMode.clear(); last = null
    }

    /** Lopende rit terugzetten na een herstart van de app; de volgende meting begint een nieuw stuk. */
    fun restore(dist: Double, moving: Long, maxSpeed: Double, started: Long, lastMove: Long, paused: Boolean, byMode: Map<String, Double>) {
        active = true
        this.paused = paused
        this.dist = dist; this.moving = moving; this.maxSpeed = maxSpeed
        this.started = started; this.lastMove = lastMove
        this.byMode.clear(); this.byMode.putAll(byMode)
        last = null; speed = 0.0
    }

    fun pause() {
        if (active && !paused) { paused = true; speed = 0.0 }
    }

    fun resume(now: Long) {
        if (active && paused) { paused = false; last = null; lastMove = now }
    }

    fun onFix(f: Fix, mode: String) {
        if (f.accuracy > 35) {
            gps = "Wachten op beter GPS-signaal (±${f.accuracy.roundToInt()} m)"
            return
        }
        gps = "GPS actief (±${f.accuracy.roundToInt()} m)"
        var sp: Double? = f.speed?.takeIf { it >= 0 }?.let { it * 3.6 }
        val prev = last
        if (prev != null && !paused) {
            val d = haversine(prev, f)
            val dt = f.time - prev.time
            if (dt > 0) {
                val calc = d / dt * 3600.0
                if (sp == null) sp = calc
                // ruis bij stilstand en onrealistische sprongen negeren
                val beweegt = if (f.speed != null) f.speed >= 1f else f.accuracy <= 12f
                if (beweegt && d > max(5.0, f.accuracy.toDouble()) && calc < 80) {
                    dist += d
                    byMode[mode] = (byMode[mode] ?: 0.0) + d / 1000
                    if (calc > 2) {
                        moving += min(dt, 30_000L)
                        lastMove = f.time
                    }
                    maxSpeed = max(maxSpeed, min(sp ?: 0.0, 80.0))
                    last = f
                }
            }
        } else {
            last = f
        }
        speed = if (sp != null && sp > 1.5) sp else 0.0
    }

    fun idleExpired(now: Long) = active && !paused && now - lastMove >= IDLE_STOP

    /** Rit afsluiten. Geeft de op te slaan rit, of null als hij te kort was. */
    fun finish(currentMode: String): RideRecord? {
        if (!active) return null
        val km = dist / 1000
        val rec = if (km >= MIN_KM) {
            val modes = byMode.mapValues { Math.round(it.value * 1000) / 1000.0 }
            val main = modes.maxByOrNull { it.value }?.key ?: currentMode
            RideRecord(started, started, lastMove, km, moving / 60_000.0, maxSpeed, main, modes)
        } else null
        active = false; paused = false; last = null; speed = 0.0
        return rec
    }
}
