package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.Market
import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.Rng
import java.time.YearMonth

/**
 * One stock's chart indicators for the technical picks (docs/egx-research.md section 4), computed once over its whole
 * history exactly as the research's `technical.py` computes them. A value at session k reads sessions up to k only; a
 * swing high at session j is known from session j + 5.
 */
class Chart internal constructor(private val s: Stock) {
    private val n = s.close.size
    private val highs = HashMap<Int, DoubleArray>()
    private val lows = HashMap<Int, DoubleArray>()

    /** The highest high of the [days] sessions before each session, when 80% of them have a price; NaN otherwise. */
    fun priorHigh(days: Int): DoubleArray = highs.getOrPut(days) { prior(s.high, days, big = true) }

    /** The lowest low of the [days] sessions before each session. */
    fun priorLow(days: Int): DoubleArray = lows.getOrPut(days) { prior(s.low, days, big = false) }

    /** Window extremes of the sessions k - days .. k - 1, stored at k, for k from 0 to n (one past the last session). */
    private fun prior(x: DoubleArray, days: Int, big: Boolean): DoubleArray {
        val out = DoubleArray(n + 1) { Double.NaN }
        val q = IntArray(n + 1)
        var head = 0
        var tail = 0
        var count = 0
        for (k in 0..n) {
            if (k >= 1) {
                val j = k - 1
                if (!x[j].isNaN()) {
                    count++
                    while (tail > head && (if (big) x[q[tail - 1]] <= x[j] else x[q[tail - 1]] >= x[j])) tail--
                    q[tail++] = j
                }
                val old = k - 1 - days
                if (old >= 0 && !x[old].isNaN()) count--
                while (tail > head && q[head] < k - days) head++
            }
            if (k >= days && count >= 0.8 * days && tail > head) out[k] = x[q[head]]
        }
        return out
    }

    /** The average share volume of the 20 sessions ending at each session, only when it traded on every one of them. */
    val volume20: DoubleArray by lazy {
        val out = DoubleArray(n) { Double.NaN }
        var sum = 0.0
        var count = 0
        for (k in 0 until n) {
            val v = s.volume(k)
            if (v > 0) { sum += v; count++ }
            if (k >= 20) s.volume(k - 20).let { if (it > 0) { sum -= it; count-- } }
            if (k >= 19 && count == 20) out[k] = sum / 20
        }
        out
    }

    /** How tight the last 10 sessions were: their highest high less their lowest low, over the close. */
    val range10: DoubleArray by lazy {
        val hi = prior(s.high, 10, true)
        val lo = prior(s.low, 10, false)
        DoubleArray(n) { k -> if (s.close[k].isNaN() || hi[k + 1].isNaN() || lo[k + 1].isNaN()) Double.NaN else (hi[k + 1] - lo[k + 1]) / s.close[k] }
    }

    private val rangeQuantiles: Array<DoubleArray> by lazy {
        val fifth = DoubleArray(n) { Double.NaN }
        val tenth = DoubleArray(n) { Double.NaN }
        val w = DoubleArray(120)
        for (k in s.first + 260 until n) {
            var m = 0
            for (j in k - 120 until k) if (!range10[j].isNaN()) w[m++] = range10[j]
            if (m < 100) continue
            val sorted = w.copyOf(m).also { it.sort() }
            fifth[k] = sorted[m / 5]
            tenth[k] = sorted[m / 10]
        }
        arrayOf(fifth, tenth)
    }

    /** The stock's own tightest fifth of [range10] over the 120 sessions before each one (the edge of it). */
    val rangeFifth: DoubleArray get() = rangeQuantiles[0]

    /** The tightest tenth. */
    val rangeTenth: DoubleArray get() = rangeQuantiles[1]

