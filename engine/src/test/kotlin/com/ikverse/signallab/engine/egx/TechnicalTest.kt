package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.EgxSynth
import com.ikverse.signallab.engine.strategy.Tier
import org.junit.Test
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A setup that picks [code] at session [at] only, with the exits given. */
private class FixedSetup(
    val code: String, val at: Int, override val limit: Int = 20, override val exitAverage: Int? = null, override val targets: Boolean = true,
) : Setup {
    override val id = "fixed"
    override val family = PickFamily.SHORT
    override val maxNew = 3
    override fun find(board: Board, k: Int): List<Candidate> = if (k == at) listOf(Candidate(code, null, 1.0, 1.0, Double.NaN)) else emptyList()
}

class TechnicalTest {
    private val noCost: (String, Int) -> Double = { _, _ -> 0.0 }
    private val cost: (String, Int) -> Double = { _, _ -> 0.005 }
    private val versions = EgxStrategies.technical.flatMap { it.versions }

    /** The board as it stood at session [n] - 1, with every session after it scrambled. */
    private fun scrambled(board: Board, n: Int): Board = Board(board.calendar, board.stocks.mapValues { (_, s) ->
        fun mix(a: DoubleArray) = DoubleArray(a.size) { i -> if (i < n || a[i].isNaN()) a[i] else a[i] * (1 + 0.3 * ((i * 7919) % 13 - 6) / 6.0) }
        val c = mix(s.close)
        Stock(s.code, s.first, mix(s.open), DoubleArray(c.size) { i -> if (i < n) s.high[i] else c[i] * 1.02 }, DoubleArray(c.size) { i -> if (i < n) s.low[i] else c[i] * 0.98 }, c, mix(s.turnover))
    }, board.holidays)

    private fun key(p: Pick) = listOf(p.k, p.code, p.levels.entryLow, p.levels.entryHigh, p.levels.stop, p.levels.target1, p.levels.target2)

    @Test
    fun everyVersionGivesTheSameAnswerOnCutHistory() {
        val found = HashMap<PickFamily, Int>()
        for (seed in 1L..3L) {
            val board = EgxSynth.board(seed = seed)
            val book = EgxSynth.dividends(board, seed)
            val cash = Slots.flatCash(board, 0.19)
            for (v in versions) {
                val full = PickAccount.run({ v }, Context(board, book), 270, cost, cash)
                found[v.family] = (found[v.family] ?: 0) + full.picks.size
                for (n in listOf(470, 610, 800)) {
                    val known = book.knownAt(board.calendar[n - 1])
                    for (other in listOf(board.cut(n), scrambled(board, n))) {
                        val part = PickAccount.run({ v }, Context(other, known), 270, cost, cash)
                        assertEquals(full.picks.filter { it.k < n }.map(::key), part.picks.filter { it.k < n }.map(::key), "${v.id} looks ahead: cut at $n, seed $seed")
                        for (i in part.days.indices) if (part.days[i].k < n) assertEquals(full.days[i].equity, part.days[i].equity, 1e-12, "${v.id} equity at ${part.days[i].k}, cut $n")
                        for (k in listOf(300, n - 1)) {
                            assertEquals(v.find(board, k).map { it.code }, v.find(other, k).map { it.code }, "${v.id} find at $k, cut $n")
                        }
                    }
                }
            }
        }
        assertTrue((found[PickFamily.SHORT] ?: 0) > 0, "no short-term picks on the synthetic boards")
        assertTrue((found[PickFamily.MID] ?: 0) > 0, "no mid-term picks on the synthetic boards")
    }

