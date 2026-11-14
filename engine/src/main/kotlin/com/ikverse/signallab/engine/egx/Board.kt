package com.ikverse.signallab.engine.egx

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

internal const val DAY_MS = 86_400_000L

/**
 * One stock's daily sessions on the market calendar, as parallel arrays as long as the calendar. Before its first session
 * and after its last every price is NaN. A session it did not trade has a flat candle at its last close and no turnover,
 * as the research's `prepare.py` writes it, so [traded] is false there while [alive] stays true.
 */
class Stock(
    val code: String,
    /** Index of its first session on the calendar. */
    val first: Int,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    /** Turnover in pounds; 0 on a session it did not trade. */
    val turnover: DoubleArray,
) {
    fun alive(k: Int): Boolean = k in close.indices && !close[k].isNaN()

    fun traded(k: Int): Boolean = alive(k) && turnover[k] > 0

    internal fun cut(n: Int) = Stock(code, first, open.copyOf(n), high.copyOf(n), low.copyOf(n), close.copyOf(n), turnover.copyOf(n))

    /** Turnover over the 30 sessions ending at each one. */
    val turnover30: DoubleArray by lazy {
        val out = DoubleArray(close.size)
        var s = 0.0
        for (k in close.indices) {
            s += turnover[k]
            if (k >= 30) s -= turnover[k - 30]
            out[k] = s
        }
        out
    }

    /**
     * The 14-session average true range at each session, NaN until 14 ranges exist. Computed exactly as the research's
     * scripts do, so the levels and stops land where they were tested.
     */
    val atr: DoubleArray by lazy {
        val n = close.size
        val tr = DoubleArray(n) { Double.NaN }
        val out = DoubleArray(n) { Double.NaN }
        for (k in first + 1 until n) {
            if (close[k].isNaN()) break
            tr[k] = maxOf(high[k] - low[k], kotlin.math.abs(high[k] - close[k - 1]), kotlin.math.abs(low[k] - close[k - 1]))
            if (k - first >= 14) {
                var s = 0.0
                for (j in k - 13..k) s += tr[j]
                out[k] = s / 14
            }
        }
        out
    }

    private val averages = HashMap<Int, DoubleArray>()

    /** The [days]-session simple average of the close, NaN until it exists (a running sum, as in the research). */
    fun average(days: Int): DoubleArray = averages.getOrPut(days) {
        val out = DoubleArray(close.size) { Double.NaN }
        var s = 0.0
        var m = 0
        for (k in first until close.size) {
            if (close[k].isNaN()) break
            s += close[k]
            if (m >= days) s -= close[k - days]
            if (m >= days - 1) out[k] = s / days
            m++
        }
        out
    }

    /** How many of the [n] sessions ending at [k] it traded. */
    fun tradedOf(k: Int, n: Int): Int {
        var c = 0
        for (j in k - n + 1..k) if (j >= 0 && traded(j)) c++
        return c
    }
}

/**
 * Every stock on one market calendar (session open times, oldest first). A strategy deciding at session k may read only
 * sessions up to k; [cut] gives the board as it stood then, which the invariance tests hold every strategy to.
 *
 * [holidays] are the Sunday-to-Thursday days the exchange is closed. The exchange publishes them ahead, so a rule may
 * know them for days after k: that is how the evening pass knows the month ends tonight. The app keeps them in a table
 * the owner can correct; the research takes them from the weekdays the market did not open ([holidaysOf]).
 */
class Board(val calendar: LongArray, val stocks: Map<String, Stock>, val holidays: Set<LocalDate> = emptySet()) {
    val size: Int get() = calendar.size

    private val dates = Array(calendar.size) { LocalDate.ofEpochDay(Math.floorDiv(calendar[it], DAY_MS)) }

    fun date(k: Int): LocalDate = dates[k]

    /** The board as it stood after the close of session [n] - 1. */
    fun cut(n: Int): Board = Board(calendar.copyOf(n), stocks.values.filter { it.first < n }.associate { it.code to it.cut(n) }, holidays)

    /** The day of the session after session [k]: the next weekday that is not a holiday (known ahead, never from tomorrow's candle). */
    fun nextSessionDay(k: Int): LocalDate = Sessions.nextSession(date(k), holidays)

