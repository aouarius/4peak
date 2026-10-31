package ch.trainingskompass.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Eine Übung, wie die Handy-App sie schickt (siehe watchPayload() in index.html). */
data class Item(
    val id: String,
    val name: String,
    val cardio: Boolean,
    val text: String,
    val sets: Int,
    val reps: String,
    val restSec: Int,
    val weight: Double,
    val unilateral: Boolean,
    val bodyweight: Boolean,
    val step: Double,
    /** Halteübung (Plank usw.): Sekunden statt Wiederholungen, Gewicht ist Zusatzgewicht */
    val timed: Boolean = false,
    /** Aufwärmsätze (Gewicht, Wiederholungen), nur bei der ersten Hauptübung */
    val warmSets: List<Pair<Double, Int>> = emptyList(),
)

/** Eine Aufwärmart (Cardio, Mobilität, Faszienrolle, Band) mit ihren Übungen. */
data class WarmType(val key: String, val name: String, val on: Boolean, val steps: List<Pair<String, String>>)

data class Warmup(val minutes: Int, val types: List<WarmType>)

data class Session(val date: String, val name: String, val deload: Boolean, val items: List<Item>, val warm: Warmup? = null)

data class Plan(val maxHr: Int, val restHr: Int, val sessions: List<Session>)

fun parsePlan(json: String): Plan? = runCatching {
    val o = JSONObject(json)
    val ss = o.getJSONArray("sessions")
    Plan(
        maxHr = o.optInt("maxHr", 190),
        restHr = o.optInt("restHr", 60),
        sessions = (0 until ss.length()).map { i ->
            val s = ss.getJSONObject(i)
            val its = s.getJSONArray("items")
            Session(
                date = s.getString("date"),
                name = s.getString("name"),
                deload = s.optBoolean("deload"),
                warm = s.optJSONObject("warm")?.let { w ->
                    val ts = w.optJSONArray("types") ?: JSONArray()
                    Warmup(
                        minutes = w.optInt("min", 5),
                        types = (0 until ts.length()).map { t ->
                            val o = ts.getJSONObject(t)
                            val st = o.optJSONArray("steps") ?: JSONArray()
                            WarmType(
                                o.optString("k"), o.optString("n"), o.optBoolean("on"),
                                (0 until st.length()).map { k -> st.getJSONObject(k).let { it.optString("n") to it.optString("d") } },
                            )
                        },
                    )
                },
                items = (0 until its.length()).map { j ->
                    val x = its.getJSONObject(j)
                    Item(
                        id = x.getString("id"),
                        name = x.getString("n"),
                        cardio = x.optBoolean("cardio"),
                        text = x.optString("txt"),
                        sets = x.optInt("sets", 3),
                        reps = x.optString("reps"),
                        restSec = x.optInt("rest", 90),
                        weight = x.optDouble("w", 0.0),
                        unilateral = x.optBoolean("uni"),
                        bodyweight = x.optBoolean("bw"),
                        step = x.optDouble("step", 1.0).takeIf { it > 0 } ?: 1.0,
                        timed = x.optBoolean("sec", Regex("\\d\\s*s\\b").containsMatchIn(x.optString("reps"))),
                        warmSets = x.optJSONArray("warm")?.let { a ->
                            (0 until a.length()).map { k -> a.getJSONObject(k).let { it.optDouble("w", 0.0) to it.optInt("r", 5) } }
                        }.orEmpty(),
                    )
                },
            )
        },
    )
}.getOrNull()

/** "6–10" → 10, "30–60 s" → 60: das obere Ende ist das Ziel der Doppelprogression. */
fun targetReps(reps: String): Int = Regex("\\d+").findAll(reps).lastOrNull()?.value?.toIntOrNull() ?: 10

/** Herzfrequenzzone nach Karvonen, gleiche Grenzen wie in der Handy-App. */
fun hrZone(hr: Int, plan: Plan?): Int {
    val max = plan?.maxHr ?: 190
    val rest = plan?.restHr ?: 60
    val pct = (hr - rest).toDouble() / (max - rest).coerceAtLeast(1)
    return when {
        pct < 0.5 -> 0
        pct < 0.6 -> 1
        pct < 0.7 -> 2
        pct < 0.8 -> 3
        pct < 0.9 -> 4
        else -> 5
    }
}

