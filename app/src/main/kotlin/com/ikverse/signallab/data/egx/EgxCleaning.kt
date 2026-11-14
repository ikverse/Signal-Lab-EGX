package com.ikverse.signallab.data.egx

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One traded session of a stock as stored: Yahoo's high, low, close and volume after the row checks, and never Yahoo's
 * open (for EGX it is the previous close, not a traded price). Prices are as Yahoo sends them; the jump flattening is
 * applied when a series is built, so a new jump never rewrites what is stored.
 */
data class DailyRow(val day: Long, val high: Double, val low: Double, val close: Double, val volume: Double) {
    /** The session's turnover in pounds: close × volume, before any flattening (as the research counted it). */
    val turnover: Double get() = close * volume
}

/** What the hourly bars say about a session: its first traded price and its last close, both unadjusted. */
data class SessionOpen(val day: Long, val open: Double, val lastClose: Double)

/**
 * A stock's daily candles, cleaned and laid on the market calendar. A market day the stock did not trade is a flat
 * candle at the last close with no volume ([traded] false), from its first traded session to its last.
 */
class EgxSeries(
    val code: String,
    val day: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val volume: DoubleArray,
    /** Pounds traded; 0 on a day the stock did not trade. */
    val turnover: DoubleArray,
    val traded: BooleanArray,
    /** The open came from the session's first hourly bar. Otherwise it is the previous close, held inside the day's range. */
    val realOpen: BooleanArray,
    /** Sessions whose move beyond [EgxCleaning.MAX_MOVE] was flattened (the earlier history scaled to meet them). */
    val flattened: List<Long>,
) {
    val size: Int get() = day.size

    fun indexOf(d: Long): Int = day.binarySearch(d).let { if (it >= 0) it else -1 }

    /** The engine's view: daily candles keyed by the session day, closing a millisecond before the next day. */
    fun toCandles(): Candles = Candles(
        code, Timeframe.D1, day.copyOf(), open.copyOf(), high.copyOf(), low.copyOf(), close.copyOf(), volume.copyOf(),
        LongArray(size) { day[it] + EgxTime.DAY_MS - 1 },
    )
}

/** The research's cleaning (`tools/egx/prepare.py`, `tools/egx/fills.py`), as pure functions. */
object EgxCleaning {
    /** EGX limits a day's move to 20%; a bigger close-to-close step is a bonus issue, an unadjusted split or a bad row. */
    const val MAX_MOVE = 0.21

    /**
     * Yahoo's daily rows reduced to sessions that traded: a row with no close, high or low, or with no volume, is a day
     * the stock did not trade and is dropped; the high and low are widened to hold the close; a Friday or Saturday row
     * (the exchange is shut) is dropped; a repeated day keeps its last row.
     */
    fun dailyRows(chart: YahooChart): List<DailyRow> {
        val out = java.util.TreeMap<Long, DailyRow>()
        for (b in chart.bars) {
            val c = b.close
            val h = b.high
            val l = b.low
            if (!(c > 0) || !(h > 0) || !(l > 0) || !(b.volume > 0)) continue
            val day = chart.dayOf(b)
            if (!EgxTime.isWeekday(day)) continue
            out[day] = DailyRow(day, max(max(h, l), c), min(min(h, l), c), c, b.volume)
        }
        return out.values.toList()
    }

    /**
     * The real open of each session in an hourly feed: the open of the session's first bar that shows trading (it has
     * volume, or its high and low differ), and the close of its last bar with a price. Yahoo reports the first hour with
     * no volume even when it traded, so volume alone would skip it.
     */
    fun sessionOpens(hourly: YahooChart): Map<Long, SessionOpen> {
        val firstOpen = HashMap<Long, Double>()
        val lastClose = HashMap<Long, Double>()
        for (b in hourly.bars.sortedBy { it.time }) {
            val day = hourly.dayOf(b)
            if (b.close > 0) lastClose[day] = b.close
            val shows = b.volume > 0 || (b.high > 0 && b.low > 0 && b.high != b.low)
            if (day !in firstOpen && b.open > 0 && shows) firstOpen[day] = b.open
        }
        return firstOpen.mapNotNull { (day, o) -> lastClose[day]?.let { day to SessionOpen(day, o, it) } }.toMap()
    }

    /**
     * The stock's series. Prices before a move beyond [MAX_MOVE] are scaled by that move, so the day reads flat (volume and
     * turnover are untouched). The open is the session's first hourly price, carried onto the daily feed's scale and held
     * inside the day's range; without one it is the previous close held inside the range. With a [calendar], days off the
     * calendar are dropped and market days the stock missed are filled flat.
     */
    fun series(code: String, rows: List<DailyRow>, opens: Map<Long, SessionOpen> = emptyMap(), calendar: EgxCalendar? = null): EgxSeries {
        val sorted = rows.sortedBy { it.day }
        val n = sorted.size
        val f = DoubleArray(n) { 1.0 }
        val flattened = ArrayList<Long>()
        for (k in n - 1 downTo 1) {
            val step = sorted[k].close / sorted[k - 1].close
            val jump = abs(step - 1) > MAX_MOVE
            if (jump) flattened += sorted[k].day
            f[k - 1] = f[k] * (if (jump) step else 1.0)
        }
        val kept = if (calendar == null) sorted.indices.toList() else sorted.indices.filter { calendar.contains(sorted[it].day) }
        val days: List<Long> = if (calendar == null || kept.isEmpty()) kept.map { sorted[it].day }
        else calendar.between(sorted[kept.first()].day, sorted[kept.last()].day)

        val size = days.size
        val day = LongArray(size)
        val o = DoubleArray(size)
        val h = DoubleArray(size)
        val l = DoubleArray(size)
        val c = DoubleArray(size)
        val v = DoubleArray(size)
        val q = DoubleArray(size)
        val traded = BooleanArray(size)
        val real = BooleanArray(size)
        val rowAt = HashMap<Long, Int>(kept.size * 2)
        for (k in kept) rowAt[sorted[k].day] = k
        var prev = Double.NaN
        for ((i, d) in days.withIndex()) {
            day[i] = d
            val k = rowAt[d]
            if (k == null) {
                o[i] = prev; h[i] = prev; l[i] = prev; c[i] = prev
                continue
            }
            val r = sorted[k]
            val s = f[k]
            h[i] = r.high * s; l[i] = r.low * s; c[i] = r.close * s
            v[i] = r.volume; q[i] = r.turnover; traded[i] = true
            val so = opens[d]
            val factor = if (so != null) r.close / so.lastClose else Double.NaN
            if (so != null && factor > 0.2 && factor < 5) {
                o[i] = (so.open * factor * s).coerceIn(l[i], h[i])
                real[i] = true
            } else {
                o[i] = (if (prev > 0) prev else c[i]).coerceIn(l[i], h[i])
            }
            prev = c[i]
        }
        return EgxSeries(code, day, o, h, l, c, v, q, traded, real, flattened.sorted())
    }
}
