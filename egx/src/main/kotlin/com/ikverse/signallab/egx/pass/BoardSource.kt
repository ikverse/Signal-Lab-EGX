package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.data.DailyRow
import com.ikverse.signallab.egx.data.EgxCalendar
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxSeries
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.data.IndexDay
import com.ikverse.signallab.egx.data.YahooDividend
import com.ikverse.signallab.engine.egx.Board
import com.ikverse.signallab.engine.egx.Dividend
import com.ikverse.signallab.engine.egx.DividendBook
import com.ikverse.signallab.engine.egx.Stock
import java.time.LocalDate
import java.time.MonthDay

/**
 * The days the exchange is closed, known ahead (the month-end rule needs tomorrow's date, never tomorrow's candle):
 * the weekdays it did not open in the past, Egypt's fixed public holidays, and the ones the owner adds by hand (the
 * Eids and Sham el-Nessim move every year, and a holiday that falls on a weekday is sometimes moved to a Thursday).
 */
object Holidays {
    /** Coptic Christmas, the 25 January revolution, Sinai Liberation, Labour Day, 30 June, 23 July, Armed Forces Day. */
    val FIXED: List<MonthDay> = listOf(
        MonthDay.of(1, 7), MonthDay.of(1, 25), MonthDay.of(4, 25), MonthDay.of(5, 1), MonthDay.of(6, 30), MonthDay.of(7, 23), MonthDay.of(10, 6),
    )

    fun known(calendar: EgxCalendar, byHand: Collection<LocalDate>, through: LocalDate): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        for (d in calendar.holidays()) out.add(EgxTime.date(d))
        out.addAll(byHand)
        val last = calendar.last?.let(EgxTime::date) ?: return out
        var y = last.year
        while (y <= through.year) {
            for (md in FIXED) {
                val d = md.atYear(y)
                if (d.isAfter(last) && !d.isAfter(through)) out.add(d)
            }
            y++
        }
        return out
    }
}

/** Everything one evening pass decides with: the board, the dividends on it, and the EGX30's closes on its calendar. */
class Snapshot(val board: Board, val dividends: DividendBook, val egx30: DoubleArray, val raw: Map<String, List<DailyRow>>)

/**
 * Lays the stored, cleaned data on the engine's board, as the research's `prepare.py` and `export.py` laid theirs:
 * one calendar, a flat candle on a session a stock did not trade, NaN before its first session and after its last.
 */
object BoardSource {
    /**
     * [series] are the stocks laid on [calendar] ([com.ikverse.signallab.egx.data.EgxSync.series]); [raw] their unadjusted
     * rows, which a dividend's yield is measured against.
     */
    fun build(
        calendar: EgxCalendar,
        series: Collection<EgxSeries>,
        raw: Map<String, List<DailyRow>>,
        yahooDividends: Map<String, List<YahooDividend>>,
        news: List<EgxDividend>,
        egx30: List<IndexDay>,
        holidays: Set<LocalDate>,
    ): Snapshot {
        val cal = calendar.sessions
        val n = cal.size
        val stocks = LinkedHashMap<String, Stock>()
        for (s in series.sortedBy { it.code }) {
            if (s.size == 0) continue
            val first = calendar.indexOf(s.day[0])
            if (first < 0) continue
            fun nan() = DoubleArray(n) { Double.NaN }
            val o = nan(); val h = nan(); val l = nan(); val c = nan(); val q = DoubleArray(n); val v = DoubleArray(n)
            for (i in 0 until s.size) {
                val k = calendar.indexOf(s.day[i])
                if (k < 0) continue
                o[k] = s.open[i]; h[k] = s.high[i]; l[k] = s.low[i]; c[k] = s.close[i]; q[k] = s.turnover[i]
                v[k] = if (s.traded[i]) s.volume[i] else 0.0
            }
            stocks[s.code] = Stock(s.code, first, o, h, l, c, q, v)
        }
        val board = Board(cal, stocks, holidays)
        val ix = DoubleArray(n) { Double.NaN }
        for (d in egx30) calendar.indexOf(d.day).takeIf { it >= 0 }?.let { ix[it] = d.close }
        return Snapshot(board, DividendBook(dividends(board, calendar, raw, yahooDividends, news)), ix, raw)
    }

    /**
     * The dividends the rules read. Past ones come from Yahoo's tables (dated as announced 17 sessions before the
     * ex-day, as the research dated them). Upcoming ones come from the news: announced at the general assembly (or when
     * the news first said so), with the ex-day the session after the last day that carries it. A dividend on both is
     * counted once, Yahoo's figure first. Its share is the amount over the last raw close before the ex-day (or the
     * latest close while that day is still ahead), between 0.2% and 30% as in the research.
     */
    fun dividends(board: Board, calendar: EgxCalendar, raw: Map<String, List<DailyRow>>, yahoo: Map<String, List<YahooDividend>>, news: List<EgxDividend>): List<Dividend> {
        val out = ArrayList<Dividend>()
        val seen = HashSet<Pair<String, Long>>()
        fun closeBefore(code: String, day: Long): Double? = raw[code]?.lastOrNull { it.day < day && it.volume > 0 }?.close
        for ((code, list) in yahoo) {
            if (code !in board.stocks) continue
            for (y in list) {
                val before = closeBefore(code, y.exDay) ?: continue
                val share = y.amount / before
                if (share !in 0.002..0.30 || !seen.add(code to y.exDay)) continue
                out.add(DividendBook.announcedBefore(board, code, y.exDay, share))
            }
        }
        for (d in news) {
            if (d.cancelled || d.code !in board.stocks) continue
            val amount = d.amount ?: continue
            val ex = d.yahooExDay ?: d.lastCumDay?.let { calendar.shift(it, 1) } ?: continue
            if (!seen.add(d.code to ex)) continue
            val before = closeBefore(d.code, ex) ?: continue
            val share = amount / before
            if (share !in 0.002..0.30) continue
            val announced = d.assemblyDay ?: d.announcedDay ?: continue
            out.add(Dividend(d.code, ex, share, announced))
        }
        return out
    }
}
