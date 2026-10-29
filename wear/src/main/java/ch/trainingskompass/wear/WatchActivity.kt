package ch.trainingskompass.wear

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PickerState
import androidx.wear.compose.material.rememberPickerState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val prefs by lazy { getSharedPreferences("tk", MODE_PRIVATE) }

    private var plan by mutableStateOf<Plan?>(null)
    private var run by mutableStateOf<Run?>(null)
    private var doneDates by mutableStateOf<Set<String>>(emptySet())
    private var ambient by mutableStateOf(false)
    private var ambientTick by mutableLongStateOf(0L)
    private var pendingStart: String? = null

    // Always-on: Während des Trainings bleibt eine schlichte, stromsparende Ansicht sichtbar.
    private val ambientObserver = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambient = true; ambientTick = System.currentTimeMillis() }
        override fun onExitAmbient() { ambient = false }
        override fun onUpdateAmbient() { ambientTick = System.currentTimeMillis() }
    })

    private val sensorPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val r = run ?: return@registerForActivityResult
            if (res[Manifest.permission.BODY_SENSORS] == true || res[Manifest.permission.ACTIVITY_RECOGNITION] == true || sensorsGranted()) {
                WorkoutService.start(this, r.session.name)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nach einem Absturz zuerst den Fehler zeigen (ohne Compose, damit das sicher klappt)
        FourPeakApp.take(this)?.let { showCrash(it); return }
        lifecycle.addObserver(ambientObserver)
        doneDates = Store.done(this)
        plan = Store.plan(this)
        if (savedInstanceState == null) pendingStart = intent.getStringExtra(EXTRA_START)
        startPending()
        lifecycleScope.launch { loadPlanFromDataLayer() }
        setContent { App() }
    }

    private fun showCrash(trace: String) {
        val pad = (resources.displayMetrics.density * 28).toInt()
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        col.addView(android.widget.TextView(this).apply {
            text = "4peak ist abgestürzt. Bitte fotografieren und an Claude schicken:"
            setTextColor(android.graphics.Color.rgb(255, 200, 80)); textSize = 13f
        })
        col.addView(android.widget.TextView(this).apply {
            text = trace; setTextColor(android.graphics.Color.WHITE); textSize = 10f; setTextIsSelectable(true)
        })
        col.addView(android.widget.Button(this).apply {
            text = "Weiter"
            setOnClickListener { FourPeakApp.clear(this@WatchActivity); recreate() }
        })
        setContentView(android.widget.ScrollView(this).apply { addView(col); isFocusableInTouchMode = true; requestFocus() })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_START)?.let { pendingStart = it; startPending() }
    }

    /** Start aus Kachel oder Komplikation: gleich das Training dieses Tages öffnen. */
    private fun startPending() {
        val d = pendingStart ?: return
        val s = plan?.sessions?.firstOrNull { it.date == d } ?: return
        pendingStart = null
        if (run == null && d !in doneDates) startRun(s)
    }

    override fun onResume() {
        super.onResume()
        dataClient.addListener(this)
    }

    override fun onPause() {
        dataClient.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) { WorkoutService.stop(this); Motion.stop() }
        super.onDestroy()
    }

    // ---------- Plan vom Handy ----------

    private suspend fun loadPlanFromDataLayer() {
        runCatching {
            val buffer = dataClient.dataItems.await()
            try {
                buffer.firstOrNull { it.uri.path == PLAN_PATH }
                    ?.let { DataMapItem.fromDataItem(it).dataMap.getString("json") }
                    ?.let { setPlan(it) }
            } finally {
                buffer.release()
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == PLAN_PATH) {
                val json = DataMapItem.fromDataItem(e.dataItem).dataMap.getString("json") ?: continue
                runOnUiThread { setPlan(json) }
            }
        }
    }

    private fun setPlan(json: String) {
        val p = parsePlan(json) ?: return
        plan = p
        Store.savePlan(this, json)
        startPending()
    }

    // ---------- Training ----------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun sensorsGranted() = granted(Manifest.permission.BODY_SENSORS) || granted(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun startRun(s: Session) {
        run = Run(s)
        Motion.start(this)
        val wanted = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { granted(it) }
        if (wanted.isEmpty()) WorkoutService.start(this, s.name) else sensorPermission.launch(wanted.toTypedArray())
    }

    private fun closeRun() {
        run = null
        Motion.stop()
        WorkoutService.stop(this)
    }

    /** Speichert das Training als Data-Layer-Eintrag. Er erreicht das Handy, sobald es verbunden ist. */
    private suspend fun sendRun(r: Run) {
        val json = r.toJson(WorkoutService.hrAvg, WorkoutService.hrMax, WorkoutService.kcal.value).toString()
        val request = PutDataMapRequest.create("/log/${r.session.date}/${System.currentTimeMillis()}").apply {
            dataMap.putString("json", json)
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
        doneDates = doneDates + r.session.date
        Store.saveDone(this, doneDates)
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    /** Kurzes Klicken als Bestätigung, z. B. nach einem gespeicherten Satz. */
    private fun tick() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    // ---------- Oberfläche ----------

    @Composable
    private fun App() {
        MaterialTheme {
            Scaffold(timeText = { if (!ambient) TimeText() }) {
                val r = run
                // Pausenende ans Hintergrund-Training melden, damit es auch bei dunklem Bildschirm vibriert
                if (r != null) LaunchedEffect(r.restUntil, r.warmUntil) { WorkoutService.rest(this@WatchActivity, maxOf(r.restUntil, r.warmUntil)) }
                // Während des Aufwärmens den Puls beobachten und melden, sobald du warm bist
                if (r != null && r.warmPhase) LaunchedEffect(r) {
                    WorkoutService.hr.collect { bpm ->
                        val p = plan
                        if (r.checkWarm(bpm, p?.maxHr ?: 190, p?.restHr ?: 60)) {
                            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))
                        }
                    }
                }
                // Wiederholungszähler: zählt nur während eines Satzes, ein neuer Satz beginnt bei null
                if (r != null) LaunchedEffect(r) {
                    while (true) {
                        val it = r.item
                        val resting = r.restUntil > System.currentTimeMillis()
                        val on = !r.warmPhase && !r.finished && !r.picking && !resting && it != null && !it.cardio && Motion.supports(it.id)
                        Motion.setPhase(if (resting) "rest" else "${r.index}-${r.setNo}-${r.warmIdx}-${it?.id}", on)
                        delay(500)
                    }
                }
                when {
                    ambient && r != null && !r.finished -> AmbientScreen(r)
                    r == null -> PlanList()
                    r.warmPhase && r.warmReady && !r.warmReadyNo -> WarmReadyScreen(r)
                    r.warmPhase -> WarmupScreen(r)
                    r.finished -> FinishScreen(r)
                    r.picking -> OrderScreen(r)
                    r.restUntil > 0 -> RestScreen(r)
                    else -> ExerciseScreen(r)
                }
            }
        }
    }

    @Composable
    private fun PlanList() {
        val p = plan
        val open = p?.sessions?.filter { it.date !in doneDates }.orEmpty()
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { Text("4peak", style = MaterialTheme.typography.title3) }
            if (open.isEmpty()) {
                item {
                    Text(
                        if (p == null) "Öffne die App auf dem Handy, dann kommt dein Plan auf die Uhr."
                        else "Keine offenen Trainings. Gute Erholung!",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
            items(open) { s ->
                Chip(
                    onClick = { startRun(s) },
                    label = { Text(s.name) },
                    secondaryLabel = { Text(Store.dayLabel(s.date) + if (s.deload) " · Deload" else "") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun HeartRateLine() {
        val hr by WorkoutService.hr.collectAsState()
        val kcal by WorkoutService.kcal.collectAsState()
        if (hr <= 0) return
        val z = hrZone(hr, plan)
        Text(
            "♥ $hr" + (if (z > 0) " · Zone $z" else "") + if (kcal > 0) " · $kcal kcal" else "",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.secondary,
        )
    }

    @Composable
    private fun ExerciseScreen(r: Run) {
        val it = r.item ?: return
        if (it.cardio) {
            CardioScreen(r, it)
            return
        }
        // Drehräder wie bei Hevy: Gewicht und Wiederholungen wischen, unten zurück, speichern, weiter
        key(r.index, r.setNo, r.warmIdx) {
            val small = LocalConfiguration.current.screenWidthDp < 225 // Watch 8 mit 40 mm
            val step = it.step
            val wCount = (300.0 / step).roundToInt() + 1
            val wState = rememberPickerState(wCount, (r.weight / step).roundToInt().coerceIn(0, wCount - 1), repeatItems = false)
            val maxR = if (it.timed) 300 else 60
            val rState = rememberPickerState(maxR + 1, r.reps.coerceIn(0, maxR), repeatItems = false)
            // Gezählte Wiederholungen ins Rad übernehmen, bis du es selbst verstellst
            val counted by Motion.count.collectAsState()
            val counts = Motion.supports(it.id) && !it.timed
            var auto by remember { mutableIntStateOf(-1) }
            var manual by remember { mutableStateOf(false) }
            LaunchedEffect(counted) {
                if (counts && !manual && counted >= 2) { auto = counted.coerceAtMost(60); rState.scrollToOption(auto) }
            }
            val initial = remember { rState.selectedOption }
            LaunchedEffect(rState) {
                snapshotFlow { rState.selectedOption to rState.isScrollInProgress }.collect { (v, moving) ->
                    if (!moving && v != (if (auto >= 0) auto else initial)) manual = true
                }
            }
            // Lünette bzw. Drehrand: dreht das ausgewählte Rad (Antippen von «kg» oder «Wdh.» wechselt)
            var active by remember { mutableIntStateOf(0) }
            var acc by remember { mutableStateOf(0f) }
            val focus = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { e ->
                        val px = e.verticalScrollPixels
                        val steps = if (abs(px) >= 20f) { acc = 0f; if (px > 0) 1 else -1 } else {
                            acc += px
                            if (abs(acc) >= 60f) { val s = if (acc > 0) 1 else -1; acc = 0f; s } else 0
                        }
                        if (steps != 0) {
                            val st = if (active == 0) wState else rState
                            scope.launch { st.scrollToOption((st.selectedOption + steps).coerceIn(0, st.numberOfOptions - 1)) }
                        }
                        true
                    }
                    .focusRequester(focus)
                    .focusable()
                    .padding(start = 12.dp, end = 12.dp, top = if (small) 20.dp else 24.dp, bottom = if (small) 4.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeartRateLine()
                // Antippen öffnet die Reihenfolge (Gerät besetzt → andere Übung vorziehen)
                Text(
                    "⇅ " + it.name,
                    style = MaterialTheme.typography.caption1,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = r.index < r.items.size - 1) { r.picking = true },
                )
                Text(
                    if (r.inWarmSet) "Aufwärmsatz ${r.warmIdx + 1} von ${it.warmSets.size}" else "Satz ${r.setNo} von ${it.sets} · Ziel ${it.reps}",
                    style = MaterialTheme.typography.caption3,
                    color = if (r.inWarmSet) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurface,
                )
                if (it.timed) {
                    // Stoppuhr: läuft bis zum zweiten Tippen, vibriert beim Erreichen des Ziels
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    val goal = targetReps(it.reps)
                    LaunchedEffect(r.holdStart) {
                        var buzzed = false
                        while (r.holdStart > 0) {
                            now = System.currentTimeMillis()
                            if (!buzzed && (now - r.holdStart) / 1000 >= goal) { buzzed = true; vibrate() }
                            delay(250)
                        }
                    }
                    val sec = if (r.holdStart > 0) ((now - r.holdStart) / 1000).toInt().coerceAtLeast(0) else 0
                    CompactChip(
                        onClick = {
                            if (r.holdStart == 0L) { r.holdStart = System.currentTimeMillis(); now = r.holdStart; tick() }
                            else {
                                val done = ((System.currentTimeMillis() - r.holdStart) / 1000).toInt().coerceIn(1, maxR)
                                r.holdStart = 0
                                scope.launch { rState.scrollToOption(done) }
                                tick()
                            }
                        },
                        label = { Text(if (r.holdStart > 0) "■ $sec s / $goal s" else "▶ Stoppuhr") },
                        colors = if (r.holdStart > 0) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    )
                }
                if (counts) Text(
                    if (counted >= 2) "⟲ $counted gezählt" + (if (manual) " · von dir geändert" else "") else "⟲ zählt mit",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.secondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PickerBox(if (it.bodyweight || it.timed) "+ kg" else "kg", wState, active == 0, small, { active = 0; runCatching { focus.requestFocus() } }) { i -> formatKg(i * step) }
                    PickerBox(if (it.timed) (if (it.unilateral) "Sek./Seite" else "Sek.") else if (it.unilateral) "Wdh./Seite" else "Wdh.", rState, active == 1, small, { active = 1; runCatching { focus.requestFocus() } }) { i -> i.toString() }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { r.prev() },
                        enabled = r.index > 0,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(if (small) ButtonDefaults.ExtraSmallButtonSize else ButtonDefaults.SmallButtonSize),
                    ) { Text("‹") }
                    Button(
                        onClick = {
                            r.holdStart = 0
                            r.weight = wState.selectedOption * step
                            r.reps = rState.selectedOption
                            val ar = if (r.inWarmSet) 0 else Motion.finishSet(this@WatchActivity, it.id, r.reps, r.weight)
                            r.logSet(ar, if (ar > 0) Motion.lastTempo else 0.0, if (ar > 0) Motion.lastSlow else 0, WorkoutService.hr.value)
                            tick()
                        },
                        modifier = Modifier.size(if (small) ButtonDefaults.SmallButtonSize else ButtonDefaults.DefaultButtonSize),
                    ) { Text("✓", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { r.next() },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(if (small) ButtonDefaults.ExtraSmallButtonSize else ButtonDefaults.SmallButtonSize),
                    ) { Text("›") }
                }
            }
        }
    }

    @Composable
    private fun PickerBox(label: String, state: PickerState, selected: Boolean, small: Boolean, onSelect: () -> Unit, text: (Int) -> String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.caption3,
                color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onSelect() },
            )
            Picker(
                state = state,
                contentDescription = label,
                onSelected = onSelect,
                modifier = Modifier.size(width = if (small) 58.dp else 66.dp, height = if (small) 62.dp else 74.dp),
            ) { i ->
                Text(text(i), style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun CardioScreen(r: Run, it: Item) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { HeartRateLine() }
            item { Text(it.name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Text(it.text.ifEmpty { "Ausdauer" }, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2) }
            item {
                Chip(
                    onClick = { r.logCardio() },
                    label = { Text("Erledigt") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (r.index < r.items.size - 1) item {
                CompactChip(
                    onClick = { r.picking = true },
                    label = { Text("Reihenfolge ändern") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                CompactChip(
                    onClick = { r.next() },
                    label = { Text("Überspringen") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                CompactChip(
                    onClick = { r.end() },
                    label = { Text("Training beenden") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }

    /** Reihenfolge ändern: eine spätere Übung vorziehen oder die aktuelle ans Ende schieben. */
    @Composable
    private fun OrderScreen(r: Run) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { Text("Was machst du jetzt?", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) }
            for (j in r.index + 1 until r.items.size) {
                val x = r.items[j]
                val done = r.logs[x.id].orEmpty().count { !it.has("min") && it.optString("t") != "w" }
                item(key = "o$j-${x.id}") {
                    Chip(
                        onClick = { r.doNow(j); tick() },
                        label = { Text(x.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(if (x.cardio) "Ausdauer" else if (done > 0) "$done von ${x.sets} Sätzen" else "${x.sets} × ${x.reps}") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            r.item?.let { cur ->
                item {
                    CompactChip(
                        onClick = { r.later(); tick() },
                        label = { Text("${cur.name} ans Ende", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            item {
                CompactChip(
                    onClick = { r.picking = false },
                    label = { Text("Zurück") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }

    @Composable
    private fun RestScreen(r: Run) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(r.restUntil) {
            while (r.restUntil > 0 && System.currentTimeMillis() < r.restUntil) {
                delay(250)
                now = System.currentTimeMillis()
            }
            if (r.restUntil > 0) {
                if (!WorkoutService.active.value) vibrate() // sonst vibriert der Hintergrund-Dienst
                r.restUntil = 0
            }
        }
        val left = ((r.restUntil - now + 999) / 1000).coerceAtLeast(0)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pause", style = MaterialTheme.typography.caption1)
            Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.display2)
            HeartRateLine()
            val sum by Motion.lastSummary.collectAsState()
            sum?.let { Text(it, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp)) }
            r.item?.let {
                Text(
                    (if (r.setNo > 1) "Als Nächstes: Satz ${r.setNo} von ${it.sets}" else "Als Nächstes: ${it.name}") + if (r.index < r.items.size - 1) " ⇅" else "",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).clickable(enabled = r.index < r.items.size - 1) { r.picking = true },
                )
            }
            Spacer(Modifier.size(8.dp))
            Row {
                CompactChip(
                    onClick = { r.restUntil += 30_000 },
                    label = { Text("+30 s") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                Spacer(Modifier.width(8.dp))
                CompactChip(onClick = { r.restUntil = 0 }, label = { Text("Weiter") })
            }
            CompactChip(
                onClick = { r.end() },
                label = { Text("Training beenden") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }

    @Composable
    private fun FinishScreen(r: Run) {
        var sending by remember { mutableStateOf(false) }
        val minutes = ((System.currentTimeMillis() - r.startedAt) / 60_000).coerceAtLeast(1)
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { Text("Training beendet", style = MaterialTheme.typography.title3) }
            item {
                Text(
                    "${r.setCount} Sätze · $minutes min" +
                        (if (WorkoutService.hrMax > 0) " · max. ♥ ${WorkoutService.hrMax}" else "") +
                        if (WorkoutService.kcal.value > 0) " · ${WorkoutService.kcal.value} kcal" else "",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
            if (r.logs.isNotEmpty()) {
                item {
                    Chip(
                        onClick = {
                            if (!sending) {
                                sending = true
                                lifecycleScope.launch {
                                    sendRun(r)
                                    closeRun()
                                }
                            }
                        },
                        label = { Text(if (sending) "Wird gespeichert …" else "Speichern") },
                        secondaryLabel = { Text("Geht ans Handy, sobald es verbunden ist") },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                CompactChip(
                    onClick = { closeRun() },
                    label = { Text(if (r.logs.isEmpty()) "Zurück" else "Verwerfen") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }

    /** Hinweis: Puls zeigt, dass du warm bist. Weiter oder noch aufwärmen. */
    @Composable
    private fun WarmReadyScreen(r: Run) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { Text("Aufgewärmt ✓", style = MaterialTheme.typography.title2, color = MaterialTheme.colors.secondary) }
            item {
                Text(
                    "Dein Puls liegt seit 2 Minuten im Aufwärmbereich. Mit dem Training beginnen?",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
            item { HeartRateLine() }
            item {
                Chip(
                    onClick = { r.endWarmup(skip = false); tick() },
                    label = { Text("Weiter zum Training") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { CompactChip(onClick = { r.warmReadyNo = true }, label = { Text("Noch aufwärmen") }, colors = ChipDefaults.secondaryChipColors()) }
        }
    }

    /** Aufwärmen: Arten an- und abwählen, Übungen sehen, Timer stellen. */
    @Composable
    private fun WarmupScreen(r: Run) {
        val w = r.session.warm ?: return
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(r.warmUntil) {
            while (r.warmUntil > 0 && System.currentTimeMillis() < r.warmUntil) {
                delay(250)
                now = System.currentTimeMillis()
            }
            if (r.warmUntil > 0) {
                if (!WorkoutService.active.value) vibrate() // sonst vibriert der Hintergrund-Dienst
                r.warmUntil = 0
                now = System.currentTimeMillis()
            }
        }
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = rememberScalingLazyListState()) {
            item { Text("Aufwärmen", style = MaterialTheme.typography.title3) }
            item {
                val bpm by WorkoutService.hr.collectAsState()
                val p = plan
                val target = (p?.restHr ?: 60) + ((p?.maxHr ?: 190) - (p?.restHr ?: 60)) / 2
                Text(
                    if (bpm > 0) "♥ $bpm · warm ab $target" else "Die Uhr meldet sich, wenn du warm bist.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                if (r.warmUntil > 0) {
                    val left = ((r.warmUntil - now + 999) / 1000).coerceAtLeast(0)
                    Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.display2)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactChip(onClick = { if (r.warmMinutes > 1) r.warmMinutes-- }, label = { Text("−") }, colors = ChipDefaults.secondaryChipColors())
                        Text("${r.warmMinutes} min", style = MaterialTheme.typography.title2, modifier = Modifier.padding(horizontal = 10.dp))
                        CompactChip(onClick = { if (r.warmMinutes < 30) r.warmMinutes++ }, label = { Text("+") }, colors = ChipDefaults.secondaryChipColors())
                    }
                }
            }
            item {
                if (r.warmUntil > 0) CompactChip(onClick = { r.warmUntil += 60_000 }, label = { Text("+1 min") }, colors = ChipDefaults.secondaryChipColors())
                else Chip(onClick = { r.startWarmTimer() }, label = { Text(if (r.warmStart == 0L) "Timer starten" else "Timer neu starten") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth())
            }
            for (t in w.types) {
                val on = t.key in r.warmOn
                item {
                    Chip(
                        onClick = { r.warmOn = if (on) r.warmOn - t.key else r.warmOn + t.key },
                        label = { Text((if (on) "✓ " else "") + t.name) },
                        colors = if (on) ChipDefaults.secondaryChipColors() else ChipDefaults.childChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (on) items(t.steps) { (n, d) ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                        Text(n, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold)
                        Text(d, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
                    }
                }
            }
            item { Chip(onClick = { r.endWarmup(skip = false); tick() }, label = { Text("Fertig, los geht's") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            item { CompactChip(onClick = { r.endWarmup(skip = true) }, label = { Text("Überspringen") }, colors = ChipDefaults.secondaryChipColors()) }
        }
    }

    /** Always-on-Ansicht: schwarz, nur Umrisse und wenig Text, aktualisiert sich einmal pro Minute. */
    @Composable
    private fun AmbientScreen(r: Run) {
        val hr by WorkoutService.hr.collectAsState()
        val now = ambientTick
        val it = r.item
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                java.time.LocalTime.now().let { t -> "%d:%02d".format(t.hour, t.minute) },
                style = MaterialTheme.typography.display3,
                color = Color.White,
            )
            Spacer(Modifier.size(6.dp))
            if (it != null && !r.warmPhase) Text(it.name, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption1)
            val left = ((r.restUntil - now) / 1000).coerceAtLeast(0)
            val wleft = ((r.warmUntil - now) / 1000).coerceAtLeast(0)
            Text(
                if (r.warmPhase && r.warmReady && !r.warmReadyNo) "Aufgewärmt ✓ · tippe zum Weitermachen"
                else if (r.warmPhase) (if (r.warmUntil > 0 && wleft > 0) "Aufwärmen · noch ca. ${(wleft + 59) / 60} min" else "Aufwärmen")
                else if (r.restUntil > 0 && left > 0) "Pause · noch ca. ${(left + 59) / 60} min"
                else if (it != null && !it.cardio) "Satz ${r.setNo} von ${it.sets}" else "Training läuft",
                color = Color.Gray,
                style = MaterialTheme.typography.caption2,
            )
            if (hr > 0) Text("♥ $hr", color = Color.Gray, style = MaterialTheme.typography.caption2)
        }
    }

    private fun formatKg(w: Double): String =
        if (w % 1.0 == 0.0) w.toInt().toString() else String.format(Locale.GERMAN, "%.1f", w)

    companion object {
        const val PLAN_PATH = "/plan"
        const val EXTRA_START = "start"
    }
}
