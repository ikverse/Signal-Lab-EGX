package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.Rng
import com.ikverse.signallab.engine.strategy.Tier

/**
 * The three things every Egyptian rule is measured against (docs/egx-plan.md part 4): cash at the owner's rate, the
 * EGX30, and random stocks picked on the same days and held as long. The third replaces "random entries" for the
 * monthly lists.
 */
object Yardsticks {
    /** Money left in cash from session [from] on. */
    fun cash(board: Board, from: Int, cashPerSession: DoubleArray, start: Double = 1.0): List<SlotDay> {
        var e = start
        return (from until board.size).map { k -> e *= 1 + cashPerSession[k]; SlotDay(k, e, 0.0) }
    }

    /** Holding an index whose close at each session is [close] (NaN where it is not known), from session [from] on. */
    fun index(close: DoubleArray, from: Int, start: Double = 1.0): List<SlotDay> {
        val base = (from - 1 downTo 0).firstOrNull { !close[it].isNaN() }?.let { close[it] } ?: close.drop(from).first { !it.isNaN() }
        var last = base
        return (from until close.size).map { k -> if (!close[k].isNaN()) last = close[k]; SlotDay(k, start * last / base, 1.0) }
    }

    /** Random draws per rule; the yardstick is their middle and their tenth and ninetieth percentiles. */
    const val DRAWS = 20

    /**
     * One random twin of [base], the run of [rule]: on each session the rule bought, it buys as many random stocks from
     * the rule's own [SlotRule.pool]; when the rule sells a stock, the twin sells the one it bought alongside. Same
     * money, slots, costs and levels. Draws come from a counter-based generator, so draw [draw] of a rule is the same on
     * every run and phone.
     */
    fun randomTwin(
        rule: SlotRule, ctx: Context, base: SlotRun, from: Int, cost: (String, Int) -> Double, cashPerSession: DoubleArray,
        draw: Int, levels: Levels = Levels.NONE, start: Double = 1.0,
    ): SlotRun {
        val seed = Rng.seedOf("egx-twin", rule.id, draw.toString())
        val partner = HashMap<String, String>()
        return Slots.run(rule, ctx, from, cost, cashPerSession, levels, start = start) { k, held ->
            val d = base.decisions[k - from]
            if (d.isEmpty) return@run Decision.NONE
            val sells = d.sells.mapNotNull { partner[it] }.filter { it in held }
            val buys = ArrayList<String>()
            if (d.buys.isNotEmpty()) {
                val pool = rule.pool(ctx, k).filter { it !in held || it in sells }.toMutableList()
                for ((j, code) in d.buys.withIndex()) {
                    pool.removeAll(buys)
                    if (pool.isEmpty()) break
                    val pick = pool[Rng.intIn(seed, k.toLong(), j.toLong(), 0, pool.size)]
                    buys.add(pick)
                    partner[code] = pick
                }
            }
            Decision(sells, buys)
        }
    }

    fun randomTwins(
        rule: SlotRule, ctx: Context, base: SlotRun, from: Int, cost: (String, Int) -> Double, cashPerSession: DoubleArray,
        levels: Levels = Levels.NONE, draws: Int = DRAWS,
    ): List<SlotRun> = (0 until draws).map { randomTwin(rule, ctx, base, from, cost, cashPerSession, it, levels) }
}

/** What 1.00 became over a run of days, and its deepest fall on the way. */
class Growth(val multiple: Double, val worstFall: Double) {
    companion object {
        /** From the close before [fromK] (or the start) to the last day at or before [toK]. */
        fun of(days: List<SlotDay>, fromK: Int = Int.MIN_VALUE, toK: Int = Int.MAX_VALUE, start: Double = 1.0): Growth? {
            val inside = days.filter { it.k in fromK..toK }
            if (inside.isEmpty()) return null
            val before = days.lastOrNull { it.k < inside.first().k }?.equity ?: start
            var peak = before
            var worst = 0.0
            for (d in inside) {
                peak = maxOf(peak, d.equity)
                worst = minOf(worst, d.equity / peak - 1)
            }
            return Growth(inside.last().equity / before, worst)
        }
    }
}