    /** Sessions whose high tops the five sessions on each side: the swing highs, oldest first. */
    val swingHighs: IntArray by lazy {
        val out = ArrayList<Int>()
        for (j in s.first + 5 until n - 5) {
            if (s.high[j].isNaN()) continue
            var seen = 0
            var top = Double.NEGATIVE_INFINITY
            for (i in j - 5..j + 5) if (!s.high[i].isNaN()) { seen++; top = maxOf(top, s.high[i]) }
            if (seen >= 9 && s.high[j] >= top) out.add(j)
        }
        out.toIntArray()
    }

    /** The swing highs known at session [k] from the last 250 sessions that are above [price], lowest first, each once. */
    fun resistances(k: Int, price: Double): List<Double> {
        val p = swingHighs
        val out = sortedSetOf<Double>()
        for (j in p) {
            if (j < k - 250) continue
            if (j > k - 5) break
            if (s.high[j] > price) out.add(s.high[j])
        }
        return out.toList()
    }

    /** The lowest low of the [days] sessions ending at session [k]. */
    fun lowestLow(k: Int, days: Int = 10): Double {
        var m = Double.POSITIVE_INFINITY
        for (j in maxOf(0, k - days + 1)..k) if (!s.low[j].isNaN()) m = minOf(m, s.low[j])
        return m
    }
}

/** The two lists of technical picks. */
enum class PickFamily { SHORT, MID }

/** A stock a setup found at a close, with what it was ranked by. */
class Candidate(
    val code: String,
    /** The breakout level (the high it closed above), the lowest the entry range goes; null for a tight range. */
    val floor: Double?,
    /** The close over the highest high of the last 250 sessions. */
    val near: Double,
    /** The session's share volume over its 20-session average; 0 when unknown. */
    val volumeRatio: Double,
    /** The last 10 sessions' range over the close; NaN when not used. */
    val tightness: Double,
)

/**
 * A chart setup: what it looks for at a close, and how its picks are left. Its picks are bought within [ORDER_SESSIONS]
 * sessions inside the entry range, half sold at target 1 and the rest at target 2 when it has [targets], and the rest
 * sold at the next session after a close below the stop, after [limit] sessions, or after a close below its
 * [exitAverage]-session average.
 */
interface Setup {
    val id: String
    val family: PickFamily
    /** The most new picks it makes at one close. */
    val maxNew: Int
    val limit: Int
    val exitAverage: Int?
    val targets: Boolean

    /** The stocks it finds at the close of session [k], best first. Reads sessions up to [k] only. */
    fun find(board: Board, k: Int): List<Candidate>

    companion object {
        const val ORDER_SESSIONS = 3
        const val UNIVERSE = 80
    }
}

internal object Setups {
    /** Enough history, traded on 57 of the last 60 sessions, with a normal move and a 200-session average. */
    fun base(s: Stock, k: Int): Boolean {
        if (k - s.first < 260 || s.close[k].isNaN() || s.close[k - 1].isNaN() || s.tradedOf(k, 60) < 57) return false
        val a = s.atr[k]
        return !a.isNaN() && a != 0.0 && !s.average(200)[k].isNaN()
    }

    fun up(s: Stock, k: Int): Boolean {
        val m50 = s.average(50)[k]
        return !m50.isNaN() && s.close[k] > m50 && s.close[k] > s.average(200)[k]
    }

    /** The trend template: close over the 50 over the 150 over the 200-session average, the 200 rising for 20 sessions, 30% above the year's low and within 25% of its high. */
    fun template(s: Stock, k: Int): Boolean {
        val m50 = s.average(50)[k]
        val m150 = s.average(150)[k]
        val m200 = s.average(200)[k]
        val m200ago = if (k >= 20) s.average(200)[k - 20] else Double.NaN
        val hi = s.chart.priorHigh(250)[k]
        val lo = s.chart.priorLow(250)[k]
        if (m50.isNaN() || m150.isNaN() || m200.isNaN() || m200ago.isNaN() || hi.isNaN() || lo.isNaN()) return false
        val c = s.close[k]
        return c > m50 && m50 > m150 && m150 > m200 && m200 > m200ago && c >= 1.3 * lo && c >= 0.75 * hi
    }