/** Ein laufendes Training auf der Uhr. */
class Run(val session: Session) {
    var index by mutableIntStateOf(0)
    var setNo by mutableIntStateOf(1)
    var weight by mutableDoubleStateOf(0.0)
    var reps by mutableIntStateOf(10)
    var restUntil by mutableLongStateOf(0L)
    /** Länge der laufenden Pause in ms, für den Ring am Rand */
    var restLen by mutableLongStateOf(0L)
    /** Aufwärmen vor der ersten Übung: Timer, gewählte Arten, Dauer */
    var warmPhase by mutableStateOf(session.warm?.types?.isNotEmpty() == true)
    var warmMinutes by mutableIntStateOf(session.warm?.minutes?.takeIf { it > 0 } ?: 5)
    var warmUntil by mutableLongStateOf(0L)
    var warmStart by mutableLongStateOf(0L)
    var warmOn by mutableStateOf(session.warm?.types?.filter { it.on }?.map { it.key }?.toSet().orEmpty())
    var warmSec = 0L
    var warmIdx by mutableIntStateOf(0)
    /** Aufgewärmt erkannt (Puls) und ob der Hinweis weggeklickt wurde */
    var warmReady by mutableStateOf(false)
    var warmReadyNo by mutableStateOf(false)
    private var hiAt = 0L
    private var lowAt = 0L

    /**
     * Aufgewärmt, wenn der Puls mindestens 2 Minuten am Stück über 50 % der Herzfrequenzreserve liegt
     * (Beginn Zone 1) und das Aufwärmen schon 4 Minuten läuft. Einbrüche unter 15 s zählen nicht.
     * Gibt true zurück, sobald es das erste Mal erkannt wird.
     */
    fun checkWarm(bpm: Int, maxHr: Int, restHr: Int): Boolean {
        if (!warmPhase || warmReady || bpm <= 0) return false
        val now = System.currentTimeMillis()
        val target = restHr + (maxHr - restHr) / 2
        if (bpm >= target) { lowAt = 0; if (hiAt == 0L) hiAt = now }
        else if (hiAt != 0L) { if (lowAt == 0L) lowAt = now else if (now - lowAt > 15_000) { hiAt = 0; lowAt = 0 } }
        val began = if (warmStart > 0) minOf(warmStart, startedAt) else startedAt
        if (hiAt != 0L && now - hiAt >= 120_000 && now - began >= 240_000) { warmReady = true; return true }
        return false
    }
    /** Reihenfolge dieses Trainings; lässt sich unterwegs ändern, wenn ein Gerät besetzt ist.
     *  Muss vor init { prepare() } stehen, sonst ist die Liste beim Start noch leer (Absturz). */
    val items = mutableStateListOf<Item>().apply { addAll(session.items) }
    /** Liste zum Umsortieren ist offen */
    var picking by mutableStateOf(false)
    /** Bisherige Positionen fürs Zurückwischen */
    private val trail = ArrayDeque<Int>()
    private fun mark() { trail.addLast(index); if (trail.size > 40) trail.removeFirst() }

    /** Zurückwischen: Liste schliessen, Pause abbrechen oder zur vorherigen Ansicht. false = nichts zurückzunehmen */
    fun back(): Boolean {
        if (picking) { picking = false; return true }
        if (restUntil > 0 && !waiting) { restUntil = 0; return true }
        val j = trail.removeLastOrNull() ?: return false
        waiting = false
        restUntil = 0
        index = j.coerceIn(0, items.size - 1)
        prepare()
        return true
    }

    /** Übung fertig, die nächste wartet auf «Los» (Gerät evtl. noch besetzt) */
    var waiting by mutableStateOf(false)
    /** Zählt jede Änderung an den Sätzen hoch; die Uhr schickt dann den Stand ans Handy */
    var version by mutableIntStateOf(0)
    /** Stoppuhr einer Halteübung: Startzeit, 0 = läuft nicht */
    var holdStart by mutableLongStateOf(0L)
    val startedAt = System.currentTimeMillis()
    /** Ende des letzten Satzes, für die tatsächliche Pause */
    private var lastSetEnd = 0L
    private var itemStartedAt = startedAt
    val logs = linkedMapOf<String, MutableList<JSONObject>>()

