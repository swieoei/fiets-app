package io.github.swieoei.fiets

import android.content.ContentValues
import android.provider.MediaStore
import android.webkit.JavascriptInterface

/** window.FietsNative in de pagina. Alleen de eigen pagina wordt geladen (zie MainActivity.Client). */
class Bridge(private val act: MainActivity) {
    private val store = Store(act)

    @JavascriptInterface fun version(): String = "1"

    /** Levende toestand: verbinding, stand, accu, lopende rit, GPS-status, aantal nog over te nemen ritten. */
    @JavascriptInterface fun live(): String = Live.json(store)

    /** Afgeronde ritten die de pagina nog moet overnemen (JSON-lijst). */
    @JavascriptInterface fun takeRides(): String = store.pendingJson()

    @JavascriptInterface fun ackRides(ids: String) = store.ackPending(ids)

    @JavascriptInterface fun start() = RideService.send(act, RideService.ACT_START)
    @JavascriptInterface fun stop() = RideService.send(act, RideService.ACT_STOP)
    @JavascriptInterface fun pause() = RideService.send(act, RideService.ACT_PAUSE)
    @JavascriptInterface fun resume() = RideService.send(act, RideService.ACT_RESUME)

    /** Stand handmatig gekozen in de pagina; telt alleen als de fiets niet verbonden is (anders bepaalt de fiets). */
    @JavascriptInterface fun setMode(m: String) {
        if (!(Live.running && Live.connected) && m in RideEngine.MODES) store.setMode(m, System.currentTimeMillis())
    }

    @JavascriptInterface fun btTap() = act.runOnUiThread { act.btTap() }
    @JavascriptInterface fun pair() = act.runOnUiThread { act.pair() }
    @JavascriptInterface fun keepScreenOn(on: Boolean) = act.runOnUiThread { act.keepScreenOn(on) }
    @JavascriptInterface fun openNav(url: String) = act.runOnUiThread { act.openIntent(url) }

    /** Back-up opslaan in Downloads (de WebView kan zelf geen bestanden downloaden). */
    @JavascriptInterface fun saveFile(name: String, text: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
        }
        val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri != null && act.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()); true } == true
    } catch (e: Exception) {
        false
    }
}
