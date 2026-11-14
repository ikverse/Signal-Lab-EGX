package com.ikverse.signallab.engine.egx

/**
 * A cash dividend as a dated event. [exDay] is the first session that no longer carries it (the day after the last one
 * that does); [share] is the dividend as a share of the last close before it; [announced] is when the amount became
 * final (the general assembly), the first moment a rule may act on it.
 */
class Dividend(val code: String, val exDay: Long, val share: Double, val announced: Long)

/**
 * Every dividend the strategies and the account may read, by stock. Dividends are not candles, so the app fills this
 * before each pass, the way it fills funding for the crypto app ([update]); the research fills it from Yahoo's
 * dividend tables. A rule deciding at a session sees an upcoming dividend only once it was [Dividend.announced], and a
 * past one by its ex-day.
 */
class DividendBook(all: List<Dividend> = emptyList()) {
    private var byCode: Map<String, List<Dividend>> = index(all)

    private fun index(all: List<Dividend>) = all.groupBy { it.code }.mapValues { (_, l) -> l.sortedBy { it.exDay } }

    fun update(all: List<Dividend>) {
        byCode = index(all)
        exIndex = null
        exBoard = null
    }

    fun of(code: String): List<Dividend> = byCode[code].orEmpty()

    /** The book as it stood at [time]: only what was announced by then. */
    fun knownAt(time: Long): DividendBook = DividendBook(byCode.values.flatten().filter { it.announced <= time })

    /** The dividend whose ex-day is session [k] of [board], or null; the account pays it to whoever held at the close before. */
    fun exOn(board: Board, code: String, k: Int): Dividend? {
        val map = exIndex.takeIf { exBoard === board } ?: byCode.mapValues { (_, list) ->
            list.associateBy { board.sessionAtOrAfter(it.exDay) }
        }.also {
            exIndex = it
            exBoard = board
        }
        return map[code]?.get(k)
    }

    private var exIndex: Map<String, Map<Int, Dividend>>? = null
    private var exBoard: Board? = null

    /** The dividends of [code], as shares of the price, whose ex-day fell in the [n] sessions ending at session [k]. */
    fun paidOver(board: Board, code: String, k: Int, n: Int): Double {
        val from = if (k - n >= 0) board.calendar[k - n] else Long.MIN_VALUE
        var s = 0.0
        for (d in of(code)) if (d.exDay > from && d.exDay <= board.calendar[k]) s += d.share
        return s
    }

    companion object {
        /**
         * The research's timing (docs/egx-plan.md, sixth pass): the amount is final at the general assembly a median 16.5
         * sessions before the last day that carries it, so a past dividend is taken as announced 17 sessions before its
         * ex-day.
         */
        const val ASSEMBLY_LEAD_SESSIONS = 17

        /** Dates a past dividend's announcement on the board's calendar, [lead] sessions before its ex-day. */
        fun announcedBefore(board: Board, code: String, exDay: Long, share: Double, lead: Int = ASSEMBLY_LEAD_SESSIONS): Dividend {
            val ex = board.sessionAtOrAfter(exDay)
            return Dividend(code, exDay, share, if (ex - lead >= 0) board.calendar[ex - lead] else Long.MIN_VALUE)
        }
    }
}
