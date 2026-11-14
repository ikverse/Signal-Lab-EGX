package com.ikverse.signallab.engine

import com.ikverse.signallab.engine.egx.Board
import com.ikverse.signallab.engine.egx.Dividend
import com.ikverse.signallab.engine.egx.DividendBook
import com.ikverse.signallab.engine.egx.Sessions
import com.ikverse.signallab.engine.egx.Stock
import java.time.LocalDate

/**
 * Synthetic Egyptian boards for tests: a Sunday-to-Thursday calendar with a few holidays, stocks listed at different
 * times, random-walk prices with some quiet stocks and some wild ones, sessions without trades, sessions locked at the
 * limit, and yearly cash dividends. Deterministic per seed (the counter-based [Rng]).
 */
object EgxSynth {
    private const val DAY = 86_400_000L

    /** Session days from [start], skipping Fridays, Saturdays and [holidays]. */
    fun calendar(sessions: Int, start: LocalDate = LocalDate.parse("2018-01-01"), holidays: Set<LocalDate> = defaultHolidays(start, sessions)): LongArray {
        val out = LongArray(sessions)
        var d = start
        var n = 0
        while (n < sessions) {
            if (Sessions.isSession(d, holidays)) out[n++] = d.toEpochDay() * DAY
            d = d.plusDays(1)
        }
        return out
    }

    /** The last weekdays of some months, so month ends fall before holidays as they do in Egypt (30 June, Eids). */
    fun defaultHolidays(start: LocalDate, sessions: Int): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        var m = start.withDayOfMonth(1)
        val end = start.plusDays(sessions * 7L / 5 + 60)
        var i = 0
        while (m.isBefore(end)) {
            if (i % 4 == 1) {
                var d = m.plusMonths(1).minusDays(1)
                while (!Sessions.isWeekday(d)) d = d.minusDays(1)
                out.add(d)
            }
            m = m.plusMonths(1)
            i++
        }
        return out
    }

    fun board(stocks: Int = 24, sessions: Int = 900, seed: Long = 1L): Board {
        val start = LocalDate.parse("2018-01-01")
        val holidays = defaultHolidays(start, sessions)
        val cal = calendar(sessions, start, holidays)
        val map = LinkedHashMap<String, Stock>()
        for (s in 0 until stocks) {
            val code = "S" + ('A' + s / 26) + ('A' + s % 26)
            val first = if (s % 5 == 4) sessions / 3 else (s * 7) % 40
            val swing = 0.005 + 0.03 * Rng.unit(seed, s.toLong(), -1)
            val drift = 0.0012 * (Rng.unit(seed, s.toLong(), -2) - 0.35)
            val o = DoubleArray(sessions) { Double.NaN }
            val h = DoubleArray(sessions) { Double.NaN }
            val l = DoubleArray(sessions) { Double.NaN }
            val c = DoubleArray(sessions) { Double.NaN }
            val q = DoubleArray(sessions)
            var p = 5.0 + 50 * Rng.unit(seed, s.toLong(), -3)
            val size = 1e6 * (1 + 40 * Rng.unit(seed, s.toLong(), -4))
            for (k in first until sessions) {
                val u = Rng.unit(seed, s.toLong(), k.toLong())
                val quiet = Rng.unit(seed, s.toLong() + 1000, k.toLong()) < 0.04
                val locked = !quiet && Rng.unit(seed, s.toLong() + 2000, k.toLong()) < 0.01
                if (quiet && k > first) {
                    o[k] = p; h[k] = p; l[k] = p; c[k] = p; q[k] = 0.0
                    continue
                }
                val prev = p
                p = if (locked) prev * (if (u < 0.5) 1.2 else 0.8) else maxOf(0.5, prev * (1 + drift + swing * (2 * u - 1)))
                c[k] = p
                if (locked) {
                    o[k] = p; h[k] = p; l[k] = p
                } else {
                    o[k] = (prev + p) / 2
                    h[k] = maxOf(prev, p) * (1 + swing * Rng.unit(seed, s.toLong() + 3000, k.toLong()))
                    l[k] = minOf(prev, p) * (1 - swing * Rng.unit(seed, s.toLong() + 4000, k.toLong()))
                }
                q[k] = size * (0.5 + Rng.unit(seed, s.toLong() + 5000, k.toLong())) * p
            }
            map[code] = Stock(code, first, o, h, l, c, q)
        }
        return Board(cal, map, holidays)
    }

    /** A cash dividend a year for every second stock, announced 17 sessions before its ex-day as the research dates them. */
    fun dividends(board: Board, seed: Long = 1L): DividendBook {
        val out = ArrayList<Dividend>()
        for ((i, s) in board.stocks.values.withIndex()) {
            if (i % 2 == 1) continue
            var ex = s.first + 60 + (i * 13) % 200
            while (ex < board.size + 120) {
                val share = 0.02 + 0.08 * Rng.unit(seed, i.toLong(), ex.toLong())
                val exDay = if (ex < board.size) board.calendar[ex] else board.calendar.last() + (ex - board.size + 1) * 7 * DAY / 5
                out.add(DividendBook.announcedBefore(board, s.code, exDay, share))
                ex += 245
            }
        }
        return DividendBook(out)
    }
}
