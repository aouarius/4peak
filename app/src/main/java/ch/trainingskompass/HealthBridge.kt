package ch.trainingskompass

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
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

/** Liest Ruhepuls, HRV und Schlaf der letzten 14 Tage aus Health Connect (dorthin schreibt die Uhr). */
class HealthBridge(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun available(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasAll(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    suspend fun hasAny(): Boolean =
        client.permissionController.getGrantedPermissions().any { it in PERMISSIONS }

    suspend fun read(): JSONObject {
        val end = Instant.now()
        val range = TimeRangeFilter.between(end.minus(14, ChronoUnit.DAYS), end)
        val zone = ZoneId.systemDefault()
        fun day(t: Instant): LocalDate = t.atZone(zone).toLocalDate()

        val rest = runCatching {
            client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, timeRangeFilter = range)).records
                .groupBy { day(it.time) }
                .mapValues { (_, r) -> r.map { it.beatsPerMinute.toDouble() }.average() }
        }.getOrDefault(emptyMap())

        val hrv = runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRangeFilter = range)).records
                .groupBy { day(it.time) }
                .mapValues { (_, r) -> r.map { it.heartRateVariabilityMillis }.average() }
        }.getOrDefault(emptyMap())

        // Schlaf zählt für den Tag, an dem man aufwacht
        val sleep = runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = range)).records
                .groupBy { day(it.endTime) }
                .mapValues { (_, r) -> r.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toDouble() }
        }.getOrDefault(emptyMap())

        return JSONObject()
            .put("rest", series(rest, "v"))
            .put("hrv", series(hrv, "v"))
            .put("sleep", series(sleep, "min"))
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
        )
    }
}