    fun jumped(s: Stock, k: Int): Boolean = s.close[k] / s.close[k - 1] - 1 >= 0.08

    fun near(s: Stock, k: Int): Double = s.chart.priorHigh(250)[k].let { if (it.isNaN() || it == 0.0) 0.0 else s.close[k] / it }

    fun volumeRatio(s: Stock, k: Int): Double {
        val v = s.volume(k)
        val a = s.chart.volume20[k]
        return if (v > 0 && !a.isNaN() && a > 0) v / a else 0.0
    }
}

/**
 * Short-term picks: a close above the highest high of the last [enter] sessions, on at least [volume] times the
 * 20-session average volume, in an uptrend (above the 50 and 200-session averages), never the session after a jump of
 * 8%. Best first: nearest the year's high, then the heaviest volume. Up to three new picks a session.
 */
class ShortTermSetup(val enter: Int = 20, val volume: Double = 1.0, override val limit: Int = 20) : Setup {
    override val id: String = "egx_short_h${enter}_v${if (volume == 1.0) "1" else volume.toString().replace('.', '_')}_d$limit"
    override val family = PickFamily.SHORT
    override val maxNew = 3
    override val exitAverage: Int? = null
    override val targets = true

    override fun find(board: Board, k: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (code in board.universe(k, Setup.UNIVERSE)) {
            val s = board.stocks.getValue(code)
            if (!Setups.base(s, k) || !Setups.up(s, k) || Setups.jumped(s, k)) continue
            val hi = s.chart.priorHigh(enter)[k]
            if (hi.isNaN() || s.close[k] <= hi) continue
            val ratio = Setups.volumeRatio(s, k)
            if (ratio < volume) continue
            out.add(Candidate(code, hi, Setups.near(s, k), ratio, Double.NaN))
        }
        return out.sortedWith(compareByDescending<Candidate> { it.near }.thenByDescending { it.volumeRatio }.thenBy { it.code })
    }
}

/**
 * Mid-term picks: a tight 10-session range (within the stock's own tightest fifth, or tenth with [tenth], of the last
 * 120 sessions) inside the full trend template (or a plain uptrend without [template]). Best first: the tightest, then
 * nearest the year's high. No targets: sold on the stop, a close under the [exitAverage]-session average, or after
 * [limit] sessions. Up to two new picks a session.
 */
class MidTermSetup(val template: Boolean = true, val tenth: Boolean = false, override val limit: Int = 120, override val exitAverage: Int? = 50) : Setup {
    override val id: String = "egx_mid_${if (template) "template" else "uptrend"}_${if (tenth) "tenth" else "fifth"}_d$limit" +
        (exitAverage?.let { "_ma$it" } ?: "")
    override val family = PickFamily.MID
    override val maxNew = 2
    override val targets = false

    override fun find(board: Board, k: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (code in board.universe(k, Setup.UNIVERSE)) {
            val s = board.stocks.getValue(code)
            if (!Setups.base(s, k) || !(if (template) Setups.template(s, k) else Setups.up(s, k))) continue
            val r = s.chart.range10[k]
            val q = (if (tenth) s.chart.rangeTenth else s.chart.rangeFifth)[k]
            if (r.isNaN() || q.isNaN() || r > q) continue
            out.add(Candidate(code, null, Setups.near(s, k), Setups.volumeRatio(s, k), r))
        }
        return out.sortedWith(compareBy<Candidate> { it.tightness }.thenByDescending { it.near }.thenBy { it.code })
    }
}

/** A pick's levels in pounds, fixed at the close it was made on, as its card shows them. */
class PickLevels(val entryLow: Double, val entryHigh: Double, val stop: Double, val target1: Double?, val target2: Double?) {
    /** The same levels at the exchange's price step, for the card. */
    fun rounded(market: Market = Markets.EGX) = PickLevels(
        market.round(entryLow), market.round(entryHigh), market.round(stop), target1?.let(market::round), target2?.let(market::round),
    )
}

