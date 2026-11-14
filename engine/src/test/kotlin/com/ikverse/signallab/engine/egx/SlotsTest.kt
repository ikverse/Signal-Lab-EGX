package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.EgxSynth
import com.ikverse.signallab.engine.Markets
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hand-made board: each stock's sessions as (open, high, low, close, turnover) from session 0. */
internal fun tinyBoard(vararg stocks: Pair<String, List<DoubleArray>>, sessions: Int = stocks.maxOf { it.second.size }): Board {
    val cal = EgxSynth.calendar(sessions, holidays = emptySet())
    return Board(cal, stocks.associate { (code, rows) ->
        fun col(i: Int) = DoubleArray(sessions) { k -> if (k < rows.size) rows[k][i] else Double.NaN }
        code to Stock(code, 0, col(0), col(1), col(2), col(3), DoubleArray(sessions) { k -> if (k < rows.size) rows[k][4] else 0.0 })
    })
}

/** A flat session at [p] that traded. */
internal fun flat(p: Double) = doubleArrayOf(p, p * 1.01, p * 0.99, p, 1e6)

/** A rule that buys [code] at session [at] and sells it at [sellAt]. */
internal class Scripted(private val code: String, private val at: Int, private val sellAt: Int = Int.MAX_VALUE, override val slots: Int = 1) : SlotRule {
    override val id = "scripted"
    override val universeSize = 80
    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision = when (k) {
        at -> Decision(emptyList(), listOf(code))
        sellAt -> Decision(listOf(code), emptyList())
        else -> Decision.NONE
    }
}

class MarketTest {
    @Test
    fun egxSettingsAreThePlans() {
        val m = Markets.EGX
        assertEquals(245, m.sessionsPerYear)
        assertEquals(0.005, m.roundTrip(1))
        assertEquals(0.005, m.roundTrip(30))
        assertEquals(0.008, m.roundTrip(31))
        assertEquals(0.008, m.roundTrip(null))
        assertEquals(0.0016, m.fees)
        assertEquals(0.19, m.cashRate)
        assertEquals(0.05, m.dividendTax)
        assertEquals(0.20, m.priceLimit)
    }

    @Test
    fun aYearOfSessionsCompoundsToTheYearlyRate() {
        for (m in listOf(Markets.EGX, Markets.CRYPTO)) {
            val r = (1 + m.sessionRate(0.19)).pow(m.sessionsPerYear)
            assertEquals(1.19, r, 1e-9, m.id)
        }
        assertEquals(365, Markets.CRYPTO.sessionsPerYear)
        assertEquals(0.0025, Markets.CRYPTO.roundTrip(1))
        assertEquals(0.0040, Markets.CRYPTO.roundTrip(null))
    }

    @Test
    fun pricesRoundToTheStep() {
        assertEquals(12.35, Markets.EGX.round(12.3462), 1e-9)
        assertEquals(12.3462, Markets.CRYPTO.round(12.3462), 1e-12)
    }
}

class SlotsTest {
    private val noCost: (String, Int) -> Double = { _, _ -> 0.0 }

    @Test
    fun idleMoneyEarnsTheCashRate() {
        val board = tinyBoard("A" to List(300) { flat(10.0) })
        val cash = Slots.flatCash(board, 0.19)
        val run = Slots.run(Scripted("A", -1), Context(board, DividendBook()), 0, noCost, cash)
        assertEquals((1 + Markets.EGX.sessionRate(0.19)).pow(245), run.days[244].equity, 1e-12)
    }

    @Test
    fun aDividendIsPaidAfterTaxToWhoeverHeldTheCloseBefore() {
        val board = tinyBoard("A" to List(30) { flat(10.0) })
        val book = DividendBook(listOf(Dividend("A", board.calendar[20], 0.10, board.calendar[3])))
        val run = Slots.run(Scripted("A", 5), Context(board, book), 0, noCost, DoubleArray(board.size))
        // Bought at session 6 for the whole account; the dividend is 10% of the close before the ex-day, less 5% tax.
        assertEquals(1.0 * 0.10 * 0.95, run.dividends, 1e-12)
        assertEquals(1.095, run.days.last().equity, 1e-12)
        // Bought on the ex-day itself: no dividend.
        assertEquals(0.0, Slots.run(Scripted("A", 19), Context(board, book), 0, noCost, DoubleArray(board.size)).dividends)
    }

    @Test
    fun aLockedOrUntradedStockCannotFillAndTheBuyWaits() {
        val rows = MutableList(20) { flat(10.0) }
        rows[6] = doubleArrayOf(10.5, 10.5, 10.5, 10.5, 1e6) // locked up 5%: one price all session
        rows[7] = doubleArrayOf(10.5, 10.5, 10.5, 10.5, 0.0) // did not trade
        val board = tinyBoard("A" to rows)
        val s = board.stocks.getValue("A")
        assertFalse(Slots.canFill(s, 6, buy = true))
        assertTrue(Slots.canFill(s, 6, buy = false), "a sell is not blocked by a lock upward")
        assertFalse(Slots.canFill(s, 7, buy = true))
        val run = Slots.run(Scripted("A", 5), Context(board, DividendBook()), 0, noCost, DoubleArray(board.size))
        assertEquals(8, run.fills.single { it.buy }.k, "the buy waited for the first session it traded")
        val research = Slots.run(Scripted("A", 5), Context(board, DividendBook()), 0, noCost, DoubleArray(board.size), buysWait = false)
        assertTrue(research.fills.isEmpty(), "the research dropped such a buy")
    }

