package com.ikverse.signallab.egx.data

/**
 * The trading calendar, taken from the candles themselves: a session is a Sunday-to-Thursday day on which enough stocks
 * traded. A weekday missing from it is a holiday (or a day the exchange was shut). No free source publishes EGX holidays
 * ahead, so days after the last known session are assumed to be Sunday to Thursday ([shift], [sessionsAfter]).
 */
class EgxCalendar(sessions: Collection<Long>) {
    /** Every known session, oldest first. */
    val sessions: LongArray = sessions.filter(EgxTime::isWeekday).distinct().sorted().toLongArray()

    val size: Int get() = sessions.size

    val first: Long? get() = sessions.firstOrNull()

    val last: Long? get() = sessions.lastOrNull()

    fun contains(day: Long): Boolean = sessions.binarySearch(day) >= 0

    fun indexOf(day: Long): Int = sessions.binarySearch(day).let { if (it >= 0) it else -1 }

    /** Sessions from [from] to [to], both included. */
    fun between(from: Long, to: Long): List<Long> {
        val a = lowerBound(from)
        val b = lowerBound(to + 1)
        return (a until b).map { sessions[it] }
    }

    /** The last session on or before [day]; null before the first. */
    fun onOrBefore(day: Long): Long? = (lowerBound(day + 1) - 1).takeIf { it >= 0 }?.let { sessions[it] }

    /** Weekdays between the first and last session on which the exchange did not trade. */
    fun holidays(): List<Long> {
        val f = first ?: return emptyList()
        val l = last ?: return emptyList()
        val out = ArrayList<Long>()
        var d = f
        while (d <= l) {
            if (EgxTime.isWeekday(d) && !contains(d)) out += d
            d += EgxTime.DAY_MS
        }
        return out
    }

    /**
     * The session [n] sessions after [day] (before it when [n] is negative), counting only sessions: [day] itself need not
     * be one. Known history is used where it reaches; beyond the last known session every Sunday to Thursday counts.
     * Null when the answer would fall before the first known session.
     */
    fun shift(day: Long, n: Int): Long? {
        if (n == 0) return day
        var d = day
        var left = kotlin.math.abs(n)
        val step = if (n > 0) EgxTime.DAY_MS else -EgxTime.DAY_MS
        val firstKnown = first
        while (left > 0) {
            d += step
            if (firstKnown != null && d < firstKnown) return null
            if (isSession(d)) left--
        }
        return d
    }

    /** The next [n] sessions after [day], by [shift]'s rule. */
    fun sessionsAfter(day: Long, n: Int): List<Long> = (1..n).mapNotNull { shift(day, it) }

    /** A known session, or, after the last known session, a weekday. */
    fun isSession(day: Long): Boolean {
        val l = last
        return if (l != null && day <= l) contains(day) else EgxTime.isWeekday(day)
    }

    private fun lowerBound(v: Long): Int {
        var lo = 0
        var hi = sessions.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sessions[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** The research counted a day as a session when at least 20 stocks traded on it. */
        const val MIN_STOCKS = 20

        /**
         * The calendar from each stock's traded days. A day counts when [minStocks] stocks traded on it; by default 20, or
         * a third of the stocks when fewer than 60 are given (so a small test set or a partial first download still works).
         */
        fun fromTradedDays(daysByStock: Collection<Collection<Long>>, minStocks: Int = defaultMinStocks(daysByStock.size)): EgxCalendar {
            val count = HashMap<Long, Int>()
            for (days in daysByStock) for (d in days.toSet()) count[d] = (count[d] ?: 0) + 1
            return EgxCalendar(count.filterValues { it >= minStocks }.keys)
        }

        fun fromRows(rowsByStock: Collection<List<DailyRow>>): EgxCalendar =
            fromTradedDays(rowsByStock.map { rows -> rows.filter { it.volume > 0 }.map { it.day } })

        fun defaultMinStocks(stocks: Int): Int = if (stocks >= 3 * MIN_STOCKS) MIN_STOCKS else maxOf(1, stocks / 3)
    }
}