    init { prepare() }

    val item: Item? get() = items.getOrNull(index)
    val finished: Boolean get() = index >= items.size
    val setCount: Int get() = logs.values.sumOf { list -> list.count { !it.has("min") && it.optString("t") != "w" } }
    val inWarmSet: Boolean get() = warmIdx < (item?.warmSets?.size ?: 0)

    fun startWarmTimer() {
        val now = System.currentTimeMillis()
        if (warmStart == 0L) warmStart = now
        warmUntil = now + warmMinutes * 60_000L
    }

    fun endWarmup(skip: Boolean) {
        warmSec = if (skip || warmStart == 0L) 0 else (System.currentTimeMillis() - warmStart) / 1000
        warmUntil = 0
        warmPhase = false
    }

    private fun prepare() {
        val it = item ?: return
        // Schon angefangene Übung (nach Umsortieren): bei den gemachten Sätzen weitermachen
        val list = logs[it.id].orEmpty()
        val work = list.filter { x -> !x.has("min") && x.optString("t") != "w" }
        warmIdx = if (work.isNotEmpty()) it.warmSets.size else list.count { x -> x.optString("t") == "w" }.coerceAtMost(it.warmSets.size)
        val w = it.warmSets.getOrNull(warmIdx)
        val last = work.lastOrNull()
        weight = w?.first ?: last?.optDouble("w") ?: it.weight
        reps = w?.second ?: last?.optInt("r") ?: targetReps(it.reps)
        setNo = work.size + 1
        itemStartedAt = System.currentTimeMillis()
        holdStart = 0
    }

    /** Übung an Position [j] jetzt machen; die aktuelle kommt direkt danach. */
    fun doNow(j: Int) {
        if (j <= index || j >= items.size) return
        items.add(index, items.removeAt(j))
        picking = false
        waiting = false
        prepare()
    }

    /** Aktuelle Übung ans Ende schieben, z. B. weil das Gerät besetzt ist. */
    fun later() {
        if (index >= items.size - 1) return
        items.add(items.removeAt(index))
        picking = false
        prepare()
    }

    /**
     * [auto] = von der Uhr gezählte Wiederholungen (0 = keine), [tempo] Sekunden pro Wiederholung,
     * [slow] wie viel langsamer die letzten Wiederholungen waren (%), [hr] Puls am Satzende.
     * Für die Auswertung durch den Trainer in der Handy-App.
     */
    fun logSet(auto: Int = 0, tempo: Double = 0.0, slow: Int = 0, hr: Int = 0) {
        val it = item ?: return
        val now = System.currentTimeMillis()
        val pause = if (lastSetEnd > 0) (now - lastSetEnd) / 1000 else 0
        lastSetEnd = now
        if (inWarmSet) {
            // Aufwärmsatz: zählt nicht als Arbeitssatz, keine Pause
            logs.getOrPut(it.id) { mutableListOf() }.add(JSONObject().put("w", weight).put("r", reps).put("t", "w"))
            version++
            warmIdx++
            val n = it.warmSets.getOrNull(warmIdx)
            weight = n?.first ?: it.weight
            reps = n?.second ?: targetReps(it.reps)
            return
        }
        val o = JSONObject().put("w", weight).put("r", reps)
        if (auto > 0) o.put("ar", auto)
        if (tempo > 0) o.put("tp", Math.round(tempo * 10) / 10.0)
        if (slow != 0) o.put("sl", slow)
        if (hr > 0) o.put("hr", hr)
        if (pause in 1..900) o.put("ps", pause)
        if (it.timed) o.put("sec", 1)
        if (it.unilateral) o.put("l", reps).put("rr", reps)
        logs.getOrPut(it.id) { mutableListOf() }.add(o)
        version++
        if (setNo >= it.sets) {
            // Nicht gleich weiterspringen: Pause läuft, die nächste Übung startet erst auf «Los»
            val j = nextOpen()
            mark()
            if (j >= 0) {
                index = j
                prepare()
                waiting = true
                restLen = if (it.restSec > 0) it.restSec * 1000L else 0
                restUntil = if (restLen > 0) System.currentTimeMillis() + restLen else 0
            } else next()
        } else {
            setNo++
            if (it.restSec > 0) { restLen = it.restSec * 1000L; restUntil = System.currentTimeMillis() + restLen }
        }
    }