    @Test
    fun indicatorsMatchTheirDefinitions() {
        val board = EgxSynth.board(seed = 2)
        for (s in board.stocks.values.take(6)) {
            val ch = s.chart
            for (k in s.first + 30 until s.close.size step 37) {
                if (!s.alive(k)) continue
                val hi20 = (k - 20 until k).maxOf { s.high[it].takeIf { x -> !x.isNaN() } ?: Double.NEGATIVE_INFINITY }
                if (k - s.first >= 20) assertEquals(hi20, ch.priorHigh(20)[k], 1e-12, "${s.code} 20-day high at $k")
                val lo = (k - 9..k).minOf { s.low[it] }
                val hi = (k - 9..k).maxOf { s.high[it] }
                if (k - s.first >= 10) assertEquals((hi - lo) / s.close[k], ch.range10[k], 1e-12)
                val v = (k - 19..k).map { s.volume(it) }
                if (v.all { it > 0 }) assertEquals(v.average(), ch.volume20[k], 1e-6 * v.average()) else assertTrue(ch.volume20[k].isNaN())
            }
            for (j in ch.swingHighs) assertTrue((j - 5..j + 5).all { s.high[it].isNaN() || s.high[j] >= s.high[it] })
        }
    }

    @Test
    fun levelsFollowTheChart() {
        val board = EgxSynth.board(seed = 1)
        var checked = 0
        for (s in board.stocks.values) for (k in s.first + 270 until s.close.size step 23) {
            if (!s.alive(k) || s.atr[k].isNaN() || s.atr[k] == 0.0) continue
            val c = s.close[k]
            val a = s.atr[k]
            val floor = c - 0.2 * a
            val lv = ChartLevels.of(s, k, floor, targets = true)
            assertEquals(c, lv.entryHigh)
            assertEquals(floor, lv.entryLow, 1e-12)
            assertTrue(lv.stop <= c - 1.5 * a + 1e-9 && lv.stop >= c - 4 * a - 1e-9, "stop between 1.5 and 4 moves")
            val risk = c - lv.stop
            assertTrue(lv.target1!! >= c + 2 * risk - 1e-9)
            assertTrue(lv.target2!! >= lv.target1 + risk * 0.999 - 1e-9)
            assertEquals(c - 0.5 * a, ChartLevels.of(s, k, null, targets = false).entryLow, 1e-12)
            assertNull(ChartLevels.of(s, k, null, targets = false).target1)
            checked++
        }
        assertTrue(checked > 50)
    }

    @Test
    fun targetOneTakesHalfAndTargetTwoTheRest() {
        // Flat at 10 (high 10.1, low 9.9): a normal move of 0.2, stop 9.7, target 1 at 10.6, target 2 at 11.2.
        val rows = (0 until 21).map { flat10() } + listOf(
            doubleArrayOf(10.2, 10.3, 10.05, 10.2, 1e6), // opens above the range and never comes back: waits
            doubleArrayOf(10.1, 10.2, 9.95, 10.1, 1e6), // comes back to the top of the range: bought at 10.00
            doubleArrayOf(10.3, 10.7, 10.2, 10.6, 1e6), // target 1
            doubleArrayOf(10.8, 11.3, 10.7, 11.2, 1e6), // target 2
        ) + (0 until 3).map { flat10() }
        val board = tinyBoard("A" to rows)
        val lv = ChartLevels.of(board.stocks.getValue("A"), 20, null, true)
        assertEquals(9.7, lv.stop, 1e-9)
        assertEquals(10.6, lv.target1!!, 1e-9)
        assertEquals(11.2, lv.target2!!, 1e-9)
        val run = PickAccount.run({ FixedSetup("A", 20) }, Context(board, DividendBook(emptyList())), 15, noCost, DoubleArray(board.size))
        val t = run.trades.single()
        assertEquals(22, t.buyK)
        assertEquals(10.0, t.buyPrice, 1e-12)
        assertTrue(t.half)
        assertEquals(PickExit.TARGET2, t.exit)
        assertEquals(24, t.sellK)
        assertEquals(0.5 * 10.6 / 10 + 0.5 * 11.2 / 10 - 1, t.result!!, 1e-12)
        assertEquals(listOf(PickEvent.Kind.BOUGHT, PickEvent.Kind.TARGET1, PickEvent.Kind.TARGET2), run.events.map { it.kind })
        assertEquals(1.0 + 0.09 / PickAccount.SLOTS, run.days.last().equity, 1e-12)
    }

