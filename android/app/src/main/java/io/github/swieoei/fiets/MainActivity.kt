package io.github.swieoei.fiets

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Het scherm van de app: dezelfde fiets-app als op swieoei.github.io/fiets-app, in een WebView.
 * Bluetooth, GPS en het meten op de achtergrond doet RideService; de pagina praat daarmee via FietsNative.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    companion object {
        private const val TAG = "Fiets"
        const val HOME = "https://swieoei.github.io/fiets-app/"
        private const val REQ_FILE = 1
        private const val REQ_ASSOC = 2
        private const val REQ_PERMS_PAIR = 3
        private const val REQ_PERMS_OPEN = 4
        private const val REQ_BG_LOC = 5
        @Volatile var visible = false
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }
    private val askPerms = arrayOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS)
    private val needPerms = arrayOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        // voor testen: andere bron (bijv. http://localhost:8920/ via adb reverse)
        val start = intent?.getStringExtra("url")?.takeIf { it.startsWith("http://localhost") || it.startsWith(HOME) } ?: HOME
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#020304"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(false)
            addJavascriptInterface(Bridge(this@MainActivity), "FietsNative")
            webViewClient = Client()
            webChromeClient = Chrome()
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020304"))
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(start)
        ensureObserving()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (Store(this).mac != null && needPerms.all(::has)) RideService.send(this, RideService.ACT_OPEN)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        visible = false
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url
            if (url.scheme == "intent") { openIntent(url.toString()); return true }
            val s = url.toString()
            if (s.startsWith(HOME) || s.startsWith("http://localhost")) return false
            try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try {
                startActivityForResult(params.createIntent(), REQ_FILE); true
            } catch (e: ActivityNotFoundException) {
                fileCb = null; false
            }
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            Log.d("FietsWeb", "${m.message()} (${m.lineNumber()})")
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null }
            REQ_ASSOC -> if (resultCode != RESULT_OK) toastWeb("Koppelen geannuleerd")
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------- aangeroepen vanuit de pagina (Bridge) ----------

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Google Maps naast de app openen (intent-URL met FLAG_ACTIVITY_LAUNCH_ADJACENT). */
    fun openIntent(url: String) {
        try {
            val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            }
            startActivity(i)
        } catch (e: Exception) {
            val fallback = try { Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") } catch (_: Exception) { null }
            if (fallback != null) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback))) } catch (_: Exception) {}
        }
    }

    fun btTap() {
        val store = Store(this)
        adoptAssociation(store)
        when {
            store.mac == null -> pair()
            !needPerms.all(::has) -> requestPermissions(askPerms, REQ_PERMS_OPEN)
            Live.running && Live.connected -> toastWeb("Verbonden met ${store.name ?: "je fiets"}. De rit start en stopt vanzelf")
            else -> { RideService.send(this, RideService.ACT_OPEN); toastWeb("Zoeken naar je fiets…") }
        }
    }

    fun toastWeb(msg: String) = main.post {
        web.evaluateJavascript("window.fietsToast&&fietsToast(${JSONObject.quote(msg)})", null)
    }

    // ---------- koppelen (eenmalig) ----------

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Fiets koppelen: toestemming, Fazua zoeken (dienst 0xFD6D, niet de Gazelle Connect-module), Android laten bevestigen. */
    fun pair() {
        if (!needPerms.all(::has)) { requestPermissions(askPerms, REQ_PERMS_PAIR); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            toastWeb("Zet Bluetooth aan en tik nog eens")
            return
        }
        toastWeb("Fiets zoeken… zet hem aan als dat nog niet zo is")
        scanForFazua(adapter) { r ->
            if (r == null) toastWeb("Fiets niet gevonden. Staat hij aan? Tik nog eens")
            else associate(r.device)
        }
    }

    /**
     * Zoekt de Fazua-aandrijving. Zonder scanfilter: sommige telefoons filteren in de Bluetooth-chip alleen
     * op het eerste advertentiepakket en missen dan de diensten uit het scan-antwoord.
     * Herkenning: dienst 0xFD6D of 0x1818 (Cycling Power). De Gazelle Connect-module heeft dezelfde naam,
     * maar adverteert alleen 0x1580. Lukt herkennen niet, dan het sterkste GZ-apparaat zonder 0x1580.
     */
    private fun scanForFazua(adapter: BluetoothAdapter, done: (ScanResult?) -> Unit) {
        val scanner = adapter.bluetoothLeScanner ?: return done(null)
        val module = ParcelUuid.fromString("00001580-0000-1000-8000-00805f9b34fb")
        val fazua = setOf(ParcelUuid(BikeGatt.FAZUA_SVC), ParcelUuid(BikeGatt.CYCLING_POWER))
        val candidates = HashMap<String, ScanResult>()
        var finished = false
        lateinit var cb: ScanCallback
        fun finish(r: ScanResult?) {
            if (finished) return
            finished = true
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            done(r)
        }
        cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord
                val uuids = rec?.serviceUuids.orEmpty().toSet() + rec?.serviceData?.keys.orEmpty()
                val name = rec?.deviceName ?: r.device.name ?: ""
                if (uuids.any { it in fazua }) { Log.d(TAG, "Fazua gevonden: ${r.device.address} $name"); finish(r); return }
                if (name.startsWith("GZ") && module !in uuids) candidates[r.device.address] = r
            }
            override fun onScanFailed(code: Int) { Log.w(TAG, "scan mislukt: $code"); finish(null) }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({ finish(candidates.values.maxByOrNull { it.rssi }) }, 15_000)
    }

    private fun associate(dev: BluetoothDevice) {
        val store = Store(this)
        store.addrType = if (Build.VERSION.SDK_INT >= 35) dev.addressType else -1
        store.name = dev.name ?: store.name
        // oude koppelingen opruimen, zodat er precies één fiets is
        cdm.myAssociations.forEach { try { cdm.disassociate(it.id) } catch (_: Exception) {} }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(dev.address).build())
            .build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm.associate(req, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) {
                try { startIntentSenderForResult(sender, REQ_ASSOC, null, 0, 0, 0) } catch (e: Exception) { toastWeb("Koppelen lukte niet") }
            }
            override fun onAssociationCreated(info: AssociationInfo) = onAssociated(info, dev)
            override fun onFailure(error: CharSequence?) { toastWeb("Koppelen lukte niet: ${error ?: "onbekende fout"}") }
        })
    }

    private fun onAssociated(info: AssociationInfo, dev: BluetoothDevice) {
        val store = Store(this)
        store.mac = info.deviceMacAddress?.toString()?.uppercase() ?: dev.address
        store.assocId = info.id
        observe(info)
        store.setPresent(true, System.currentTimeMillis())
        RideService.send(this, RideService.ACT_PRESENT)
        toastWeb("Fiets gekoppeld. Kies bij locatie 'Altijd toestaan', dan meet de app ook als hij dicht is")
        if (!has(ACCESS_BACKGROUND_LOCATION)) main.postDelayed({ requestPermissions(arrayOf(ACCESS_BACKGROUND_LOCATION), REQ_BG_LOC) }, 1500)
    }

    private fun adoptAssociation(store: Store) {
        if (store.mac != null) return
        val a = cdm.myAssociations.firstOrNull() ?: return
        store.mac = a.deviceMacAddress?.toString()?.uppercase() ?: return
        store.assocId = a.id
    }

    private fun ensureObserving() {
        val store = Store(this)
        adoptAssociation(store)
        cdm.myAssociations.firstOrNull { it.id == store.assocId }?.let { observe(it) }
    }

    private fun observe(info: AssociationInfo) {
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(info.deviceMacAddress.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "aanwezigheid volgen lukte niet: $e")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_PERMS_PAIR -> if (needPerms.all(::has)) pair() else toastWeb("Zonder toegang tot apparaten in de buurt en locatie kan de app je fiets niet vinden")
            REQ_PERMS_OPEN -> if (needPerms.all(::has)) btTap()
            REQ_BG_LOC -> RideService.send(this, RideService.ACT_OPEN)
        }
    }
}