    @Test
    fun aSellWaitsWhileTheStockIsLockedDown() {
        val rows = MutableList(20) { flat(10.0) }
        rows[10] = doubleArrayOf(9.0, 9.0, 9.0, 9.0, 1e6) // locked down 10%
        val board = tinyBoard("A" to rows)
        val run = Slots.run(Scripted("A", 2, sellAt = 9), Context(board, DividendBook()), 0, noCost, DoubleArray(board.size))
        assertEquals(11, run.fills.single { !it.buy }.k)
    }

    @Test
    fun costsAreHalfARoundTripEachWay() {
        val board = tinyBoard("A" to List(20) { flat(10.0) })
        val run = Slots.run(Scripted("A", 2, sellAt = 9), Context(board, DividendBook()), 0, { _, _ -> 0.01 }, DoubleArray(board.size))
        assertEquals(0.995 * 0.995, run.days.last().equity, 1e-12)
    }

    private fun levelsBoard(path: List<Double>): Board = tinyBoard("A" to List(20) { flat(10.0) } + path.map { p -> doubleArrayOf(p, p, p, p, 1e6).also { it[1] = p * 1.001; it[2] = p * 0.999 } })

    @Test
    fun targetOneSellsHalfAndTargetTwoTheRest() {
        // Flat at 10 with a true range of 0.2 a session: target 1 at 10 + 5 x 0.2 = 11, target 2 at 12.
        val board = levelsBoard(listOf(10.0, 10.0, 11.05, 11.2, 12.1, 12.2))
        val run = Slots.run(Scripted("A", 19), Context(board, DividendBook()), 0, noCost, DoubleArray(board.size), Levels(target1Atr = 5.0, target2Atr = 10.0))
        val sells = run.fills.filter { !it.buy }
        assertEquals(listOf(Exit.TARGET1, Exit.TARGET2), sells.map { it.exit })
        val buy = run.fills.single { it.buy }
        assertEquals(buy.shares / 2, sells[0].shares, 1e-12)
        assertEquals(buy.price + 5 * 0.2, sells[0].price, 1e-9)
    }

    @Test
    fun aCloseBelowTheStopSellsAtTheNextOpen() {
        val board = levelsBoard(listOf(10.0, 9.5, 9.1, 9.0, 8.9))
        val run = Slots.run(Scripted("A", 19), Context(board, DividendBook()), 0, noCost, DoubleArray(board.size), Levels(stopAtr = 4.0))
        val sell = run.fills.single { !it.buy }
        assertEquals(Exit.STOP, sell.exit)
        assertEquals(23, sell.k, "the close at 9.1 broke 10 - 4 x 0.2 = 9.2; filled at the next open")
    }

    @Test
    fun theEntryRangeFillsAtTheLastCloseOrNotAtAll() {
        val above = levelsBoard(listOf(10.5))
        assertTrue(Slots.run(Scripted("A", 19), Context(above, DividendBook()), 0, noCost, DoubleArray(above.size), Levels(entryRange = true)).fills.isEmpty())
        val dips = tinyBoard("A" to List(20) { flat(10.0) } + listOf(doubleArrayOf(10.3, 10.4, 9.9, 10.2, 1e6), flat(10.2)))
        val fill = Slots.run(Scripted("A", 19), Context(dips, DividendBook()), 0, noCost, DoubleArray(dips.size), Levels(entryRange = true)).fills.single()
        assertEquals(10.0, fill.price, 1e-12)
    }

    @Test
    fun aCallsLevelsAreRoundedToThePriceStep() {
        val l = CallLevels.of(close = 20.004, atr = 0.3)
        assertEquals(19.85, l.entryLow, 1e-9)
        assertEquals(20.0, l.entryHigh, 1e-9)
        assertEquals(18.8, l.stop!!, 1e-9)
        assertEquals(21.5, l.target1!!, 1e-9)
        assertEquals(23.0, l.target2!!, 1e-9)
    }

