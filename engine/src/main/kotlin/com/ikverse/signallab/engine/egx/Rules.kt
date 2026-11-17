package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.strategy.Tier

/**
 * Calm stocks (docs/egx-plan.md part 3): at each month's end, of the [universeSize] busiest stocks that traded on 57 of
 * the last 60 sessions and close above their [trend]-session average, hold the [top] with the smallest swings over
 * [window] sessions; keep those that stay on the list.
 */
class CalmStocks(
    val top: Int = 8, override val universeSize: Int = 80, val trend: Int = 200, val window: Int = 60, val minTraded: Int = 57,
    override val id: String = "egx_calm",
) : SlotRule {
    override val slots: Int get() = top

    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        if (ctx.board.monthEnding(k) == null) return Decision.NONE
        val scores = ArrayList<Pair<Double, String>>()
        for (code in ctx.board.universe(k, universeSize)) {
            val s = ctx.board.stocks.getValue(code)
            if (k - maxOf(window, 60) - 1 < s.first || s.tradedOf(k, 60) < minTraded) continue
            if (trend > 0) {
                val a = s.average(trend)[k]
                if (a.isNaN() || s.close[k] < a) continue
            }
            scores.add(variance(s, k, window) to code)
        }
        val want = scores.sortedWith(compareBy<Pair<Double, String>> { it.first }.thenBy { it.second }).take(top).map { it.second }
        return Decision.toList(held, want)
    }

    override fun pool(ctx: Context, k: Int): List<String> = super.pool(ctx, k).filter { code ->
        val s = ctx.board.stocks.getValue(code)
        k - 61 >= s.first && (trend == 0 || s.average(trend)[k].let { !it.isNaN() && s.close[k] >= it })
    }
}

/** The sum of squared deviations of the [n] daily returns ending at session [k], as the research ranks swings. */
internal fun variance(s: Stock, k: Int, n: Int): Double {
    val r = DoubleArray(n) { i -> val j = k - n + 1 + i; s.close[j] / s.close[j - 1] - 1 }
    var sum = 0.0
    for (x in r) sum += x
    val m = sum / n
    var v = 0.0
    for (x in r) v += (x - m) * (x - m)
    return v
}

/**
 * Dividend payers: at each month's end, hold the [top] stocks among the [universeSize] busiest that paid the most in
 * cash dividends over the last 250 sessions (with [uptrend], only those above their 200-session average).
 */
class DividendPayers(
    val top: Int = 8, override val universeSize: Int = 80, val uptrend: Boolean = false, override val id: String = "egx_payers",
) : SlotRule {
    override val slots: Int get() = top

    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        if (ctx.board.monthEnding(k) == null) return Decision.NONE
        val scores = ArrayList<Pair<Double, String>>()
        for (code in ctx.board.universe(k, universeSize)) {
            val y = ctx.dividends.paidOver(ctx.board, code, k, 250)
            val s = ctx.board.stocks.getValue(code)
            val a = s.average(200)[k]
            if (y > 0 && !(uptrend && (a.isNaN() || s.close[k] < a))) scores.add(y to code)
        }
        val want = scores.sortedWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second }).take(top).map { it.second }
        return Decision.toList(held, want)
    }

    override fun pool(ctx: Context, k: Int): List<String> = ctx.board.universe(k, universeSize)
}

/**
 * Dividend run: when a general assembly fixes a cash dividend worth [minYield] or more of the price, buy at the next
 * session, and sell on the last session that still carries it (the order goes in when the ex-day is one or two sessions
 * away). A holding that somehow stays longer than [lead] + 5 sessions is sold. About 17 trades a year, mostly April to June.
 */
class DividendRun(
    val lead: Int = DividendBook.ASSEMBLY_LEAD_SESSIONS - 1, val minYield: Double = 0.04, override val universeSize: Int = 80,
    val out: Int = 1, override val slots: Int = 8, override val id: String = "egx_dividend_run",
) : SlotRule {
    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        val b = ctx.board
        val sells = held.values.filter { h ->
            ctx.dividends.of(h.code).any { b.sessionsUntil(k, it.exDay) in 1..out + 1 } || k - h.k > lead + 5
        }.map { it.code }
        val since = if (k > 0) b.calendar[k - 1] else Long.MIN_VALUE
        val buys = ArrayList<Pair<Double, String>>()
        for (code in b.universe(k, universeSize)) {
            val d = ctx.dividends.of(code).firstOrNull { it.announced > since && it.announced <= b.calendar[k] && b.sessionsUntil(k, it.exDay) > out + 1 }
            if (d != null && d.share >= minYield) buys.add(d.share to code)
        }
        return Decision(sells, buys.sortedWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second }).map { it.second })
    }

    override fun pool(ctx: Context, k: Int): List<String> = ctx.board.universe(k, universeSize)
}

