package ch.trainingskompass

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Die Handy-App zeigt den Trainingskompass (assets/index.html) in einer WebView.
 * Über die Brücke "TKNative" schickt die Seite den Plan an die Uhr und holt Daten aus Health Connect.
 * Von der Uhr gespeicherte Trainings kommen als Data-Layer-Einträge unter /log/... zurück.
 */
class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var pageReady = false
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val health by lazy { HealthBridge(this) }
    private val hr by lazy {
        HrSensor(
            this,
            onHr = { bpm -> runOnUiThread { web.evaluateJavascript("window.onNativeHr && window.onNativeHr($bpm)", null) } },
            onStatus = { text, ok -> runOnUiThread { web.evaluateJavascript("window.onNativeHrStatus && window.onNativeHrStatus(${JSONObject.quote(text)},$ok)", null) } },
        )
    }

    private val btPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val btPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) hr.connect()
            else web.evaluateJavascript("window.onNativeHrStatus && window.onNativeHrStatus(${JSONObject.quote("Ohne Bluetooth-Freigabe kann die App den Gurt nicht finden.")},false)", null)
        }

    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            web.evaluateJavascript("window.onNotifyPermission && window.onNotifyPermission($ok)", null)
        }

    /** Datum aus Widget oder Erinnerung: Die Seite startet dann das Training dieses Tages. */
    private var pendingStart: String? = null

    private fun deliverStart() {
        val d = pendingStart ?: return
        if (!pageReady) return
        pendingStart = null
        web.evaluateJavascript("window.onNativeStart && window.onNativeStart(${JSONObject.quote(d)})", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Reminders.EXTRA_START)?.let { pendingStart = it; deliverStart() }
    }

    private val healthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            lifecycleScope.launch { deliverHealth() }
        }

    // Datensicherung: Die Seite liefert den Inhalt, Android fragt nach dem Speicherort.
    private var pendingBackup: String? = null

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingBackup
            pendingBackup = null
            val ok = uri != null && json != null && runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            web.evaluateJavascript("window.onBackupSaved && window.onBackupSaved(${if (ok) "true" else if (uri == null) "null" else "false"})", null)
        }

    private val openBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            web.evaluateJavascript("window.onBackupLoaded && window.onBackupLoaded(${JSONObject.quote(text ?: "")})", null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingStart = intent.getStringExtra(Reminders.EXTRA_START)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "TKNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "http" || url.scheme == "https") {
                        // Videos, Spotify und Quellen in der passenden App bzw. im Browser öffnen
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pullWatchLogs()
                    pullLive()
                    deliverStart()
                }
            }
        }
        // Die WebView beachtet kein eigenes Padding, deshalb hält ein Rahmen sie von
        // Statusleiste, Navigationstasten und Tastatur fern.
        root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Die Web-App entscheidet: Tab zurück, bei laufendem Training nur in den Hintergrund, sonst schliessen
                web.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : 'exit'") { res ->
                    when (res?.trim('"')) {
                        "ok" -> Unit
                        "bg" -> moveTaskToBack(true)
                        else -> if (web.canGoBack()) web.goBack() else finish()
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        dataClient.addListener(this)
        pullWatchLogs()
        pullMotion()
        pullLive()
        // Sport aus Samsung Health still nachladen, wenn Health Connect schon freigegeben ist
        lifecycleScope.launch { if (runCatching { health.available() && health.hasAny() }.getOrDefault(false)) deliverHealth() }
    }

    override fun onDestroy() {
        if (isFinishing) hr.disconnect()
        super.onDestroy()
    }

    override fun onPause() {
        dataClient.removeListener(this)
        super.onPause()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path?.startsWith(LOG_PATH) == true }) pullWatchLogs()
        if (events.any { it.dataItem.uri.path?.startsWith(MOTION_PATH) == true }) pullMotion()
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == LIVE_WATCH }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getString("json") }
            .lastOrNull()?.let { json -> runOnUiThread { deliverLive(json) } }
    }

    private fun deliverLive(json: String) {
        if (!pageReady) return
        web.evaluateJavascript("window.onWatchLive && window.onWatchLive(${JSONObject.quote(json)})", null)
    }

    /** Beim Öffnen: Läuft auf der Uhr ein Training, steigt die App auf dem gleichen Stand ein. */
    private fun pullLive() {
        if (!pageReady) return
        lifecycleScope.launch {
            val json = runCatching {
                val buffer = dataClient.dataItems.await()
                try {
                    buffer.filter { it.uri.path == LIVE_WATCH }.mapNotNull { DataMapItem.fromDataItem(it).dataMap.getString("json") }.lastOrNull()
                } finally {
                    buffer.release()
                }
            }.getOrNull() ?: return@launch
            deliverLive(json)
        }
    }

    /** Bewegungsdaten der Uhr (gepackte CSV je Satz) abholen, in filesDir/motion ablegen und den Eintrag löschen. */
    private fun pullMotion() {
        lifecycleScope.launch {
            val items = runCatching {
                val buffer = dataClient.dataItems.await()
                try {
                    buffer.filter { it.uri.path?.startsWith(MOTION_PATH) == true }.mapNotNull { item ->
                        val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("csv") ?: return@mapNotNull null
                        Triple(item.uri, item.uri.lastPathSegment ?: return@mapNotNull null, asset)
                    }
                } finally {
                    buffer.release()
                }
            }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch
            val dir = java.io.File(filesDir, "motion").apply { mkdirs() }
            var n = 0
            for ((uri, name, asset) in items) {
                val ok = runCatching {
                    val fd = dataClient.getFdForAsset(asset).await()
                    fd.inputStream.use { inp -> java.io.File(dir, "$name.gz").outputStream().use { inp.copyTo(it) } }
                }.isSuccess
                if (ok) { n++; runCatching { dataClient.deleteDataItems(uri).await() } }
            }
            if (n > 0 && pageReady) web.evaluateJavascript("window.onMotionCount && window.onMotionCount(${motionFiles().size})", null)
        }
    }

    private fun motionFiles() = java.io.File(filesDir, "motion").listFiles { f -> f.name.endsWith(".csv.gz") }?.sortedBy { it.name }.orEmpty()

    // Export der Bewegungsdaten als ZIP (eine CSV je Satz), Speicherort wählt Android
    private val createMotionZip =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) { web.evaluateJavascript("window.onMotionExported && window.onMotionExported(null)", null); return@registerForActivityResult }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val files = motionFiles()
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        java.util.zip.ZipOutputStream(os).use { zip ->
                            for (f in files) {
                                zip.putNextEntry(java.util.zip.ZipEntry(f.name.removeSuffix(".gz")))
                                java.util.zip.GZIPInputStream(f.inputStream()).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }.isSuccess
                runOnUiThread { web.evaluateJavascript("window.onMotionExported && window.onMotionExported(${if (ok) files.size else "false"})", null) }
            }
        }

    /** Holt alle von der Uhr gespeicherten Trainings, übergibt sie der Seite und löscht sie danach. */
    private fun pullWatchLogs() {
        if (!pageReady) return
        lifecycleScope.launch {
            val found = runCatching {
                val buffer = dataClient.dataItems.await()
                try {
                    buffer.filter { it.uri.path?.startsWith(LOG_PATH) == true }.mapNotNull { item ->
                        val json = DataMapItem.fromDataItem(item).dataMap.getString("json") ?: return@mapNotNull null
                        item.uri to json
                    }
                } finally {
                    buffer.release()
                }
            }.getOrDefault(emptyList())
            if (found.isEmpty()) return@launch

            val arr = JSONArray()
            found.forEach { (_, json) -> runCatching { arr.put(JSONObject(json)) } }
            web.evaluateJavascript("window.onWatchSessions ? window.onWatchSessions($arr) : null") { result ->
                // Nur löschen, wenn die Seite die Daten angenommen hat
                if (result != null && result != "null") {
                    lifecycleScope.launch { found.forEach { (uri, _) -> runCatching { dataClient.deleteDataItems(uri).await() } } }
                }
            }
        }
    }

    private suspend fun deliverHealth() {
        val obj = if (!health.available()) {
            JSONObject().put("error", "Health Connect ist auf diesem Handy nicht installiert oder nicht eingerichtet.")
        } else if (!health.hasAny()) {
            JSONObject().put("error", "Ohne Freigabe in Health Connect kann die App Puls, HRV und Schlaf nicht lesen.")
        } else {
            runCatching { health.read() }.getOrElse {
                JSONObject().put("error", "Die Daten aus Health Connect konnten nicht gelesen werden.")
            }
        }
        web.evaluateJavascript("window.onNativeHealth && window.onNativeHealth($obj)", null)
    }

    /** Öffnet den Barcode-Scanner der Google-Play-Dienste (braucht keine Kamera-Berechtigung der App). */
    private fun startBarcodeScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val ean = code.rawValue
                if (ean.isNullOrBlank()) sendBarcode(JSONObject().put("error", "Kein Barcode erkannt."))
                else lifecycleScope.launch { sendBarcode(FoodLookup.lookup(ean)) }
            }
            .addOnCanceledListener { sendBarcode(JSONObject().put("cancelled", true)) }
            .addOnFailureListener {
                sendBarcode(JSONObject().put("error", "Der Scanner konnte nicht starten. Prüfe, ob die Google-Play-Dienste aktuell sind."))
            }
    }

    private fun sendBarcode(obj: JSONObject) {
        web.evaluateJavascript("window.onBarcode && window.onBarcode($obj)", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun sendToWatch(json: String) {
            val request = PutDataMapRequest.create(PLAN_PATH).apply {
                dataMap.putString("json", json)
                dataMap.putLong("t", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
        }

        /** Laufendes Training an die Uhr: {date, t, t0, sets} oder {date, end:true} */
        @JavascriptInterface
        fun sendLive(json: String) {
            val request = PutDataMapRequest.create(LIVE_PHONE).apply { dataMap.putString("json", json) }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
        }

        /** Verbundene Uhren und ob 4peak darauf läuft; Antwort an window.onWatchStatus */
        @JavascriptInterface
        fun watchStatus() {
            lifecycleScope.launch {
                val nodes = runCatching { Wearable.getNodeClient(this@MainActivity).connectedNodes.await() }.getOrDefault(emptyList())
                val withApp = runCatching {
                    Wearable.getCapabilityClient(this@MainActivity)
                        .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes.map { it.id }.toSet()
                }.getOrDefault(emptySet())
                val arr = JSONArray()
                nodes.forEach { arr.put(JSONObject().put("name", it.displayName).put("near", it.isNearby).put("app", it.id in withApp)) }
                web.evaluateJavascript("window.onWatchStatus && window.onWatchStatus($arr)", null)
            }
        }

        /** Die Seite meldet ihr Design, damit Statusleiste und Ränder dazu passen. */
        @JavascriptInterface
        fun setTheme(background: String, dark: Boolean) {
            val color = runCatching { Color.parseColor(background) }.getOrNull() ?: return
            runOnUiThread {
                window.decorView.setBackgroundColor(color)
                root.setBackgroundColor(color)
                web.setBackgroundColor(color)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        /** Heutiges Training und Erinnerungen für Widget und Benachrichtigungen. */
        @JavascriptInterface
        fun setHome(json: String) {
            val ctx = applicationContext
            HomeStore.save(ctx, json)
            TodayWidget.updateAll(ctx)
            Reminders.reschedule(ctx)
        }

        /** Fragt ab Android 13 nach der Erlaubnis für Benachrichtigungen. */
        @JavascriptInterface
        fun askNotify() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) web.evaluateJavascript("window.onNotifyPermission && window.onNotifyPermission(true)", null)
                else notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        @JavascriptInterface
        fun connectHr() {
            runOnUiThread {
                if (btPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) hr.connect()
                else btPermissionRequest.launch(btPermissions)
            }
        }

        @JavascriptInterface
        fun disconnectHr() {
            runOnUiThread {
                hr.disconnect()
                web.evaluateJavascript("window.onNativeHr && window.onNativeHr(0); window.onNativeHrStatus && window.onNativeHrStatus('Getrennt',false)", null)
            }
        }

        /** Steuert die Musik-App, die gerade spielt (z. B. Spotify), wie die Tasten eines Kopfhörers. */
        @JavascriptInterface
        fun media(cmd: String) {
            val code = when (cmd) {
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = getSystemService(AudioManager::class.java) ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }

        /** Anzahl gespeicherter Bewegungsdaten-Sätze; Antwort über window.onMotionCount(n) */
        @JavascriptInterface
        fun motionCount() {
            runOnUiThread { pullMotion(); web.evaluateJavascript("window.onMotionCount && window.onMotionCount(${motionFiles().size})", null) }
        }

        @JavascriptInterface
        fun exportMotion(name: String) {
            runOnUiThread { createMotionZip.launch(name) }
        }

        @JavascriptInterface
        fun saveBackup(name: String, json: String) {
            runOnUiThread {
                pendingBackup = json
                createBackup.launch(name)
            }
        }

        @JavascriptInterface
        fun openBackup() {
            runOnUiThread { openBackupLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }

        /** KI-Trainer: Antwort kommt über window.onAiResult(id, {text}|{error}) zurück */
        @JavascriptInterface
        fun aiAsk(id: String, key: String, system: String, user: String) {
            lifecycleScope.launch {
                val res = AiCoach.ask(key, system, user)
                web.evaluateJavascript("window.onAiResult && window.onAiResult(${JSONObject.quote(id)}, ${JSONObject.quote(res.toString())})", null)
            }
        }

        @JavascriptInterface
        fun scanBarcode() {
            runOnUiThread { startBarcodeScan() }
        }

        @JavascriptInterface
        fun requestHealth() {
            runOnUiThread {
                lifecycleScope.launch {
                    when {
                        !health.available() -> deliverHealth()
                        health.hasAll() -> deliverHealth()
                        else -> healthPermissions.launch(HealthBridge.PERMISSIONS)
                    }
                }
            }
        }
    }

    companion object {
        const val PLAN_PATH = "/plan"
        /** Von der Uhr-App angemeldet (wear/res/values/wear.xml) */
        const val WATCH_CAPABILITY = "fourpeak_watch"
        const val LOG_PATH = "/log/"
        const val MOTION_PATH = "/motion/"
        const val LIVE_WATCH = "/live/watch"
        const val LIVE_PHONE = "/live/phone"
    }
}
