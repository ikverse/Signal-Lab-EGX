package com.ikverse.signallab.egx.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Days on the Egyptian Exchange. A session is named by its date in Cairo, written as the epoch milliseconds of that
 * date's midnight UTC (the research's convention, and how the engine's daily candles are keyed). The exchange trades
 * Sunday to Thursday, 10:00 to 14:30 Cairo time.
 */
object EgxTime {
    const val DAY_MS = 86_400_000L

    /** Yahoo's offset for Cairo when an answer does not say: two hours. */
    const val DEFAULT_OFFSET_SEC = 7200

    val CAIRO: ZoneId = ZoneId.of("Africa/Cairo")

    /**
     * A session counts as closed from 15:00 Cairo time: the market closes at 14:30, and Yahoo's last bar can take a few
     * minutes to settle.
     */
    val CLOSED_AT: LocalTime = LocalTime.of(15, 0)

    /** The session day a Yahoo timestamp (unix seconds) falls on, given the answer's offset from UTC. */
    fun dayOf(unixSec: Long, offsetSec: Int): Long = Math.floorDiv(unixSec + offsetSec, 86_400L) * DAY_MS

    fun date(day: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(day, DAY_MS))

    fun day(date: LocalDate): Long = date.toEpochDay() * DAY_MS

    fun day(year: Int, month: Int, dayOfMonth: Int): Long = day(LocalDate.of(year, month, dayOfMonth))

    /** Sunday to Thursday: the days the exchange can be open. Holidays are learned from the candles (see [EgxCalendar]). */
    fun isWeekday(day: Long): Boolean = when (date(day).dayOfWeek) {
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> false
        else -> true
    }

    /** True once [day]'s session is over at [nowMs], so its candle no longer changes. */
    fun sessionClosed(day: Long, nowMs: Long): Boolean =
        nowMs >= date(day).atTime(CLOSED_AT).atZone(CAIRO).toInstant().toEpochMilli()

    /** Today's date in Cairo, as a session day (whether or not the market opens today). */
    fun today(nowMs: Long): Long =
        day(java.time.Instant.ofEpochMilli(nowMs).atZone(CAIRO).toLocalDate())
}
