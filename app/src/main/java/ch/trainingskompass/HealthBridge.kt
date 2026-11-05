package ch.trainingskompass

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Liest Ruhepuls, HRV, Schlaf, Sport-Einheiten, Gewicht und Körperfett der letzten 14 Tage aus Health Connect (dorthin schreibt Samsung Health). */
class HealthBridge(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun available(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasAll(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Körperwerte, für die noch keine Freigabe da ist (deutsche Namen für die Anzeige) */
    suspend fun missingBody(): List<String> {
        val g = client.permissionController.getGrantedPermissions()
        return listOf(
            WeightRecord::class to "Gewicht", BodyFatRecord::class to "Körperfett", LeanBodyMassRecord::class to "Fettfreie Masse",
            BodyWaterMassRecord::class to "Körperwasser", BoneMassRecord::class to "Knochenmasse", BasalMetabolicRateRecord::class to "Grundumsatz",
        ).filter { HealthPermission.getReadPermission(it.first) !in g }.map { it.second }
    }

    /** Fehlende Freigaben für Erholungsdaten (deutsche Namen für die Anzeige) */
    suspend fun missingRecovery(): List<String> {
        val g = client.permissionController.getGrantedPermissions()
        return listOf(
            RestingHeartRateRecord::class to "Ruhepuls", HeartRateVariabilityRmssdRecord::class to "HRV",
            SleepSessionRecord::class to "Schlaf", HeartRateRecord::class to "Puls",
        ).filter { HealthPermission.getReadPermission(it.first) !in g }.map { it.second }
    }

    suspend fun hasAny(): Boolean =
        client.permissionController.getGrantedPermissions().any { it in PERMISSIONS }

    suspend fun read(): JSONObject {
        val end = Instant.now()
        val range = TimeRangeFilter.between(end.minus(14, ChronoUnit.DAYS), end)
        val zone = ZoneId.systemDefault()
        fun day(t: Instant): LocalDate = t.atZone(zone).toLocalDate()

        // Befund für die Anzeige: Anzahl Einträge und Quellen pro Datenart, oder der Fehler
        val hdiag = JSONObject()
        suspend fun <T : androidx.health.connect.client.records.Record> recs(cls: kotlin.reflect.KClass<T>, label: String, r: TimeRangeFilter = range): List<T> =
            runCatching {
                val all = mutableListOf<T>()
                var token: String? = null
                do {
                    val resp = client.readRecords(ReadRecordsRequest(cls, timeRangeFilter = r, pageSize = 1000, pageToken = token))
                    all += resp.records
                    token = resp.pageToken
                } while (token != null && all.size < 20000)
                all.toList()
            }
                .onSuccess { l -> hdiag.put(label, "${l.size}" + if (l.isNotEmpty()) " (" + l.map { it.metadata.dataOrigin.packageName.substringAfterLast('.') }.distinct().joinToString() + ")" else "") }
                .onFailure { e -> hdiag.put(label, "Fehler: ${e.javaClass.simpleName}") }
                .getOrDefault(emptyList())

        val restRec = recs(RestingHeartRateRecord::class, "Ruhepuls")
            .groupBy { day(it.time) }
            .mapValues { (_, r) -> r.map { it.beatsPerMinute.toDouble() }.average() }

        val hrv = recs(HeartRateVariabilityRmssdRecord::class, "HRV")
            .groupBy { day(it.time) }
            .mapValues { (_, r) -> r.map { it.heartRateVariabilityMillis }.average() }

        // Schlaf zählt für den Tag, an dem man aufwacht
        val sleepRec = recs(SleepSessionRecord::class, "Schlaf")
        val sleep = sleepRec
            .groupBy { day(it.endTime) }
            .mapValues { (_, r) -> r.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toDouble() }

        // Samsung Health schreibt meist keinen Ruhepuls, aber laufend Pulswerte. Daraus ein Ruhepuls pro Tag:
        // Schnitt der 5 tiefsten Werte im Schlaf (ohne Schlafdaten: 0–7 Uhr), der Tag ist der Aufwachtag.
        val rest = if (restRec.size >= 3) restRec else {
            val hr = recs(HeartRateRecord::class, "Puls (Einzelwerte)")
            val samples = hr.flatMap { it.samples }
            val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
            for (s in samples) {
                val inSleep = sleepRec.firstOrNull { !s.time.isBefore(it.startTime) && !s.time.isAfter(it.endTime) }
                val d = when {
                    inSleep != null -> day(inSleep.endTime)
                    sleepRec.isEmpty() && s.time.atZone(zone).hour < 7 -> day(s.time)
                    else -> null
                } ?: continue
                byDay.getOrPut(d) { mutableListOf() } += s.beatsPerMinute.toDouble()
            }
            val calc = byDay.filterValues { it.size >= 5 }.mapValues { (_, v) -> v.sorted().take(5).average() }
            if (calc.isNotEmpty()) hdiag.put("Ruhepuls", (hdiag.optString("Ruhepuls") + ", aus Nachtpuls berechnet für ${calc.size} Tage").trimStart(',', ' '))
            restRec + calc.filterKeys { it !in restRec }
        }

        // Sport-Einheiten, z. B. von Samsung Health erkannt oder auf der Uhr gestartet; mit Puls und Kalorien
        val ex = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = range)).records
                .filter { it.metadata.dataOrigin.packageName != context.packageName }
                .forEach { r ->
                    val min = Duration.between(r.startTime, r.endTime).toMinutes()
                    if (min < 5) return@forEach
                    val agg = runCatching {
                        client.aggregate(AggregateRequest(
                            setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            TimeRangeFilter.between(r.startTime, r.endTime),
                        ))
                    }.getOrNull()
                    val (name, kind) = exName(r.exerciseType)
                    ex.put(JSONObject()
                        .put("id", r.metadata.id)
                        .put("d", day(r.startTime).toString())
                        .put("start", r.startTime.toString())
                        .put("min", min)
                        .put("n", r.title?.takeIf { it.isNotBlank() } ?: name)
                        .put("k", kind)
                        .put("avg", agg?.get(HeartRateRecord.BPM_AVG) ?: 0)
                        .put("max", agg?.get(HeartRateRecord.BPM_MAX) ?: 0)
                        .put("kcal", agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories?.roundToInt() ?: 0)
                        .put("src", r.metadata.dataOrigin.packageName))
                }
        }

        // Gewicht und Körperfett der letzten 120 Tage, z. B. von einer Withings-Waage; pro Tag die letzte Messung
        val longRange = TimeRangeFilter.between(end.minus(120, ChronoUnit.DAYS), end)
        // Kurzer Befund für die Anzeige, falls nichts ankommt: Anzahl Messungen, Quellen, Fehler
        val diag = JSONObject()
        suspend fun <T : androidx.health.connect.client.records.Record> body(cls: kotlin.reflect.KClass<T>, label: String): List<T> =
            runCatching { client.readRecords(ReadRecordsRequest(cls, timeRangeFilter = longRange, pageSize = 1000)).records }
                .onSuccess { r -> diag.put(label, "${r.size}" + if (r.isNotEmpty()) " (" + r.map { it.metadata.dataOrigin.packageName.substringAfterLast('.') }.distinct().joinToString() + ")" else "") }
                .onFailure { e -> diag.put(label, "Fehler: ${e.javaClass.simpleName}") }
                .getOrDefault(emptyList())
        val weight = body(WeightRecord::class, "Gewicht")
                .groupBy { day(it.time) }
                .mapValues { (_, r) -> r.maxBy { it.time }.weight.inKilograms }
        val fat = body(BodyFatRecord::class, "Körperfett")
                .groupBy { day(it.time) }
                .mapValues { (_, r) -> r.maxBy { it.time }.percentage.value }
        // Weitere Werte der Waage (Withings & Co.): fettfreie Masse, Wasser, Knochen, Grundumsatz
        val lean = body(LeanBodyMassRecord::class, "Fettfreie Masse")
                .groupBy { day(it.time) }.mapValues { (_, r) -> r.maxBy { it.time }.mass.inKilograms }
        val water = body(BodyWaterMassRecord::class, "Körperwasser")
                .groupBy { day(it.time) }.mapValues { (_, r) -> r.maxBy { it.time }.mass.inKilograms }
        val bone = body(BoneMassRecord::class, "Knochenmasse")
                .groupBy { day(it.time) }.mapValues { (_, r) -> r.maxBy { it.time }.mass.inKilograms }
        val bmr = body(BasalMetabolicRateRecord::class, "Grundumsatz")
                .groupBy { day(it.time) }.mapValues { (_, r) -> r.maxBy { it.time }.basalMetabolicRate.inKilocaloriesPerDay }
        val bw = JSONArray()
        (weight.keys + fat.keys + lean.keys + water.keys + bone.keys).toSortedSet().forEach { d ->
            val o = JSONObject().put("d", d.toString())
            weight[d]?.let { o.put("v", Math.round(it * 10) / 10.0) }
            fat[d]?.let { o.put("f", Math.round(it * 10) / 10.0) }
            lean[d]?.let { o.put("lean", Math.round(it * 10) / 10.0) }
            water[d]?.let { o.put("water", Math.round(it * 10) / 10.0) }
            bone[d]?.let { o.put("bone", Math.round(it * 10) / 10.0) }
            bmr[d]?.let { o.put("bmr", Math.round(it)) }
            bw.put(o)
        }

        return JSONObject()
            .put("ex", ex)
            .put("bw", bw)
            .put("bwdiag", diag)
            .put("hdiag", hdiag)
            .put("rest", series(rest, "v"))
            .put("hrv", series(hrv, "v"))
            .put("sleep", series(sleep, "min"))
    }

    /** Sportart auf Deutsch und ob es Kraft oder Ausdauer ist */
    private fun exName(t: Int): Pair<String, String> = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Laufen" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Gehen" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Wandern" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Radfahren" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Indoor-Cycling" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Schwimmen" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Rudern" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Crosstrainer" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Treppensteigen" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Fussball" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Skifahren" to "cardio"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Krafttraining" to "kraft"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics" to "kraft"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga" to "mob"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates" to "mob"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Dehnen" to "mob"
        else -> "Sport" to "cardio"
    }

    private fun series(map: Map<LocalDate, Double>, key: String): JSONArray {
        val arr = JSONArray()
        map.toSortedMap().forEach { (d, v) ->
            arr.put(JSONObject().put("d", d.toString()).put(key, Math.round(v * 10) / 10.0))
        }
        return arr
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        )
    }
}
