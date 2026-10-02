package com.botglobal.nqrb

import com.botglobal.nqrb.app.ui.NqrbCallTime
import com.botglobal.nqrb.app.ui.basicCallTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val utcTimestamp = Regex(
    """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""",
)

/** Uses the pre-Android-8 calendar API so call history works on the app's API 24 minimum. */
internal fun androidCallTime(utc: String, languageTag: String): NqrbCallTime = runCatching {
    val values = requireNotNull(utcTimestamp.matchEntire(utc)).groupValues
    val instant = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        isLenient = false
        clear()
        set(values[1].toInt(), values[2].toInt() - 1, values[3].toInt(), values[4].toInt(), values[5].toInt(), values[6].toInt())
        set(Calendar.MILLISECOND, values[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0)
    }.timeInMillis
    val zone = values[8]
    val offsetMinutes = if (zone == "Z") 0 else {
        val direction = if (zone[0] == '+') 1 else -1
        direction * (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt())
    }
    val local = Calendar.getInstance().apply { timeInMillis = instant - offsetMinutes * 60_000L }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val key = calendarKey(local)
    val day = when (key) {
        calendarKey(today) -> if (languageTag.startsWith("ar")) "اليوم" else "Today"
        calendarKey(yesterday) -> if (languageTag.startsWith("ar")) "أمس" else "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag(languageTag)).format(local.time)
    }
    val time = SimpleDateFormat("h:mm a", Locale.forLanguageTag(languageTag)).format(local.time)
    NqrbCallTime(key, day, time, "$day · $time")
}.getOrElse { basicCallTime(utc, languageTag) }

private fun calendarKey(day: Calendar): String =
    "%04d-%02d-%02d".format(Locale.US, day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