    @Test
    fun aListKeepsWhatStaysOnItWithoutSellingAndRebuying() {
        val held = linkedMapOf("A" to Holding("A", 1.0, 0, 1.0, 0.1), "B" to Holding("B", 1.0, 0, 1.0, 0.1))
        val d = Decision.toList(held, listOf("B", "C"))
        assertEquals(listOf("A"), d.sells)
        assertEquals(listOf("C"), d.buys)
        val board = EgxSynth.board()
        val run = Slots.run(CalmStocks(top = 5, universeSize = 20), Context(board, DividendBook()), 250, { _, _ -> 0.005 }, Slots.flatCash(board, 0.19))
        val byDay = run.fills.groupBy { it.k }
        for ((k, fills) in byDay) {
            val sold = fills.filter { !it.buy }.map { it.code }.toSet()
            assertTrue(fills.none { it.buy && it.code in sold }, "sold and rebought on session $k")
        }
        assertTrue(run.fills.size > 10, "the synthetic board gave the list something to do")
    }

    @Test
    fun theTwoRecordsDifferOnlyByTheLevels() {
        val board = EgxSynth.board(seed = 3)
        val ctx = Context(board, EgxSynth.dividends(board, 3))
        val cash = Slots.flatCash(board, 0.19)
        val rule = CalmStocks(top = 5, universeSize = 20)
        val plain = Slots.run(rule, ctx, 250, { _, _ -> 0.005 }, cash)
        val levels = Slots.run(rule, ctx, 250, { _, _ -> 0.005 }, cash, Levels.PLAN)
        assertTrue(plain.fills.none { it.exit == Exit.STOP || it.exit == Exit.TARGET1 || it.exit == Exit.TARGET2 })
        assertTrue(abs(plain.days.last().equity - levels.days.last().equity) > 0, "the levels changed the result")
    }
}

class BoardTest {
    @Test
    fun theUniverseNeeds180DaysListedAndTurnover() {
        val board = EgxSynth.board()
        val k = board.size - 1
        val u = board.universe(k, 80)
        for (code in u) {
            val s = board.stocks.getValue(code)
            assertTrue(board.calendar[k] - board.calendar[s.first] >= 180 * DAY_MS)
            assertTrue(s.turnover30[k] > 0)
        }
        assertEquals(u.sortedByDescending { board.stocks.getValue(it).turnover30[k] }, u)
        assertEquals(u.take(5), board.universe(k, 5))
        assertNotNull(board.rank(k, u[2])).let { assertEquals(3, it) }
        assertTrue(board.universe(10, 80).isEmpty(), "nothing is 180 days old in the first sessions")
    }

    @Test
    fun aMonthEndsTheSessionBeforeAHolidayThatClosesIt() {
        val board = EgxSynth.board()
        assertTrue(board.holidays.isNotEmpty())
        var ends = 0
        for (k in 0 until board.size - 1) {
            val m = board.monthEnding(k)
            val real = board.date(k + 1).monthValue != board.date(k).monthValue
            assertEquals(real, m != null, "session $k (${board.date(k)})")
            if (m != null) ends++
        }
        assertTrue(ends > 30)
        val inside = board.holidays.filter { !it.isAfter(board.date(board.size - 1)) }.toSet()
        assertEquals(inside, Board.holidaysOf(board.calendar))
    }

    @Test
    fun sessionsAheadAreCountedOnTheCalendarThenOnWeekdays() {
        val board = EgxSynth.board(sessions = 300)
        assertEquals(1, board.sessionsUntil(100, board.calendar[101]))
        assertEquals(5, board.sessionsUntil(100, board.calendar[105]))
        val last = board.size - 1
        for (days in 1..40) {
            val t = board.calendar[last] + days * DAY_MS
            val day = board.date(last).plusDays(days.toLong())
            assertEquals(Sessions.sessionsBetween(board.date(last), day, board.holidays), board.sessionsUntil(last, t))
        }
        assertEquals(0, board.sessionsUntil(100, board.calendar[100]))
    }

    @Test
    fun atrAndAveragesStartWhenTheyHaveEnoughSessions() {
        val board = EgxSynth.board()
        val s = board.stocks.values.first()
        assertTrue(s.atr[s.first + 13].isNaN())
        assertFalse(s.atr[s.first + 14].isNaN())
        assertTrue(s.average(200)[s.first + 198].isNaN())
        assertEquals((s.first + 200 - 200 until s.first + 200).sumOf { s.close[it] } / 200, s.average(200)[s.first + 199], 1e-9)
    }

    @Test
    fun dividendsAreSeenOnlyOnceAnnounced() {
        val board = EgxSynth.board()
        val book = EgxSynth.dividends(board)
        val code = board.stocks.keys.first()
        val d = book.of(code).first()
        assertTrue(book.knownAt(d.announced - 1).of(code).none { it.exDay == d.exDay })
        assertTrue(book.knownAt(d.announced).of(code).any { it.exDay == d.exDay })
        val ex = board.sessionAtOrAfter(d.exDay)
        assertEquals(DividendBook.ASSEMBLY_LEAD_SESSIONS, ex - board.sessionAtOrAfter(d.announced))
        assertNotNull(book.exOn(board, code, ex))
        assertNull(book.exOn(board, code, ex - 1))
        assertEquals(d.share, book.paidOver(board, code, ex, 250), 1e-12)
        assertEquals(0.0, book.paidOver(board, code, ex - 1, 250))
    }
}
