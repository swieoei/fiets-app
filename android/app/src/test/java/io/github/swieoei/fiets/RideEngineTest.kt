package io.github.swieoei.fiets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nep-ritten: dezelfde regels als de web-app, zonder telefoon of fiets. */
class RideEngineTest {
    private val m = 111_195.0 // meter per graad breedte

    /** Punt `meters` ten noorden van het startpunt, op tijdstip t (ms). */
    private fun fix(t: Long, meters: Double, speed: Float? = 6f, acc: Float = 5f) =
        Fix(52.0 + meters / m, 5.0, acc, speed, t)

    @Test
    fun gewoneRitTeltAfstandTijdEnMax() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..60) e.onFix(fix(i * 1000L, i * 6.0), "River") // 6 m/s = 21,6 km/u, 1 minuut
        assertEquals(360.0, e.dist, 1.0)
        assertEquals(60_000L, e.moving)
        assertEquals(21.6, e.maxSpeed, 0.01)
        assertEquals(0.36, e.byMode["River"]!!, 0.001)
        assertEquals(21.6, e.speed, 0.01)
        assertTrue(e.gps.startsWith("GPS actief"))
    }

    @Test
    fun gpsRuisBijStilstandTeltNiet() {
        val e = RideEngine()
        e.start(0)
        // telefoon ligt stil: punten springen 3-8 m heen en weer, GPS meldt bijna geen snelheid
        for (i in 0..120) e.onFix(fix(i * 1000L, if (i % 2 == 0) 0.0 else 8.0, speed = 0.3f, acc = 6f), "Breeze")
        assertEquals(0.0, e.dist, 0.0)
        assertEquals(0L, e.moving)
        assertEquals(0.0, e.speed, 0.0)
    }

    @Test
    fun onnauwkeurigePuntenTellenNiet() {
        val e = RideEngine()
        e.start(0)
        e.onFix(fix(0, 0.0), "River")
        e.onFix(fix(1000, 6.0, acc = 50f), "River")
        assertEquals(0.0, e.dist, 0.0)
        assertTrue(e.gps.startsWith("Wachten op beter GPS-signaal"))
    }

    @Test
    fun onrealistischeSprongTeltNiet() {
        val e = RideEngine()
        e.start(0)
        e.onFix(fix(0, 0.0), "River")
        e.onFix(fix(1000, 500.0, speed = 6f), "River") // 500 m in 1 s
        assertEquals(0.0, e.dist, 0.0)
    }

    @Test
    fun naVijfMinutenStilstandStoptRitZonderDieStilstand() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..120) e.onFix(fix(i * 1000L, i * 6.0), "River") // 2 min rijden
        val stop = 120_000L
        for (i in 121..400) e.onFix(fix(i * 1000L, 720.0, speed = 0f), "River") // daarna stilstand
        assertFalse(e.idleExpired(stop + RideEngine.IDLE_STOP - 1))
        assertTrue(e.idleExpired(stop + RideEngine.IDLE_STOP))
        val r = e.finish("River")!!
        assertEquals(stop, r.end)
        assertEquals(2.0, r.mins, 0.001)
        assertEquals(0.72, r.km, 0.002)
        assertEquals("River", r.mode)
        assertFalse(e.active)
    }

    @Test
    fun teKorteRitWordtNietOpgeslagen() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..5) e.onFix(fix(i * 1000L, i * 6.0), "River") // 30 m
        assertNull(e.finish("River"))
    }

    @Test
    fun pauzeTeltNietMee() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..30) e.onFix(fix(i * 1000L, i * 6.0), "River")
        e.pause()
        for (i in 31..90) e.onFix(fix(i * 1000L, i * 6.0), "River") // doorgefietst tijdens pauze
        assertFalse(e.idleExpired(10 * 60_000L)) // gepauzeerd: geen automatische stop
        e.resume(91_000L)
        for (i in 91..120) e.onFix(fix(i * 1000L, i * 6.0), "River")
        assertEquals(180.0 + 174.0, e.dist, 1.0) // 30 s voor en 29 s na de pauze
    }

    @Test
    fun kmPerStandEnHoofdstand() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..100) e.onFix(fix(i * 1000L, i * 6.0), if (i <= 30) "Breeze" else "Rocket")
        val r = e.finish("River")!!
        assertEquals(0.18, r.modes["Breeze"]!!, 0.001)
        assertEquals(0.42, r.modes["Rocket"]!!, 0.001)
        assertEquals("Rocket", r.mode)
    }

    @Test
    fun zonderGpsSnelheidAlleenNauwkeurigePunten() {
        val e = RideEngine()
        e.start(0)
        for (i in 0..10) e.onFix(fix(i * 1000L, i * 6.0, speed = null, acc = 20f), "River") // wifi-locatie
        assertEquals(0.0, e.dist, 0.0)
        for (i in 11..20) e.onFix(fix(i * 1000L, i * 15.0, speed = null, acc = 10f), "River")
        assertTrue(e.dist > 100)
    }

    @Test
    fun herstelNaHerstartGaatVerder() {
        val e = RideEngine()
        e.restore(1200.0, 240_000L, 25.0, 1_000L, 250_000L, false, mapOf("River" to 1.2))
        assertTrue(e.active)
        e.onFix(fix(251_000L, 0.0), "River")
        e.onFix(fix(252_000L, 6.0), "River")
        assertEquals(1206.0, e.dist, 1.0)
        assertEquals(241_000L, e.moving)
    }
}