    @Test
    fun aPickThatNeverComesBackToItsRangeExpiresAfterThreeSessions() {
        val rows = (0 until 21).map { flat10() } + (0 until 4).map { doubleArrayOf(10.5, 10.6, 10.3, 10.5, 1e6) }
        val board = tinyBoard("A" to rows)
        val run = PickAccount.run({ FixedSetup("A", 20) }, Context(board, DividendBook(emptyList())), 15, noCost, DoubleArray(board.size))
        assertTrue(run.trades.isEmpty())
        assertEquals(PickEvent.Kind.EXPIRED, run.events.single().kind)
        assertEquals(23, run.events.single().k)
    }

    @Test
    fun aCloseUnderTheStopSellsAtTheNextOpen() {
        val rows = (0 until 21).map { flat10() } + listOf(
            doubleArrayOf(9.95, 10.0, 9.9, 9.95, 1e6), // bought at the open, inside the range
            doubleArrayOf(9.8, 9.85, 9.5, 9.6, 1e6), // closes under 9.70
            doubleArrayOf(9.55, 9.6, 9.4, 9.5, 1e6), // sold at this open
        )
        val board = tinyBoard("A" to rows)
        val run = PickAccount.run({ FixedSetup("A", 20) }, Context(board, DividendBook(emptyList())), 15, noCost, DoubleArray(board.size))
        val t = run.trades.single()
        assertEquals(21, t.buyK)
        assertEquals(PickExit.STOP, t.exit)
        assertEquals(23, t.sellK)
        assertEquals(9.55 / 9.95 - 1, t.result!!, 1e-12)
    }

    @Test
    fun theTimeLimitAndTheExitAverageSellAtTheNextOpen() {
        val rows = (0 until 21).map { flat10() } + (0 until 8).map { flat10() }
        val board = tinyBoard("A" to rows)
        val timed = PickAccount.run({ FixedSetup("A", 20, limit = 3) }, Context(board, DividendBook(emptyList())), 15, noCost, DoubleArray(board.size))
        assertEquals(PickExit.TIME, timed.trades.single().exit)
        assertEquals(25, timed.trades.single().sellK)
        val falling = (0 until 21).map { flat10() } + listOf(flat10(), doubleArrayOf(9.98, 10.0, 9.95, 9.96, 1e6)) + (0 until 3).map { flat10() }
        val b2 = tinyBoard("A" to falling)
        val ruled = PickAccount.run({ FixedSetup("A", 20, exitAverage = 5, targets = false) }, Context(b2, DividendBook(emptyList())), 15, noCost, DoubleArray(b2.size))
        assertEquals(PickExit.RULE, ruled.trades.single().exit)
        assertEquals(23, ruled.trades.single().sellK)
        assertNull(ChartLevels.of(b2.stocks.getValue("A"), 20, null, targets = false).target2)
    }

    @Test
    fun statsCountTheFinishedTrades() {
        val rows = (0 until 21).map { flat10() } + listOf(
            doubleArrayOf(10.1, 10.2, 9.95, 10.1, 1e6), doubleArrayOf(10.3, 10.7, 10.2, 10.6, 1e6), doubleArrayOf(10.8, 11.3, 10.7, 11.2, 1e6),
        )
        val board = tinyBoard("A" to rows)
        val run = PickAccount.run({ FixedSetup("A", 20) }, Context(board, DividendBook(emptyList())), 15, noCost, DoubleArray(board.size))
        val s = assertNotNull(PickStats.of(run.trades))
        assertEquals(1, s.trades)
        assertEquals(1.0, s.won)
        assertEquals(1.0, s.target1)
        assertEquals(1.0, s.target2)
        assertEquals(0.0, s.stopped)
        assertNull(PickStats.of(emptyList()))
    }