/**
 * A breakout: buy a close above the highest high of the last [enter] sessions, sell on a close below the lowest low of
 * the last [leave], at [maxDays] sessions when set, or on a close three average true ranges under the entry. Without
 * [chase], never the session after a jump of 8% (wait for the next new high).
 */
class Breakout(
    val enter: Int, val leave: Int, val maxDays: Int?, override val universeSize: Int, val chase: Boolean,
    override val id: String, override val slots: Int = 8,
) : SlotRule {
    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        val b = ctx.board
        val sells = ArrayList<String>()
        for ((code, h) in held) {
            val s = b.stocks.getValue(code)
            if (!s.alive(k)) continue
            var low = Double.POSITIVE_INFINITY
            for (j in maxOf(0, k - leave) until k) if (!s.low[j].isNaN()) low = minOf(low, s.low[j])
            val stop = if (h.atr.isNaN() || h.atr == 0.0) 0.0 else h.price - 3 * h.atr
            if ((low.isFinite() && s.close[k] < low) || (maxDays != null && k - h.k >= maxDays) || s.close[k] < stop) sells.add(code)
        }
        val buys = ArrayList<String>()
        for (code in b.universe(k, universeSize)) {
            val s = b.stocks.getValue(code)
            if (code in held || k - s.first < enter) continue
            var high = Double.NEGATIVE_INFINITY
            for (j in k - enter until k) high = maxOf(high, s.high[j])
            if (s.close[k] > high) {
                if (!chase && s.alive(k - 1) && s.close[k] / s.close[k - 1] - 1 >= 0.08) continue
                buys.add(code)
            }
        }
        return Decision(sells, buys)
    }

    override fun pool(ctx: Context, k: Int): List<String> = ctx.board.universe(k, universeSize)
}

/**
 * The low-risk formula (Blitz and van Vliet's conservative formula, shrunk to the Egyptian market, untuned): each quarter,
 * of the busiest stocks keep the calmer half by a year of swings, rank those by twelve-month rise (skipping the last
 * month) plus dividend yield, and hold the best [top].
 */
class LowRiskFormula(
    val top: Int = 10, override val universeSize: Int = 80, val keep: Double = 0.5, val every: Int = 3, override val id: String = "egx_low_risk",
) : SlotRule {
    override val slots: Int get() = top

    private class Row(val vol: Double, val mom: Double, val y: Double, val code: String)

    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        // Re-picked when the next session opens a new quarter (January, April, July, October), as the research tested it.
        if (ctx.board.monthEnding(k) == null || (ctx.board.nextSessionDay(k).monthValue - 1) % every != 0) return Decision.NONE
        val rows = ArrayList<Row>()
        for (code in ctx.board.universe(k, universeSize)) {
            val s = ctx.board.stocks.getValue(code)
            if (k - 252 < s.first || s.tradedOf(k, 60) < 57) continue
            rows.add(Row(variance(s, k, 250), s.close[k - 21] / s.close[k - 250] - 1, ctx.dividends.paidOver(ctx.board, code, k, 250), code))
        }
        rows.sortWith(compareBy<Row> { it.vol }.thenBy { it.mom }.thenBy { it.y }.thenBy { it.code })
        val calm = rows.take(maxOf(top, (rows.size * keep).toInt()))
        val byMom = calm.sortedByDescending { it.mom }.withIndex().associate { it.value.code to it.index }
        val byY = calm.sortedByDescending { it.y }.withIndex().associate { it.value.code to it.index }
        val want = byMom.keys.sortedBy { byMom.getValue(it) + byY.getValue(it) }.take(top)
        return Decision.toList(held, want)
    }

    override fun pool(ctx: Context, k: Int): List<String> =
        super.pool(ctx, k).filter { k - 252 >= ctx.board.stocks.getValue(it).first }
}

/**
 * Holding the 30 busiest stocks, re-picked when the next session opens March or September, never timed: the market
 * yardstick of the research.
 */
class HoldBusiest(val count: Int = 30, override val id: String = "egx_hold30") : SlotRule {
    override val slots: Int get() = count
    override val universeSize: Int get() = count

    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        val repick = ctx.board.monthEnding(k) != null && ctx.board.nextSessionDay(k).monthValue.let { it == 3 || it == 9 }
        if (held.isNotEmpty() && !repick) return Decision.NONE
        return Decision.toList(held, ctx.board.universe(k, count))
    }
}

