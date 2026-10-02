package com.botglobal.nqrb.app.ui

/** A timestamp prepared for the user's local calendar and clock. */
data class NqrbCallTime(
    val dayKey: String,
    val dayLabel: String,
    val timeLabel: String,
    val fullLabel: String,
)

fun basicCallTime(utc: String, languageTag: String): NqrbCallTime {
    val date = utc.substringBefore('T').ifBlank { utc }
    val time = utc.substringAfter('T', "").take(5)
    return NqrbCallTime(date, date, time, "$date $time".trim())
}