    @Test
    fun theLearningStepChoosesFromThePastOnly() {
        val board = EgxSynth.board(seed = 3, sessions = 900)
        val book = EgxSynth.dividends(board, 3)
        val cash = Slots.flatCash(board, 0.19)
        val list = EgxStrategies.shortTerm
        val full = PickLearning.choices(list.versions, Context(board, book), 600, 270, cost, cash)
        assertEquals(YearMonth.from(board.date(600)), full.first().month)
        assertEquals(YearMonth.from(board.date(board.size - 1)), full.last().month)
        // A month's choice reads only the sessions before it: cutting the board after the month starts changes nothing.
        for (c in full.dropLast(1)) {
            val first = (0 until board.size).first { YearMonth.from(board.date(it)) == c.month }
            val cut = board.cut(first + 1)
            val again = PickLearning.choices(list.versions, Context(cut, book.knownAt(cut.calendar.last())), first, 270, cost, cash)
            assertEquals(c.setup.id, again.single().setup.id, "month ${c.month}")
        }
        for (c in full) {
            val best = c.growth.values.max()
            if (c.setup != list.registered) assertEquals(best, c.growth.getValue(c.setup.id))
        }
        // Too little history: the registered version.
        val early = PickLearning.choices(list.versions, Context(board, book), 300, 270, cost, cash)
        assertEquals(list.registered.id, early.first().setup.id)
    }

    @Test
    fun theMarketStateCountsTheUptrends() {
        val board = EgxSynth.board(seed = 1)
        val k = board.size - 1
        val index = DoubleArray(board.size) { 1000.0 + it }
        val m = MarketState.at(board, index, k)
        assertEquals(1000.0 + k, m.egx30)
        assertEquals(true, m.above50)
        assertEquals(true, m.above200)
        assertTrue(m.counted in 1..24 && m.uptrend in 0..m.counted)
        val none = MarketState.at(board, DoubleArray(board.size) { Double.NaN }, k)
        assertNull(none.egx30)
        assertNull(none.above200)
    }

    @Test
    fun bothListsAreRegisteredAsTestingWithEveryVersionTried() {
        assertEquals(240, EgxStrategies.triedCount)
        assertEquals(2, EgxStrategies.technical.size)
        for (list in EgxStrategies.technical) {
            assertEquals(Tier.TESTING, list.launchTier)
            assertEquals(8, list.versions.size)
            assertEquals(8, list.versions.map { it.id }.toSet().size)
            assertTrue(list.versions.all { it.family == list.family })
        }
        val short = EgxStrategies.shortTerm.registered as ShortTermSetup
        assertEquals(listOf(20, 20), listOf(short.enter, short.limit))
        assertEquals(1.0, short.volume)
        assertTrue(short.targets)
        val mid = EgxStrategies.midTerm.registered as MidTermSetup
        assertTrue(mid.template && !mid.tenth && mid.limit == 120 && mid.exitAverage == 50)
        assertFalse(EgxStrategies.midTerm.versions.any { it.targets })
        assertTrue(EgxStrategies.midTerm.versions.all { it.exitAverage == 50 })
        assertEquals(EgxStrategies.midTerm, EgxStrategies.technicalOf("egx_mid_term"))
    }

    @Test
    fun aListIsSwitchedOffWhenItFallsBehind() {
        val list = EgxStrategies.shortTerm
        fun rec(months: Int, rule: Double, cash: Double = 1.1, index: Double? = 1.2, random: List<Double> = listOf(1.0, 1.1, 1.2)) =
            YardstickRecord(months, Growth(rule, 0.0), Growth(cash, 0.0), index?.let { Growth(it, 0.0) }, random)
        assertTrue(EgxTiers.shows(EgxTiers.judge(list, null)))
        assertEquals(Tier.TESTING, EgxTiers.judge(list, rec(6, 0.5)).tier)
        assertEquals(Tier.WATCH, EgxTiers.judge(list, rec(12, 1.0)).tier)
        assertEquals(Tier.WATCH, EgxTiers.judge(list, rec(12, 1.15, random = List(10) { 1.16 + it * 0.01 })).tier)
        val ok = EgxTiers.judge(list, rec(12, 1.3))
        assertEquals(Tier.TESTING, ok.tier)
        assertTrue(EgxTiers.shows(ok))
        assertFalse(EgxTiers.shows(EgxTiers.judge(list, rec(13, 0.9))))
    }

    private fun flat10() = doubleArrayOf(10.0, 10.1, 9.9, 10.0, 1e6)
}
