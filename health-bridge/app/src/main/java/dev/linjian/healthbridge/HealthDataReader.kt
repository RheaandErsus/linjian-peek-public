package dev.linjian.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object HealthDataReader {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun collect(context: Context): JSONObject {
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val recentStart = now.minus(Duration.ofHours(36))
        val sevenDaysStart = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant()

        val today = collectRange(client, todayStart, now)
        val latestSleep = readLatestSleep(client, now.minus(Duration.ofDays(3)), now)
        val daily = JSONArray()
        for (offset in 6L downTo 0L) {
            val date = LocalDate.now(zone).minusDays(offset)
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(now)
            if (end > start) {
                val day = collectRange(client, start, end, activityOnly = true)
                day.put("date", date.toString())
                daily.put(day)
            }
        }

        val result = JSONObject()
        result.put("schema_version", 1)
        result.put("source", "android_health_connect")
        result.put("generated_at", now.toString())
        result.put("timezone", zone.id)
        result.put("window_start", sevenDaysStart.toString())
        result.put("today", today)
        result.put("latest_sleep", latestSleep ?: JSONObject.NULL)
        result.put("daily_7d", daily)
        result.put("recent_window_start", recentStart.toString())
        return result
    }

    private suspend fun collectRange(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        activityOnly: Boolean = false
    ): JSONObject {
        val filter = TimeRangeFilter.between(start, end)
        val out = JSONObject()
        out.put("start", start.toString())
        out.put("end", end.toString())

        val stepAggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = filter
            )
        )
        out.put("steps", stepAggregate[StepsRecord.COUNT_TOTAL] ?: 0L)

        val distances = client.readRecords(
            ReadRecordsRequest(DistanceRecord::class, filter)
        ).records
        out.put("distance_m", round1(distances.sumOf { it.distance.inMeters }))

        val activeCalories = client.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, filter)
        ).records
        out.put("active_calories_kcal", round1(activeCalories.sumOf { it.energy.inKilocalories }))

        val totalCalories = client.readRecords(
            ReadRecordsRequest(TotalCaloriesBurnedRecord::class, filter)
        ).records
        out.put("total_calories_kcal", round1(totalCalories.sumOf { it.energy.inKilocalories }))

        val exercises = client.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, filter)
        ).records
        out.put("exercise_minutes", exercises.sumOf {
            Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0)
        })
        out.put("exercise_sessions", exercises.size)

        if (activityOnly) return out

        val heartRecords = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, filter)
        ).records
        val heartSamples = heartRecords.flatMap { it.samples }
        if (heartSamples.isNotEmpty()) {
            val latest = heartSamples.maxBy { it.time }
            out.put("heart_rate_latest_bpm", latest.beatsPerMinute)
            out.put("heart_rate_latest_at", latest.time.toString())
            out.put("heart_rate_min_bpm", heartSamples.minOf { it.beatsPerMinute })
            out.put("heart_rate_max_bpm", heartSamples.maxOf { it.beatsPerMinute })
            out.put("heart_rate_avg_bpm", heartSamples.map { it.beatsPerMinute }.average().roundToInt())
        }

        val resting = client.readRecords(
            ReadRecordsRequest(RestingHeartRateRecord::class, filter)
        ).records
        resting.maxByOrNull { it.time }?.let {
            out.put("resting_heart_rate_bpm", it.beatsPerMinute)
            out.put("resting_heart_rate_at", it.time.toString())
        }

        val oxygen = client.readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, filter)
        ).records
        if (oxygen.isNotEmpty()) {
            val latest = oxygen.maxBy { it.time }
            val values = oxygen.map { it.percentage.value }
            out.put("oxygen_latest_percent", round1(latest.percentage.value))
            out.put("oxygen_latest_at", latest.time.toString())
            out.put("oxygen_min_percent", round1(values.min()))
            out.put("oxygen_avg_percent", round1(values.average()))
        }
        return out
    }

    private suspend fun readLatestSleep(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): JSONObject? {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(start, end)
            )
        ).records
        val latest = sessions.maxByOrNull { it.endTime } ?: return null
        return JSONObject().apply {
            put("start", latest.startTime.toString())
            put("end", latest.endTime.toString())
            put("duration_minutes", Duration.between(latest.startTime, latest.endTime).toMinutes())
            put("title", latest.title ?: "")
            put("notes", latest.notes ?: "")
            put("stage_count", latest.stages.size)
            put("data_origin", latest.metadata.dataOrigin.packageName)
        }
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
}
