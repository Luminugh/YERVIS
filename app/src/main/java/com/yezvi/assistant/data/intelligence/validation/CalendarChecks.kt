package com.yezvi.assistant.data.intelligence.validation

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Deterministic checks only; no relative date resolver. Uses APIs present on Android 24. */
internal object CalendarChecks {
    private val zones by lazy { TimeZone.getAvailableIDs().toSet() }
    // java.util also exposes Java-only short aliases. Real TZDB links (CET, EST, Japan, etc.) stay valid.
    private val javaOnlyAliases = setOf("ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST",
        "CTT", "EAT", "ECT", "IET", "IST", "JST", "MIT", "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST")
    fun validZone(id: String): Boolean = id in zones && id !in javaOnlyAliases && !id.startsWith("SystemV/")
    fun validDate(text: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) return false
        val year = text.take(4).toInt()
        val month = text.substring(5, 7).toInt()
        val day = text.takeLast(2).toInt()
        if (year !in 1..9999 || month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val maxDay = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        return day in 1..maxDay
    }
    fun validTime(text: String): Boolean = Regex("([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?").matches(text)

    private fun calendar(zone: TimeZone) = GregorianCalendar(zone, Locale.ROOT).apply {
        gregorianChange = java.util.Date(Long.MIN_VALUE)
        isLenient = false
        clear()
    }
    fun utcLocalMillis(date: String, time: String = "00:00:00"): Long = calendar(TimeZone.getTimeZone("UTC")).apply {
        set(date.take(4).toInt(), date.substring(5, 7).toInt() - 1, date.takeLast(2).toInt(),
            time.take(2).toInt(), time.substring(3, 5).toInt(), if (time.length == 8) time.takeLast(2).toInt() else 0)
    }.timeInMillis

    fun weekday(date: String): String {
        val day = calendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcLocalMillis(date) }.get(Calendar.DAY_OF_WEEK)
        return listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")[day - 1]
    }

    /** Enumerate real offset candidates, rejecting gaps and exposing folds without guessing an offset. */
    fun possibleInstants(date: String, time: String, zoneId: String): Set<Long> {
        val zone = TimeZone.getTimeZone(zoneId)
        val wall = utcLocalMillis(date, time)
        val offsets = (-36..36 step 6).map { zone.getOffset(wall + it * 3600000L) }.toSet() + zone.rawOffset
        return offsets.map { wall - it }.filter { instant ->
            val local = calendar(zone).apply { timeInMillis = instant }
            local.get(Calendar.YEAR) == date.take(4).toInt() && local.get(Calendar.MONTH) + 1 == date.substring(5, 7).toInt() &&
                local.get(Calendar.DAY_OF_MONTH) == date.takeLast(2).toInt() && local.get(Calendar.HOUR_OF_DAY) == time.take(2).toInt() &&
                local.get(Calendar.MINUTE) == time.substring(3, 5).toInt() &&
                local.get(Calendar.SECOND) == if (time.length == 8) time.takeLast(2).toInt() else 0
        }.toSet()
    }
}
