package ch.trainingskompass.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Puls und Schlaf im Hintergrund über Health Services (passive Daten). Die Uhr misst ohnehin laufend;
 * 4Peak bekommt die Werte gesammelt geliefert und startet keine eigene Messung.
 * Ergebnis pro Tag (Aufwachtag): Ruhepuls (Schnitt der 5 tiefsten Werte im Schlaf, ohne Schlaf 0–7 Uhr)
 * und Schlafdauer. Geht als /passive/summary ans Handy, weil Samsung Health beides nicht an Health Connect gibt.
 */
object Passive {
    private const val PREFS = "passive"
    private const val KEEP_MS = 4L * 24 * 3600 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val client = HealthServices.getClient(app).passiveMonitoringClient
                val caps = client.getCapabilitiesAsync().await()
                val types = setOf(DataType.HEART_RATE_BPM).filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(types)
                    .setShouldUserActivityInfoBeRequested(true)
                    .build()
                client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("err", "").apply()
            }.onFailure { e ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("err", e.javaClass.simpleName).apply()
            }
        }
    }

    private fun load(ctx: Context, key: String) =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]")) }.getOrDefault(JSONArray())

    @Synchronized
    fun addHr(ctx: Context, pts: List<Pair<Long, Double>>) {
        if (pts.isEmpty()) return
        val now = System.currentTimeMillis()
        val a = load(ctx, "hr")
        val out = JSONArray()
        for (i in 0 until a.length()) { val p = a.getJSONArray(i); if (now - p.getLong(0) < KEEP_MS) out.put(p) }
        // Höchstens ein Wert pro Minute (der tiefste), das reicht für den Ruhepuls
        val byMin = pts.filter { it.second in 30.0..200.0 }.groupBy { it.first / 60000 }.mapValues { (_, v) -> v.minOf { it.second } }
        byMin.forEach { (m, v) -> out.put(JSONArray().put(m * 60000).put(v)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("hr", out.toString()).apply()
    }

    @Synchronized
    fun addState(ctx: Context, t: Long, asleep: Boolean) {
        val now = System.currentTimeMillis()
        val a = load(ctx, "st")
        val out = JSONArray()
        for (i in 0 until a.length()) { val p = a.getJSONArray(i); if (now - p.getLong(0) < KEEP_MS) out.put(p) }
        val last = if (out.length() > 0) out.getJSONArray(out.length() - 1).getBoolean(1) else null
        if (last != asleep) out.put(JSONArray().put(t).put(asleep))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("st", out.toString()).apply()
    }

    /** Tageswerte berechnen und ans Handy schicken (höchstens alle 20 Minuten, ausser force) */
    fun send(ctx: Context, force: Boolean = false) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("sent", 0) < 20 * 60 * 1000) return
        p.edit().putLong("sent", now).apply()
        val json = summary(ctx).toString()
        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val req = PutDataMapRequest.create("/passive/summary").apply { dataMap.putString("json", json) }.asPutDataRequest()
                Wearable.getDataClient(app).putDataItem(req).await()
            }
        }
    }

    fun summary(ctx: Context): JSONObject {
        val zone = ZoneId.systemDefault()
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val now = System.currentTimeMillis()
        val st = load(ctx, "st")
        // Schlafabschnitte aus den Zustandswechseln
        val sleeps = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        for (i in 0 until st.length()) {
            val e = st.getJSONArray(i)
            if (e.getBoolean(1)) { if (start == null) start = e.getLong(0) }
            else if (start != null) { sleeps += start!! to e.getLong(0); start = null }
        }
        val open = start // läuft gerade noch
        val sleepMin = sleeps.filter { it.second - it.first >= 20 * 60000 }.groupBy { day(it.second) }
            .mapValues { (_, v) -> v.sumOf { (it.second - it.first) / 60000 } }
        val hr = load(ctx, "hr")
        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        for (i in 0 until hr.length()) {
            val e = hr.getJSONArray(i); val t = e.getLong(0)
            val s = sleeps.firstOrNull { t in it.first..it.second }
            val d = when {
                s != null -> day(s.second)
                open != null && t >= open -> null
                sleeps.isEmpty() && Instant.ofEpochMilli(t).atZone(zone).hour < 7 -> day(t)
                else -> null
            } ?: continue
            byDay.getOrPut(d) { mutableListOf() } += e.getDouble(1)
        }
        val rest = JSONArray()
        byDay.filterValues { it.size >= 5 }.toSortedMap().forEach { (d, v) ->
            rest.put(JSONObject().put("d", d.toString()).put("v", Math.round(v.sorted().take(5).average() * 10) / 10.0))
        }
        val sleep = JSONArray()
        sleepMin.toSortedMap().forEach { (d, m) -> sleep.put(JSONObject().put("d", d.toString()).put("min", m)) }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject().put("at", now).put("rest", rest).put("sleep", sleep)
            .put("hrN", hr.length()).put("stN", st.length()).put("err", p.getString("err", ""))
    }

    fun bootInstant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
}

class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Passive.bootInstant()
        val pts = dataPoints.getData(DataType.HEART_RATE_BPM).map { it.getTimeInstant(boot).toEpochMilli() to it.value }
        Passive.addHr(this, pts)
        Passive.send(this)
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        Passive.addState(this, info.stateChangeTime.toEpochMilli(), asleep)
        // Beim Aufwachen gleich schicken, damit der Schlaf morgens auf dem Handy ist
        Passive.send(this, force = !asleep)
    }
}

/** Nach einem Neustart der Uhr die Hintergrund-Erfassung wieder anmelden */
class PassiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Passive.register(context)
    }
}
