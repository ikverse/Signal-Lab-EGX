package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.EgxSynth
import com.ikverse.signallab.engine.strategy.Tier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RulesTest {
    private val cost: (String, Int) -> Double = { _, _ -> 0.005 }

    private fun rules(): List<SlotRule> = EgxStrategies.all.map { it.rule } + HoldBusiest(10) +
        // Smaller versions, so the synthetic board's 24 stocks give every branch work.
        listOf(CalmStocks(top = 4, universeSize = 16), DividendPayers(top = 4, universeSize = 16, uptrend = true), LowRiskFormula(top = 4, universeSize = 16))

    private fun same(a: Decision, b: Decision) = a.sells == b.sells && a.buys == b.buys

    /** The board as it stood at session [n] - 1, with every session after it scrambled. */
    private fun scrambled(board: Board, n: Int): Board = Board(board.calendar, board.stocks.mapValues { (_, s) ->
        fun mix(a: DoubleArray) = DoubleArray(a.size) { i -> if (i < n || a[i].isNaN()) a[i] else a[i] * (1 + 0.3 * ((i * 7919) % 13 - 6) / 6.0) }
        val c = mix(s.close)
        Stock(s.code, s.first, mix(s.open), DoubleArray(c.size) { i -> if (i < n) s.high[i] else c[i] * 1.02 }, DoubleArray(c.size) { i -> if (i < n) s.low[i] else c[i] * 0.98 }, c, mix(s.turnover))
    }, board.holidays)

    @Test
    fun everyRuleGivesTheSameAnswerOnCutHistory() {
        for (seed in 1L..3L) {
            val board = EgxSynth.board(seed = seed)
            val book = EgxSynth.dividends(board, seed)
            val cash = Slots.flatCash(board, 0.19)
            for (rule in rules()) {
                val full = Slots.run(rule, Context(board, book), 200, cost, cash, Levels.PLAN)
                for (n in listOf(330, 470, 610, 800)) {
                    val known = book.knownAt(board.calendar[n - 1])
                    val cut = Slots.run(rule, Context(board.cut(n), known), 200, cost, cash, Levels.PLAN)
                    val mixed = Slots.run(rule, Context(scrambled(board, n), known), 200, cost, cash, Levels.PLAN)
                    for (k in 200 until n) {
                        val i = k - 200
                        assertTrue(same(full.decisions[i], cut.decisions[i]), "${rule.id} looks ahead: cut at $n, session $k, seed $seed")
                        assertTrue(same(full.decisions[i], mixed.decisions[i]), "${rule.id} looks ahead: scrambled after $n, session $k, seed $seed")
                    }
                }
                assertTrue(full.decisions.any { !it.isEmpty }, "${rule.id} never decided anything on seed $seed")
            }
        }
    }

    @Test
    fun sixStrategiesAreRegisteredWithTheResearchsVersionsCounted() {
        val all = EgxStrategies.all
        assertEquals(6, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(170, EgxStrategies.triedCount)
        assertEquals(listOf("Dividend run", "Calm stocks", "Dividend payers"), all.filter { it.launchTier.makesCalls }.map { it.name })
        assertEquals(listOf("Slow breakout", "Low-risk formula"), all.filter { it.launchTier == Tier.WATCH }.map { it.name })
        assertEquals(listOf(EgxStrategies.fastBreakout), all.filter { it.silent })
        assertTrue(all.all { it.summary.isNotBlank() })
    }

    @Test
    fun theDividendRunBuysOnTheAnnouncementAndSellsBeforeTheExDay() {
        val board = EgxSynth.board()
        val book = EgxSynth.dividends(board)
        val run = Slots.run(DividendRun(minYield = 0.0, universeSize = 80), Context(board, book), 200, { _, _ -> 0.0 }, DoubleArray(board.size))
        var checked = 0
        for (buy in run.fills.filter { it.buy }) {
            val sell = run.fills.firstOrNull { !it.buy && it.code == buy.code && it.k > buy.k } ?: continue
            val d = book.of(buy.code).first { board.sessionAtOrAfter(it.exDay) > buy.k }
            val s = board.stocks.getValue(buy.code)
            val boughtAt = board.sessionAtOrAfter(d.announced) + 1
            val soldAt = board.sessionAtOrAfter(d.exDay) - 1
            // A session the stock did not trade or was locked delays the fill; only clean cases pin the timing.
            if (!Slots.canFill(s, boughtAt, buy = true) || !Slots.canFill(s, soldAt, buy = false)) continue
            assertEquals(boughtAt, buy.k, "bought at the session after the assembly")
            assertEquals(soldAt, sell.k, "sold on the last session that carries the dividend")
            checked++
        }
        assertTrue(checked > 5)
    }

    @Test
    fun theSlowBreakoutWaitsAfterAJump() {
        val b = Breakout(100, 40, null, 80, chase = false, id = "t")
        val chasing = Breakout(100, 40, null, 80, chase = true, id = "t2")
        val long = tinyBoard("A" to List(200) { flat(10.0) } + listOf(doubleArrayOf(10.9, 10.95, 10.85, 10.9, 1e6)))
        val lc = Context(long, DividendBook())
        assertEquals(listOf("A"), chasing.decide(lc, 200, emptyMap()).buys)
        assertEquals(emptyList(), b.decide(lc, 200, emptyMap()).buys)
    }
}

class YardsticksTest {
    private val cost: (String, Int) -> Double = { _, _ -> 0.005 }

    @Test
    fun cashAndAnIndexCompound() {
        val board = EgxSynth.board(sessions = 300)
        val cash = Slots.flatCash(board, 0.19)
        val c = Yardsticks.cash(board, 10, cash)
        assertEquals(Math.pow(1 + cash[0], 245.0), c[244].equity, 1e-12)
        val close = DoubleArray(board.size) { 100.0 + it }
        val ix = Yardsticks.index(close, 10)
        assertEquals((100.0 + 299) / 109, ix.last().equity, 1e-12)
        val g = Growth.of(listOf(SlotDay(0, 1.0, 0.0), SlotDay(1, 1.2, 0.0), SlotDay(2, 0.9, 0.0), SlotDay(3, 1.1, 0.0)))!!
        assertEquals(1.1, g.multiple, 1e-12)
        assertEquals(-0.25, g.worstFall, 1e-12)
    }

    @Test
    fun randomTwinsFollowTheRulesTimingAndRepeat() {
        val board = EgxSynth.board(seed = 2)
        val ctx = Context(board, EgxSynth.dividends(board, 2))
        val cash = Slots.flatCash(board, 0.19)
        val rule = CalmStocks(top = 4, universeSize = 16)
        val base = Slots.run(rule, ctx, 250, cost, cash)
        val a = Yardsticks.randomTwin(rule, ctx, base, 250, cost, cash, draw = 0)
        val again = Yardsticks.randomTwin(rule, ctx, base, 250, cost, cash, draw = 0)
        val other = Yardsticks.randomTwin(rule, ctx, base, 250, cost, cash, draw = 1)
        assertEquals(a.fills.map { it.code to it.k }, again.fills.map { it.code to it.k })
        assertNotEquals(a.fills.map { it.code }, other.fills.map { it.code })
        val ruleDays = base.decisions.withIndex().filter { it.value.buys.isNotEmpty() }.map { it.index + 250 }.toSet()
        val twinDays = a.decisions.withIndex().filter { it.value.buys.isNotEmpty() }.map { it.index + 250 }.toSet()
        assertTrue(twinDays.isNotEmpty() && ruleDays.containsAll(twinDays), "a twin buys only on days the rule bought")
        assertEquals(Yardsticks.DRAWS, Yardsticks.randomTwins(rule, ctx, base, 250, cost, cash).size)
    }

    private fun record(months: Int, rule: Double, cash: Double = 1.2, index: Double? = 1.3, random: List<Double> = List(20) { 1.0 + it * 0.02 }) =
        YardstickRecord(months, Growth(rule, -0.1), Growth(cash, 0.0), index?.let { Growth(it, -0.2) }, random.sorted())

    @Test
    fun tiersMoveOnlyOnTheRulesOwnRecordAgainstTheYardsticks() {
        val calm = EgxStrategies.calm
        val slow = EgxStrategies.slowBreakout
        assertEquals(Tier.PROMISING, EgxTiers.judge(calm, null).tier)
        assertEquals(Tier.PROMISING, EgxTiers.judge(calm, record(6, 0.5)).tier, "too early to judge")
        assertEquals(Tier.WATCH, EgxTiers.judge(calm, record(12, 1.1)).tier, "behind cash and the EGX30")
        assertEquals(Tier.WATCH, EgxTiers.judge(calm, record(12, 1.25, cash = 1.2, index = 1.1, random = List(20) { 1.3 + it * 0.01 })).tier, "behind nine in ten random picks")
        assertEquals(Tier.PROMISING, EgxTiers.judge(calm, record(12, 1.25, cash = 1.2, index = 1.4)).tier, "ahead of cash though behind the index")
        assertEquals(Tier.WATCH, EgxTiers.judge(slow, record(18, 2.0)).tier, "promotion needs two years")
        assertEquals(Tier.PROMISING, EgxTiers.judge(slow, record(24, 2.0)).tier)
        assertEquals(Tier.WATCH, EgxTiers.judge(slow, record(24, 1.33, random = List(20) { 1.0 + it * 0.02 })).tier, "not above the random ninetieth percentile")
        assertEquals(Tier.TESTING, EgxTiers.judge(EgxStrategies.fastBreakout, record(36, 9.0)).tier, "the control stays silent")
    }
}
