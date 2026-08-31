package com.ashraffarag.sentricam.recording.overlay.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

object RecordingOverlayTimestampFormatter {
    const val PATTERN = "dd/MM/yyyy HH:mm:ss"
    private val formatter = DateTimeFormatter.ofPattern(PATTERN, Locale.US)
    private val configuredFormatters = mapOf(
        Triple(true, true, true) to DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US),
        Triple(true, true, false) to DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a", Locale.US),
        Triple(true, false, true) to DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        Triple(true, false, false) to DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        Triple(false, true, true) to DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US),
        Triple(false, true, false) to DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US),
    )

    fun format(
        timestampMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = formatter.format(Instant.ofEpochMilli(timestampMillis).atZone(zoneId))

    fun format(timestampMillis: Long, timeZone: TimeZone): String = format(timestampMillis, timeZone.toZoneId())

    fun format(
        timestampMillis: Long,
        zoneId: ZoneId,
        configuration: RecordingOverlayConfiguration,
    ): String {
        val configured = configuredFormatters[Triple(
            configuration.dateEnabled,
            configuration.timeEnabled,
            configuration.use24HourTime,
        )] ?: formatter
        return configured.format(Instant.ofEpochMilli(timestampMillis).atZone(zoneId))
    }
}

data class RecordingOverlayTimestamp(
    val epochSecond: Long,
    val zoneId: ZoneId,
    val text: String,
)

/** Wall-clock source for user-visible overlay time; frame/media timestamps never participate. */
class RecordingOverlayTimestampSource(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val currentZoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    fun current(configuration: RecordingOverlayConfiguration = RecordingOverlayConfiguration()): RecordingOverlayTimestamp {
        val now = nowMillis()
        val zone = currentZoneId()
        return RecordingOverlayTimestamp(
            epochSecond = Math.floorDiv(now, MILLIS_PER_SECOND),
            zoneId = zone,
            text = RecordingOverlayTimestampFormatter.format(now, zone, configuration),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
