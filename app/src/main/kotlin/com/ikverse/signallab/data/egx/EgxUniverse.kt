package com.ikverse.signallab.data.egx

/** A stock in the universe on a given day, with its rank by turnover (1 is the busiest). */
data class UniverseEntry(val code: String, val rank: Int, val turnover30: Double, val tradedOf60: Int)

/**
 * The stocks watched: the 80 busiest by turnover over the last 30 sessions, among those listed at least 180 days and
 * traded on at least 57 of the last 60 sessions. Every number is the plan's (`docs/egx-plan.md` part 2).
 */
object EgxUniverse {
    const val SIZE = 80
    const val TURNOVER_SESSIONS = 30
    const val LISTED_DAYS = 180
    const val ACTIVITY_SESSIONS = 60
    const val MIN_TRADED = 57

    /**
     * The universe at the close of [asOf] (a session of [calendar]). Each series must be laid on the same calendar
     * ([EgxCleaning.series] with a calendar), so a session it is missing from means it did not trade or was not listed yet.
     * Ties in turnover go to the earlier code, so the answer never depends on input order. [listedSince] gives each
     * stock's first traded session when the series passed in are only the recent part of its history.
     */
    fun pick(
        series: Collection<EgxSeries>,
        calendar: EgxCalendar,
        asOf: Long,
        size: Int = SIZE,
        listedSince: Map<String, Long> = emptyMap(),
    ): List<UniverseEntry> {
        val at = calendar.indexOf(asOf)
        if (at < 0) return emptyList()
        val activity = calendar.sessions.copyOfRange(maxOf(0, at - ACTIVITY_SESSIONS + 1), at + 1)
        val turnoverDays = calendar.sessions.copyOfRange(maxOf(0, at - TURNOVER_SESSIONS + 1), at + 1)
        val candidates = ArrayList<Triple<String, Double, Int>>()
        for (s in series) {
            if (s.size == 0 || (listedSince[s.code] ?: s.day[0]) > asOf - LISTED_DAYS * EgxTime.DAY_MS) continue
            var traded = 0
            for (d in activity) {
                val i = s.indexOf(d)
                if (i >= 0 && s.traded[i]) traded++
            }
            if (traded < MIN_TRADED) continue
            var turnover = 0.0
            for (d in turnoverDays) {
                val i = s.indexOf(d)
                if (i >= 0) turnover += s.turnover[i]
            }
            if (turnover > 0) candidates += Triple(s.code, turnover, traded)
        }
        return candidates
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.first })
            .take(size)
            .mapIndexed { i, (code, turnover, traded) -> UniverseEntry(code, i + 1, turnover, traded) }
    }
}