    private val universes = HashMap<Int, List<String>>()

    /**
     * The stocks watched at session [k], busiest first by 30-session turnover: alive, listed at least 180 days, and traded
     * in the last 30 sessions. Ties go as in the research (the later code first). [size] keeps the busiest so many.
     */
    fun universe(k: Int, size: Int): List<String> = universes.getOrPut(k) {
        stocks.values.filter { s -> s.alive(k) && calendar[k] - calendar[s.first] >= 180 * DAY_MS && s.turnover30[k] > 0 }
            .sortedWith(compareByDescending<Stock> { it.turnover30[k] }.thenByDescending { it.code }).map { it.code }
    }.let { if (it.size > size) it.subList(0, size) else it }

    /** The rank of [code] at session [k] among every stock watched (1 = the busiest), or null when it is not watched. */
    fun rank(k: Int, code: String): Int? = universe(k, Int.MAX_VALUE).indexOf(code).takeIf { it >= 0 }?.plus(1)

    /** The index of the first session at or after [time], or [size] when there is none yet. */
    fun sessionAtOrAfter(time: Long): Int {
        var lo = 0
        var hi = calendar.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (calendar[mid] < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * How many sessions from session [k] until the day [time]: 1 when it is the next session. A day inside the calendar
     * is counted on it; a day beyond the last session is counted on Sunday-to-Thursday days that are not [holidays].
     * 0 or less when the day is not after session [k].
     */
    fun sessionsUntil(k: Int, time: Long): Int {
        if (time <= calendar[k]) return sessionAtOrAfter(time) - k
        val last = calendar.size - 1
        if (time <= calendar[last]) return sessionAtOrAfter(time) - k
        return last - k + Sessions.sessionsBetween(date(last), LocalDate.ofEpochDay(Math.floorDiv(time, DAY_MS)), holidays)
    }

    /**
     * The month that ends with session [k], or null. Session k ends a month when the next session day, from the weekdays
     * and the known [holidays], falls in another month. When an unannounced closing ended the month early, the first
     * session of the next month ends it instead, a session late.
     */
    fun monthEnding(k: Int): YearMonth? {
        val d = date(k)
        if (nextSessionDay(k).monthValue != d.monthValue) return YearMonth.from(d)
        if (k > 0) {
            val p = date(k - 1)
            if (p.monthValue != d.monthValue && nextSessionDay(k - 1).monthValue == p.monthValue) return YearMonth.from(p)
        }
        return null
    }

    companion object {
        /** The weekdays between the first and last session on which the market did not open: the past's holidays. */
        fun holidaysOf(calendar: LongArray): Set<LocalDate> {
            if (calendar.isEmpty()) return emptySet()
            val open = calendar.map { LocalDate.ofEpochDay(Math.floorDiv(it, DAY_MS)) }.toHashSet()
            val out = HashSet<LocalDate>()
            var x = LocalDate.ofEpochDay(Math.floorDiv(calendar.first(), DAY_MS))
            val end = LocalDate.ofEpochDay(Math.floorDiv(calendar.last(), DAY_MS))
            while (!x.isAfter(end)) {
                if (Sessions.isWeekday(x) && x !in open) out.add(x)
                x = x.plusDays(1)
            }
            return out
        }
    }
}

/** The Egyptian Exchange's week: Sunday to Thursday. */
object Sessions {
    fun isWeekday(d: LocalDate): Boolean = d.dayOfWeek != DayOfWeek.FRIDAY && d.dayOfWeek != DayOfWeek.SATURDAY

    fun isSession(d: LocalDate, holidays: Set<LocalDate>): Boolean = isWeekday(d) && d !in holidays

    fun nextSession(d: LocalDate, holidays: Set<LocalDate> = emptySet()): LocalDate {
        var x = d.plusDays(1)
        while (!isSession(x, holidays)) x = x.plusDays(1)
        return x
    }

    /** Session days in (from, to]. */
    fun sessionsBetween(from: LocalDate, to: LocalDate, holidays: Set<LocalDate> = emptySet()): Int {
        var n = 0
        var x = from.plusDays(1)
        while (!x.isAfter(to)) {
            if (isSession(x, holidays)) n++
            x = x.plusDays(1)
        }
        return n
    }
}
