package ch.trainingskompass.wear

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener, SensorEventListener {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val prefs by lazy { getSharedPreferences("tk", MODE_PRIVATE) }

    private var plan by mutableStateOf<Plan?>(null)
    private var run by mutableStateOf<Run?>(null)
    private var doneDates by mutableStateOf<Set<String>>(emptySet())
    private var hr by mutableIntStateOf(0)
    private var hrSum = 0L
    private var hrCount = 0L
    private var hrMax = 0

    private val sensorPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && run != null) startHeartRate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doneDates = prefs.getStringSet("done", emptySet())!!.toSet()
        prefs.getString("plan", null)?.let { plan = parsePlan(it) }
        lifecycleScope.launch { loadPlanFromDataLayer() }
        setContent { App() }
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
        stopHeartRate()
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
        prefs.edit().putString("plan", json).apply()
    }

    // ---------- Training ----------

    private fun startRun(s: Session) {
        hrSum = 0; hrCount = 0; hrMax = 0
        run = Run(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            startHeartRate()
        } else {
            sensorPermission.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun closeRun() {
        run = null
        stopHeartRate()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Speichert das Training als Data-Layer-Eintrag. Er erreicht das Handy, sobald es verbunden ist. */
    private suspend fun sendRun(r: Run) {
        val avg = if (hrCount > 0) (hrSum / hrCount).toInt() else 0
        val json = r.toJson(avg, hrMax).toString()
        val request = PutDataMapRequest.create("/log/${r.session.date}/${System.currentTimeMillis()}").apply {
            dataMap.putString("json", json)
        }.asPutDataRequest().setUrgent()
        runCatching { dataClient.putDataItem(request).await() }
        doneDates = doneDates + r.session.date
        prefs.edit().putStringSet("done", doneDates).apply()
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    // ---------- Puls ----------

    private fun startHeartRate() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopHeartRate() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        hr = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values.firstOrNull()?.roundToInt() ?: return
        if (v <= 0) return
        hr = v
        if (run != null) {
            hrSum += v; hrCount++
            if (v > hrMax) hrMax = v
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- Oberfläche ----------

    @Composable
    private fun App() {
        MaterialTheme {
            Scaffold(timeText = { TimeText() }) {
                val r = run
                when {
                    r == null -> PlanList()
                    r.finished -> FinishScreen(r)
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
                    secondaryLabel = { Text(dayLabel(s.date) + if (s.deload) " · Deload" else "") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun HeartRateLine() {
        if (hr <= 0) return
        val z = hrZone(hr, plan)
        Text(
            "♥ $hr" + if (z > 0) " · Zone $z" else "",
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
        key(r.index, r.setNo) {
            val step = it.step
            val wCount = (300.0 / step).roundToInt() + 1
            val wState = rememberPickerState(wCount, (r.weight / step).roundToInt().coerceIn(0, wCount - 1), repeatItems = false)
            val rState = rememberPickerState(61, r.reps.coerceIn(0, 60), repeatItems = false)
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeartRateLine()
                Text(
                    it.name,
                    style = MaterialTheme.typography.caption1,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Satz ${r.setNo} von ${it.sets} · Ziel ${it.reps}", style = MaterialTheme.typography.caption3)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PickerBox(if (it.bodyweight) "+ kg" else "kg", wState) { i -> formatKg(i * step) }
                    PickerBox(if (it.unilateral) "Wdh./Seite" else "Wdh.", rState) { i -> i.toString() }
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
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    ) { Text("‹") }
                    Button(
                        onClick = {
                            r.weight = wState.selectedOption * step
                            r.reps = rState.selectedOption
                            r.logSet()
                        },
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) { Text("✓", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { r.next() },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    ) { Text("›") }
                }
            }
        }
    }

    @Composable
    private fun PickerBox(label: String, state: PickerState, text: (Int) -> String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.caption3)
            Picker(
                state = state,
                contentDescription = label,
                modifier = Modifier.size(width = 66.dp, height = 74.dp),
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

    @Composable
    private fun RestScreen(r: Run) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(r.restUntil) {
            while (r.restUntil > 0 && System.currentTimeMillis() < r.restUntil) {
                delay(250)
                now = System.currentTimeMillis()
            }
            if (r.restUntil > 0) {
                vibrate()
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
            r.item?.let { Text("Als Nächstes: Satz ${r.setNo} von ${it.sets}", style = MaterialTheme.typography.caption2) }
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
                    "${r.setCount} Sätze · $minutes min" + if (hrMax > 0) " · max. ♥ $hrMax" else "",
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

    private fun dayLabel(date: String): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val today = LocalDate.now()
        return when (d) {
            today -> "Heute"
            today.plusDays(1) -> "Morgen"
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN) + ", ${d.dayOfMonth}.${d.monthValue}."
        }
    }

    private fun formatKg(w: Double): String =
        if (w % 1.0 == 0.0) w.toInt().toString() else String.format(Locale.GERMAN, "%.1f", w)

    companion object {
        const val PLAN_PATH = "/plan"
    }
}