/** A registered rule of the Egyptian app: its rule, the name a person reads, and where it starts. */
class EgxStrategy(
    val rule: SlotRule,
    val name: String,
    val launchTier: Tier,
    /** One line on what it does. */
    val summary: String,
    /** True for the control that runs into the ledger only and is never shown on Today. */
    val silent: Boolean = false,
    /** True for a list that is re-picked at month's or quarter's end. */
    val monthly: Boolean = false,
) {
    val id: String get() = rule.id
}

/**
 * Every rule of the Egyptian app, registered before it runs (docs/egx-plan.md part 3). The research tried about 170
 * versions on the same history before choosing these; every one counts toward the multiple-testing denominator.
 */
object EgxStrategies {
    /**
     * Rule versions tried by the research: 170 by the six passes and the levels test of 2026-11-14, and 70 by the
     * technical picks of 2026-11-16 (44 account versions and 26 event-study setups).
     */
    const val RESEARCH_VERSIONS = 240

    val dividendRun = EgxStrategy(
        DividendRun(), "Dividend run", Tier.PROMISING,
        "When a general assembly fixes a cash dividend worth 4% or more, buy; sell on the last day that still carries it.",
    )
    val calm = EgxStrategy(
        CalmStocks(), "Calm stocks", Tier.PROMISING,
        "At each month's end, hold the steadiest of the 80 busiest stocks that are above their 200-day average.", monthly = true,
    )
    val payers = EgxStrategy(
        DividendPayers(), "Dividend payers", Tier.PROMISING,
        "At each month's end, hold the stocks that paid the most in dividends over the last year.", monthly = true,
    )
    val slowBreakout = EgxStrategy(
        Breakout(100, 40, null, 80, chase = false, id = "egx_slow_breakout"), "Slow breakout", Tier.WATCH,
        "Buy a new 100-day high, sell on a close below the 40-day low; never the day after a jump of 8%.",
    )
    val lowRisk = EgxStrategy(
        LowRiskFormula(), "Low-risk formula", Tier.WATCH,
        "Each quarter, from the calmer half of the stocks, hold those with the best twelve-month rise and dividend yield.", monthly = true,
    )
    val fastBreakout = EgxStrategy(
        Breakout(50, 20, 60, 40, chase = true, id = "egx_fast_breakout"), "Fast breakout (control)", Tier.TESTING,
        "The crypto app's Breakout, run silently to show whether the research was right to drop it.", silent = true,
    )

    val all: List<EgxStrategy> = listOf(dividendRun, calm, payers, slowBreakout, lowRisk, fastBreakout)

    fun of(id: String): EgxStrategy? = all.firstOrNull { it.id == id }

    /** Everything ever tried: the research's versions, plus any rule registered later that was not among them (none yet). */
    val triedCount: Int get() = RESEARCH_VERSIONS

    /**
     * Short-term picks (stage 1 of the technical picks, 2026-11-16): the 8 versions tested, the registered one first
     * (a new 20-day high on at least average volume, held up to 20 sessions).
     */
    val shortTerm = TechnicalList(
        PickFamily.SHORT, "Short-term picks",
        "A new 20-day high on at least its average volume, in an uptrend; never the day after a jump of 8%. Held up to 20 sessions.",
        listOf(20, 55).flatMap { e -> listOf(1.0, 1.5).flatMap { v -> listOf(20, 10).map { d -> ShortTermSetup(e, v, d) } } }
            .sortedBy { if (it.enter == 20 && it.volume == 1.0 && it.limit == 20) 0 else 1 },
    )

    /**
     * Mid-term picks: the 8 versions with the 50-day exit and no targets, the registered one first (the full trend
     * template, the tightest fifth, held up to 120 sessions).
     */
    val midTerm = TechnicalList(
        PickFamily.MID, "Mid-term picks",
        "A tight 10-day range inside a strong uptrend. No targets: held until the stop, a close under the 50-day average, or 120 sessions.",
        listOf(true, false).flatMap { t -> listOf(false, true).flatMap { tenth -> listOf(120, 60).map { d -> MidTermSetup(t, tenth, d, 50) } } },
    )

    val technical: List<TechnicalList> = listOf(shortTerm, midTerm)

    fun technicalOf(id: String): TechnicalList? = technical.firstOrNull { it.id == id }
}

/** A list of technical picks: its setup's versions (the first is the one registered from the research), and where it starts. */
class TechnicalList(
    val family: PickFamily,
    val name: String,
    val summary: String,
    val versions: List<Setup>,
    val launchTier: Tier = Tier.TESTING,
) {
    val id: String get() = "egx_${family.name.lowercase()}_term"
    val registered: Setup get() = versions.first()
}