/**
 * The chart levels of a pick (docs/egx-research.md section 4):
 *
 * - entry range: from the breakout level, or half a normal day's move under the close when that is higher, up to the close;
 * - stop loss: a tenth of a move under the lowest low of the last 10 sessions, at least 1.5 and at most 4 moves under the close;
 * - target 1: the first swing high of the last 250 sessions at least twice the risk above the close, else twice the risk;
 * - target 2: the next swing high at least one risk above target 1, else four times the risk.
 */
object ChartLevels {
    fun of(s: Stock, k: Int, floor: Double?, targets: Boolean): PickLevels {
        val c = s.close[k]
        val a = s.atr[k]
        val lo = minOf(if (floor == null) c - 0.5 * a else maxOf(floor, c - 0.5 * a), c)
        val stop = maxOf(minOf(s.chart.lowestLow(k) - 0.1 * a, c - 1.5 * a), c - 4 * a)
        if (!targets) return PickLevels(lo, c, stop, null, null)
        val risk = c - stop
        val res = s.chart.resistances(k, c)
        val t1 = res.firstOrNull { it >= c + 2 * risk } ?: (c + 2 * risk)
        val t2 = res.firstOrNull { it >= t1 + risk } ?: if (c + 4 * risk > t1 + risk * 0.999) c + 4 * risk else t1 + risk
        return PickLevels(lo, c, stop, t1, t2)
    }
}

/** A pick: made at the close of session [k], to buy within [Setup.ORDER_SESSIONS] sessions. */
class Pick(val code: String, val k: Int, val setup: Setup, val levels: PickLevels, val candidate: Candidate, val rank: Int) {
    val until: Int get() = k + Setup.ORDER_SESSIONS
}

/** How a pick ended. */
enum class PickExit { TARGET2, STOP, TIME, RULE, DELISTED, EXPIRED }

/** A pick that was bought: when, at what price, and (once over) how it ended and what it returned after costs. */
class PickTrade(val pick: Pick, val buyK: Int, val buyPrice: Double) {
    var half: Boolean = false
        internal set
    var sellK: Int? = null
        internal set
    var exit: PickExit? = null
        internal set

    /** The money paid in and taken out, per pound paid, after costs and dividends. */
    internal var got: Double = 0.0
    internal var shares: Double = 0.0

    /** What it returned after costs once it is over; null while held. */
    val result: Double? get() = if (exit == null) null else got - 1.0
}

/** Something that happened to a pick at a session, for the ledger and the notifications. */
class PickEvent(val k: Int, val pick: Pick, val kind: Kind, val price: Double) {
    enum class Kind { BOUGHT, TARGET1, TARGET2, STOP, TIME, RULE, DELISTED, EXPIRED }
}

class PickRun(
    val days: List<SlotDay>,
    /** Every pick made, oldest first. */
    val picks: List<Pick>,
    /** Every pick bought, oldest first, over or still held. */
    val trades: List<PickTrade>,
    val events: List<PickEvent>,
    /** Picks still waiting to be bought after the last close. */
    val waiting: List<Pick>,
    /** Held picks to be sold at the next session, with why. */
    val sells: Map<String, PickExit>,
)

/**
 * The technical picks followed as one paper account, exactly as `technical.py`'s `run` follows them: [SLOTS] equal
 * slots, costs by the stock's rank, idle money at the cash rate, dividends after tax.
 *
 * 1. At the open, sells decided last evening fill at the open (not while locked at the limit); a stock gone from the
 *    data is paid out at its last close.
 * 2. Waiting picks, oldest first, fill while a slot is free: at the open when it is inside the entry range, at the top
 *    of the range when the session comes back down to it, else they wait, three sessions at most.
 * 3. On each held pick's own session range: half at target 1, the rest at target 2; a close under the stop, the time
 *    limit, or a close under the exit average sells at the next open.
 * 4. At the close the setup of that month finds new picks, up to its [Setup.maxNew] not already held or waiting.
 *
 * [twin] swaps each session's picks for as many random stocks among those the setup could look at, with the same
 * levels and exits: the random yardstick.
 */
