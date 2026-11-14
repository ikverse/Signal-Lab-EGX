package com.ikverse.signallab.egx.data

import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.engine.Timeframe

/** One session of the EGX30 index, built from its hourly bars. */
data class IndexDay(val day: Long, val open: Double, val high: Double, val low: Double, val close: Double)

/**
 * The EGX30 itself. Yahoo's `^CASE30` gives no daily history (its daily feed answers with one row), but it does give
 * about two years of hourly bars, so each session is built from those and stored from the first run on: no free source
 * keeps the index's history. The research compared its home-made indexes with these same bars (`tools/egx/proxy.py`).
 */
object Egx30 {
    const val SYMBOL = "^CASE30"

    /** The first run asks for all Yahoo keeps; later runs only for the last few sessions. */
    const val FIRST_RANGE = "2y"
    const val UPDATE_RANGE = "5d"

    /**
     * Daily sessions from hourly bars: the first bar's open, the highest high and lowest low, the last bar's close. Bars
     * without a close are skipped, and so is a session that is not over at [nowMs] (its candle could still change).
     */
    fun sessions(hourly: YahooChart, nowMs: Long = Long.MAX_VALUE): List<IndexDay> {
        val byDay = java.util.TreeMap<Long, MutableList<YahooBar>>()
        for (b in hourly.bars.sortedBy { it.time }) {
            if (!(b.close > 0)) continue
            byDay.getOrPut(hourly.dayOf(b)) { ArrayList() } += b
        }
        return byDay.mapNotNull { (day, bars) ->
            if (!EgxTime.isWeekday(day) || !EgxTime.sessionClosed(day, nowMs)) return@mapNotNull null
            val open = bars.first().open.takeIf { it > 0 } ?: bars.first().close
            var high = open
            var low = open
            for (b in bars) {
                high = maxOf(high, b.close, if (b.high > 0) b.high else b.close)
                low = minOf(low, b.close, if (b.low > 0) b.low else b.close)
            }
            IndexDay(day, open, high, low, bars.last().close)
        }
    }

    /** The stored sessions as the engine's daily candles (no volume). */
    fun toCandles(days: List<IndexDay>): Candles {
        val s = days.sortedBy { it.day }
        return Candles(
            "EGX30", Timeframe.D1,
            LongArray(s.size) { s[it].day }, DoubleArray(s.size) { s[it].open }, DoubleArray(s.size) { s[it].high },
            DoubleArray(s.size) { s[it].low }, DoubleArray(s.size) { s[it].close }, DoubleArray(s.size),
            LongArray(s.size) { s[it].day + EgxTime.DAY_MS - 1 },
        )
    }
}