/** A rule's live record next to its yardsticks over the same sessions. */
class YardstickRecord(
    val months: Int,
    val rule: Growth,
    val cash: Growth,
    val index: Growth?,
    /** The random twins' multiples, sorted. */
    val random: List<Double>,
) {
    private fun percentile(p: Double): Double? =
        if (random.isEmpty()) null else random[((random.size - 1) * p).toInt().coerceIn(0, random.size - 1)]

    val randomLow: Double? get() = percentile(0.1)
    val randomMiddle: Double? get() = percentile(0.5)
    val randomHigh: Double? get() = percentile(0.9)
}

/**
 * How an Egyptian rule earns or loses its place, from its own live record against the three yardsticks only, never
 * against another rule (docs/egx-plan.md part 4). The numbers are fixed and written on the evidence page:
 *
 * - Fewer than [MIN_MONTHS] months live: the research tier stands.
 * - A rule on Today drops to **Watch** when it trails both cash and the EGX30, or ends below the tenth percentile of its
 *   random twins.
 * - A rule on Watch rises to **Promising** after [PROMOTE_MONTHS] months ahead of cash, the EGX30 and the ninetieth
 *   percentile of its random twins.
 * - The silent control never makes calls.
 */
object EgxTiers {
    const val MIN_MONTHS = 12
    const val PROMOTE_MONTHS = 24

    class Verdict(val tier: Tier, val reason: String)

    fun judge(s: EgxStrategy, live: YardstickRecord?): Verdict {
        if (s.silent) return Verdict(Tier.TESTING, "A control: it runs into the ledger only, to show whether the research was right to drop it.")
        if (live == null || live.months < MIN_MONTHS) {
            return Verdict(s.launchTier, if (s.launchTier.makesCalls) "The research supports it; too early for its live record to say more." else "Recorded without calls until its live record earns a place.")
        }
        val r = live.rule.multiple
        val behindCash = r < live.cash.multiple
        val behindIndex = live.index != null && r < live.index.multiple
        val low = live.randomLow
        val high = live.randomHigh
        if (s.launchTier.makesCalls) {
            if (behindCash && (live.index == null || behindIndex)) return Verdict(Tier.WATCH, "Behind cash and the EGX30 over ${live.months} months live.")
            if (low != null && r < low) return Verdict(Tier.WATCH, "Behind nine in ten random picks over ${live.months} months live.")
            return Verdict(s.launchTier, "Holding up against cash, the EGX30 and random picks over ${live.months} months live.")
        }
        if (live.months >= PROMOTE_MONTHS && !behindCash && !behindIndex && high != null && r > high) {
            return Verdict(Tier.PROMISING, "Ahead of cash, the EGX30 and nine in ten random picks over ${live.months} months live.")
        }
        return Verdict(s.launchTier, "Recorded without calls; it has not yet beaten all three yardsticks for $PROMOTE_MONTHS months.")
    }

    /**
     * A list of technical picks shows its picks while it is Testing. After [MIN_MONTHS] months live it is switched off
     * (Watch: recorded, not shown) when it trails both cash and the EGX30, or ends below nine in ten of its random twins.
     */
    fun judge(list: TechnicalList, live: YardstickRecord?): Verdict {
        if (live == null || live.months < MIN_MONTHS) return Verdict(list.launchTier, "On paper: too early for its live record to say more.")
        val r = live.rule.multiple
        val behindCash = r < live.cash.multiple
        val behindIndex = live.index != null && r < live.index.multiple
        val low = live.randomLow
        if (behindCash && (live.index == null || behindIndex)) return Verdict(Tier.WATCH, "Switched off: behind cash and the EGX30 over ${live.months} months live.")
        if (low != null && r < low) return Verdict(Tier.WATCH, "Switched off: behind nine in ten random picks over ${live.months} months live.")
        return Verdict(list.launchTier, "Holding up against cash, the EGX30 and random picks over ${live.months} months live.")
    }

    /** Whether a list of technical picks puts its picks on Today. */
    fun shows(v: Verdict): Boolean = v.tier != Tier.WATCH && v.tier != Tier.FAILING
}