object PickAccount {
    const val SLOTS = 8

    fun run(
        setupAt: (Int) -> Setup,
        ctx: Context,
        from: Int,
        cost: (String, Int) -> Double,
        cashPerSession: DoubleArray,
        start: Double = 1.0,
        useRange: Boolean = true,
        useStop: Boolean = true,
        useTargets: Boolean = true,
        twin: Int? = null,
        market: Market = Markets.EGX,
    ): PickRun {
        val board = ctx.board
        var cash = start
        var equity = start
        val held = LinkedHashMap<String, PickTrade>()
        val sells = LinkedHashMap<String, PickExit>()
        var orders = ArrayList<Pick>()
        val last = HashMap<String, Double>()
        val days = ArrayList<SlotDay>()
        val picks = ArrayList<Pick>()
        val trades = ArrayList<PickTrade>()
        val events = ArrayList<PickEvent>()
        val seed = twin?.let { Rng.seedOf("egx-pick-twin", setupAt(from).family.name, it.toString()) }
        val paidTotal = HashMap<PickTrade, Double>()

        fun close(t: PickTrade, price: Double, k: Int, why: PickExit) {
            val back = t.shares * price * (1 - cost(t.pick.code, k) / 2)
            cash += back
            t.got += back / paidTotal.getValue(t)
            t.shares = 0.0
            t.sellK = k
            t.exit = why
            held.remove(t.pick.code)
            events.add(PickEvent(k, t.pick, kindOf(why), price))
        }

        for (k in from until board.size) {
            for (code in ArrayList(sells.keys)) {
                val t = held[code]
                val s = board.stocks.getValue(code)
                if (t == null) sells.remove(code)
                else if (Slots.canFill(s, k, buy = false, market)) close(t, s.open[k], k, sells.remove(code)!!)
                else if (!s.alive(k)) close(t, last.getValue(code), k, sells.remove(code)!!)
            }
            val keep = ArrayList<Pick>()
            for (o in orders) {
                if (o.code in held) continue
                val s = board.stocks.getValue(o.code)
                if (held.size >= SLOTS || !Slots.canFill(s, k, buy = true, market)) {
                    if (k < o.until) keep.add(o) else events.add(PickEvent(k, o, PickEvent.Kind.EXPIRED, Double.NaN))
                    continue
                }
                var px = s.open[k]
                if (useRange && px > o.levels.entryHigh) {
                    if (s.low[k] <= o.levels.entryHigh) px = o.levels.entryHigh
                    else {
                        if (k < o.until) keep.add(o) else events.add(PickEvent(k, o, PickEvent.Kind.EXPIRED, Double.NaN))
                        continue
                    }
                }
                val amount = minOf(equity / SLOTS, cash)
                if (amount <= 0) {
                    if (k < o.until) keep.add(o)
                    continue
                }
                cash -= amount
                val t = PickTrade(o, k, px)
                t.shares = amount * (1 - cost(o.code, k) / 2) / px
                paidTotal[t] = amount
                held[o.code] = t
                trades.add(t)
                events.add(PickEvent(k, o, PickEvent.Kind.BOUGHT, px))
            }
            orders = keep
            cash *= 1 + cashPerSession[k]
            for (code in ArrayList(held.keys)) {
                val t = held.getValue(code)
                val s = board.stocks.getValue(code)
                val paid = paidTotal.getValue(t)
                val d = ctx.dividends.exOn(board, code, k)
                if (d != null && t.buyK < k && k > 0 && s.alive(k - 1)) {
                    val money = t.shares * s.close[k - 1] * d.share * (1 - market.dividendTax)
                    cash += money
                    t.got += money / paid
                }
                if (!s.alive(k) || !s.traded(k) || t.buyK == k || code in sells) continue
                val lv = t.pick.levels
                val targets = useTargets && t.pick.setup.targets
                val t1 = lv.target1
                if (targets && t1 != null && !t.half && s.high[k] >= t1) {
                    val part = t.shares / 2
                    val back = part * t1 * (1 - cost(code, k) / 2)
                    cash += back
                    t.got += back / paid
                    t.shares -= part
                    t.half = true
                    events.add(PickEvent(k, t.pick, PickEvent.Kind.TARGET1, t1))
                }
                val t2 = lv.target2
                if (targets && t2 != null && s.high[k] >= t2) {
                    close(t, t2, k, PickExit.TARGET2)
                    continue
                }
                val ma = t.pick.setup.exitAverage
                if (useStop && s.close[k] < lv.stop) sells[code] = PickExit.STOP
                else if (k - t.buyK >= t.pick.setup.limit) sells[code] = PickExit.TIME
                else if (ma != null && !s.average(ma)[k].isNaN() && s.close[k] < s.average(ma)[k]) sells[code] = PickExit.RULE
            }
            var worth = 0.0
            for ((code, t) in held) {
                val s = board.stocks.getValue(code)
                var c = if (s.alive(k)) s.close[k] else Double.NaN
                if (c.isNaN()) {
                    c = last[code] ?: t.buyPrice
                    sells.putIfAbsent(code, PickExit.DELISTED)
                }
                last[code] = c
                worth += t.shares * c
            }
            equity = cash + worth
            days.add(SlotDay(k, equity, if (equity != 0.0) worth / equity else 0.0))
            val setup = setupAt(k)
            var found = setup.find(board, k)
            if (seed != null) {
                val pool = board.universe(k, Setup.UNIVERSE).filter { it !in held && Setups.base(board.stocks.getValue(it), k) }.toMutableList()
                val n = minOf(found.size, setup.maxNew, pool.size)
                val drawn = ArrayList<Candidate>()
                for (j in 0 until n) {
                    val c = pool.removeAt(Rng.intIn(seed, k.toLong(), j.toLong(), 0, pool.size))
                    drawn.add(Candidate(c, null, 0.0, 0.0, Double.NaN))
                }
                found = drawn
            }
            val pending = orders.mapTo(HashSet()) { it.code }
            var new = 0
            for (c in found) {
                if (new >= setup.maxNew) break
                if (c.code in held || c.code in pending) continue
                new++
                val p = Pick(c.code, k, setup, ChartLevels.of(board.stocks.getValue(c.code), k, c.floor, setup.targets && useTargets), c, new)
                orders.add(p)
                picks.add(p)
            }
        }
        return PickRun(days, picks, trades, events, orders, LinkedHashMap(sells))
    }

