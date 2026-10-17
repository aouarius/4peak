package ch.trainingskompass.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
)

data class Session(val date: String, val name: String, val deload: Boolean, val items: List<Item>)

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
    val startedAt = System.currentTimeMillis()
    private var itemStartedAt = startedAt
    val logs = linkedMapOf<String, MutableList<JSONObject>>()

    init { prepare() }

    val item: Item? get() = session.items.getOrNull(index)
    val finished: Boolean get() = index >= session.items.size
    val setCount: Int get() = logs.values.sumOf { list -> list.count { !it.has("min") } }

    private fun prepare() {
        val it = item ?: return
        weight = it.weight
        reps = targetReps(it.reps)
        setNo = 1
        itemStartedAt = System.currentTimeMillis()
    }

    fun logSet() {
        val it = item ?: return
        val o = JSONObject().put("w", weight).put("r", reps)
        if (it.unilateral) o.put("l", reps).put("rr", reps)
        logs.getOrPut(it.id) { mutableListOf() }.add(o)
        if (setNo >= it.sets) next()
        else {
            setNo++
            if (it.restSec > 0) restUntil = System.currentTimeMillis() + it.restSec * 1000L
        }
    }

    fun logCardio() {
        val it = item ?: return
        val min = ((System.currentTimeMillis() - itemStartedAt) / 60_000).coerceAtLeast(1)
        logs.getOrPut(it.id) { mutableListOf() }.add(JSONObject().put("w", 0).put("r", 0).put("min", min))
        next()
    }

    fun next() {
        restUntil = 0
        index++
        prepare()
    }

    fun prev() {
        if (index <= 0) return
        restUntil = 0
        index--
        prepare()
    }

    fun end() {
        restUntil = 0
        index = session.items.size
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
        return o
    }
}