    fun logCardio() {
        val it = item ?: return
        val min = ((System.currentTimeMillis() - itemStartedAt) / 60_000).coerceAtLeast(1)
        logs.getOrPut(it.id) { mutableListOf() }.add(JSONObject().put("w", 0).put("r", 0).put("min", min))
        version++
        next()
    }

    /** Nächste Übung beginnen (nach «Los») */
    fun go() {
        waiting = false
        restUntil = 0
        itemStartedAt = System.currentTimeMillis()
    }

    /** Weiter zur nächsten offenen Übung (übersprungene kommen am Schluss wieder); keine mehr offen = fertig */
    fun next() {
        waiting = false
        restUntil = 0
        val j = nextOpen()
        mark()
        index = if (j >= 0) j else items.size
        prepare()
    }

    /** Erste offene Übung nach der aktuellen, sonst die erste offene davor; -1 wenn alles erledigt */
    private fun nextOpen(): Int {
        val after = (index + 1 until items.size).firstOrNull { !complete(items[it]) }
        return after ?: (0 until minOf(index, items.size)).firstOrNull { !complete(items[it]) } ?: -1
    }

    fun prev() {
        if (index <= 0) return
        waiting = false
        restUntil = 0
        index--
        prepare()
    }

    fun end() {
        mark()
        waiting = false
        restUntil = 0
        index = items.size
    }

    /** Noch offene Übungen (inkl. der aktuellen) */
    val openCount: Int get() = items.count { !complete(it) }

    private fun complete(x: Item): Boolean {
        val l = logs[x.id].orEmpty()
        return if (x.cardio) l.isNotEmpty() else l.count { !it.has("min") && it.optString("t") != "w" } >= x.sets
    }

    /**
     * Sätze, die auf dem Handy eingetragen wurden, übernehmen: pro Übung gilt die längere Liste.
     * Ist die aktuelle Übung damit fertig, springt die Uhr zur ersten offenen. Gibt true zurück, wenn sich etwas geändert hat.
     */
    fun merge(remote: JSONObject): Boolean {
        var changed = false
        for (id in remote.keys()) {
            val a = remote.optJSONArray(id) ?: continue
            val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            if (list.size > logs[id].orEmpty().size) { logs[id] = list.toMutableList(); changed = true }
        }
        if (!changed) return false
        if (warmPhase && logs.isNotEmpty()) endWarmup(true)
        item?.let { cur -> if (complete(cur)) { val j = items.indexOfFirst { !complete(it) }; index = if (j < 0) items.size else j; restUntil = 0; waiting = false } }
        prepare()
        version++
        return true
    }

    /** Aktueller Stand fürs Handy */
    fun liveJson(): JSONObject {
        val sets = JSONObject()
        logs.forEach { (id, list) -> sets.put(id, JSONArray(list)) }
        return JSONObject().put("date", session.date).put("src", "watch").put("t", System.currentTimeMillis()).put("t0", startedAt).put("sets", sets)
    }

    fun toJson(hrAvg: Int, hrMax: Int, kcal: Int = 0): JSONObject {
        val sets = JSONObject()
        logs.forEach { (id, list) -> sets.put(id, JSONArray(list)) }
        val o = JSONObject()
            .put("date", session.date)
            .put("at", java.time.Instant.now().toString())
            .put("min", ((System.currentTimeMillis() - startedAt) / 60_000).coerceAtLeast(1))
            .put("sets", sets)
        if (hrMax > 0) o.put("hr", JSONObject().put("avg", hrAvg).put("max", hrMax))
        if (kcal > 0) o.put("kcal", kcal)
        if (warmSec >= 30) o.put("warm", warmSec)
        return o
    }
}