    private fun kindOf(e: PickExit): PickEvent.Kind = when (e) {
        PickExit.TARGET2 -> PickEvent.Kind.TARGET2
        PickExit.STOP -> PickEvent.Kind.STOP
        PickExit.TIME -> PickEvent.Kind.TIME
        PickExit.RULE -> PickEvent.Kind.RULE
        PickExit.DELISTED -> PickEvent.Kind.DELISTED
        PickExit.EXPIRED -> PickEvent.Kind.EXPIRED
    }

    /** Random twins of a list: the same setups, timing, levels and exits on random stocks. */
    fun twins(
        setupAt: (Int) -> Setup, ctx: Context, from: Int, cost: (String, Int) -> Double, cashPerSession: DoubleArray, draws: Int = Yardsticks.DRAWS,
    ): List<PickRun> = (0 until draws).map { run(setupAt, ctx, from, cost, cashPerSession, twin = it) }
}

/** A list's record from its finished trades. */
class PickStats(
    val trades: Int,
    /** Shares of the finished trades: made money after costs, reached target 1, reached target 2, were stopped. */
    val won: Double,
    val target1: Double,
    val target2: Double,
    val stopped: Double,
    val average: Double,
    /** The middle number of sessions held. */
    val days: Int,
) {
    companion object {
        fun of(trades: List<PickTrade>): PickStats? {
            val done = trades.filter { it.exit != null && it.exit != PickExit.EXPIRED }
            if (done.isEmpty()) return null
            val n = done.size.toDouble()
            val held = done.map { it.sellK!! - it.buyK }.sorted()
            return PickStats(
                done.size,
                done.count { it.result!! > 0 } / n,
                done.count { it.half || it.exit == PickExit.TARGET2 } / n,
                done.count { it.exit == PickExit.TARGET2 } / n,
                done.count { it.exit == PickExit.STOP } / n,
                done.sumOf { it.result!! } / n,
                held[held.size / 2],
            )
        }
    }
}

/**
 * The monthly learning step: on the first session of each month a list uses the version of its setup whose paper
 * account did best over all the history before that month (from [historyStart]), measured on past data only. The first
 * version of a list is the one registered from the research, used until there is a year of history to choose with.
 */
object PickLearning {
    class Choice(val month: YearMonth, val setup: Setup, val growth: Map<String, Double>)

    /** The choice for each month from the month of session [from] to the board's last. */
    fun choices(
        versions: List<Setup>, ctx: Context, from: Int, historyStart: Int, cost: (String, Int) -> Double, cashPerSession: DoubleArray,
    ): List<Choice> {
        val board = ctx.board
        val curves = versions.map { v -> PickAccount.run({ v }, ctx, historyStart, cost, cashPerSession).days }
        val out = ArrayList<Choice>()
        var k = from
        while (k < board.size) {
            val month = YearMonth.from(board.date(k))
            var first = k
            while (first > 0 && YearMonth.from(board.date(first - 1)) == month) first--
            val end = first - 1
            val growth = LinkedHashMap<String, Double>()
            for ((i, v) in versions.withIndex()) {
                val d = curves[i]
                val at = end - historyStart
                growth[v.id] = if (at >= 0 && at < d.size) d[at].equity else 1.0
            }
            val enough = end - historyStart >= 245
            val best = if (!enough) versions[0] else versions.maxWith(compareBy<Setup> { growth.getValue(it.id) }.thenByDescending { versions.indexOf(it) })
            out.add(Choice(month, best, growth))
            while (k < board.size && YearMonth.from(board.date(k)) == month) k++
        }
        return out
    }

    /** A setup for every session, from the month's choice. */
    fun setupAt(board: Board, choices: List<Choice>, fallback: Setup): (Int) -> Setup {
        val byMonth = choices.associate { it.month to it.setup }
        return { k -> byMonth[YearMonth.from(board.date(k))] ?: fallback }
    }
}

/** The market's state at a close: the EGX30 against its 50 and 200-session averages, and how many stocks are in an uptrend. */
class MarketState(
    val egx30: Double?,
    val above50: Boolean?,
    val above200: Boolean?,
    /** Stocks among the 80 busiest above both their 50 and 200-session averages, and how many had both. */
    val uptrend: Int,
    val counted: Int,
) {
    val uptrendShare: Double get() = if (counted == 0) 0.0 else uptrend.toDouble() / counted

    companion object {
        fun at(board: Board, egx30: DoubleArray, k: Int): MarketState {
            fun average(n: Int): Double? {
                var sum = 0.0
                var m = 0
                var j = k
                while (j >= 0 && m < n) { if (!egx30[j].isNaN()) { sum += egx30[j]; m++ }; j-- }
                return if (m == n) sum / n else null
            }
            val last = (k downTo 0).firstOrNull { !egx30[it].isNaN() }?.let { egx30[it] }
            val a50 = average(50)
            val a200 = average(200)
            var up = 0
            var counted = 0
            for (code in board.universe(k, Setup.UNIVERSE)) {
                val s = board.stocks.getValue(code)
                val m50 = s.average(50)[k]
                val m200 = s.average(200)[k]
                if (m50.isNaN() || m200.isNaN() || s.close[k].isNaN()) continue
                counted++
                if (s.close[k] > m50 && s.close[k] > m200) up++
            }
            return MarketState(last, if (last != null && a50 != null) last > a50 else null, if (last != null && a200 != null) last > a200 else null, up, counted)
        }
    }
}
