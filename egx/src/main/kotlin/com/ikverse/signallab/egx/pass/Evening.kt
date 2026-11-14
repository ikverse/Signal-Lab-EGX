package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.egx.CallLevels
import com.ikverse.signallab.engine.egx.Context
import com.ikverse.signallab.engine.egx.DividendRun
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.EgxStrategy
import com.ikverse.signallab.engine.egx.EgxTiers
import com.ikverse.signallab.engine.egx.Exit
import com.ikverse.signallab.engine.egx.Growth
import com.ikverse.signallab.engine.egx.Levels
import com.ikverse.signallab.engine.egx.SlotRun
import com.ikverse.signallab.engine.egx.Slots
import com.ikverse.signallab.engine.egx.YardstickRecord
import com.ikverse.signallab.engine.egx.Yardsticks
import kotlin.math.floor

/** One strategy after an evening: its four records, its yardsticks, its tier, and its calls for the next session. */
class StrategyEvening(
    val strategy: EgxStrategy,
    /** Its fixed share of "money I trade with", in pounds. */
    val share: Double,
    val runs: Map<String, SlotRun>,
    val yardstick: YardstickRecord,
    val tier: EgxTiers.Verdict,
    val calls: List<CallRow>,
)

/** What one evening pass produced: everything to append to the ledger, and tomorrow's plan. */
class EveningResult(
    val session: Long,
    val strategies: List<StrategyEvening>,
    val fills: List<PaperFillRow>,
    val days: List<PaperDayRow>,
    val yardsticks: List<YardstickRow>,
    /** The calls of strategies that are on and whose tier puts calls on Today. */
    val calls: List<CallRow>,
    /** True when this session ended a month: the evening of the month-end list. */
    val monthEnd: Boolean,
    /** Paper orders this calendar month across the strategies that are on, against Thndr's 50 free ones. */
    val ordersThisMonth: Int,
    /** Today's fills at a target or a stop, for the notification. */
    val levelsReached: List<PaperFillRow>,
)

/**
 * The evening pass's arithmetic, with no Android in it (the worker around it downloads, stores and notifies). Every
 * registered strategy runs from the session the ledger started on, at its fixed share of the money (item 25: split equally
 * among the strategies that are on, never rebalanced; a strategy that is not on is recorded at the same share, for
 * comparison), with the paper account's levels and by the rule alone, each at the real costs and at double.
 */
object Evening {
    const val JUMP = 0.08

    /** The round trip for a stock on a session: 0.5% for the 30 busiest, 0.8% for the rest, times [factor]. */
    fun costs(snapshot: Snapshot, factor: Double): (String, Int) -> Double = { code, k -> factor * Markets.EGX.roundTrip(snapshot.board.rank(k, code)) }

    fun run(snapshot: Snapshot, prefs: EgxPrefs, liveStart: Long, news: Map<String, Pair<Long?, Long?>> = emptyMap()): EveningResult {
        val board = snapshot.board
        val from = board.sessionAtOrAfter(liveStart).coerceAtMost(board.size - 1)
        val last = board.size - 1
        val ctx = Context(board, snapshot.dividends.knownAt(board.calendar[last]))
        val cash = Slots.flatCash(board, prefs.cashRate)
        val on = EgxStrategies.all.filter { it.id in prefs.on && !it.silent }
        val share = prefs.money / maxOf(1, on.size)
        val out = ArrayList<StrategyEvening>()
        val fills = ArrayList<PaperFillRow>()
        val days = ArrayList<PaperDayRow>()
        val yard = ArrayList<YardstickRow>()
        val calls = ArrayList<CallRow>()
        val monthEnd = board.monthEnding(last) != null
        for (s in EgxStrategies.all) {
            val runs = LinkedHashMap<String, SlotRun>()
            for (record in Records.ALL) {
                val levels = if (record.startsWith(Records.LEVELS)) Levels.PLAN else Levels.NONE
                val factor = if (record.endsWith("_x2")) 2.0 else 1.0
                val run = Slots.run(s.rule, ctx, from, costs(snapshot, factor), cash, levels, start = share)
                runs[record] = run
                for (f in run.fills) {
                    fills.add(PaperFillRow(s.id, record, board.calendar[f.k], f.code, f.buy, f.shares, f.price,
                        if (f.buy) "" else (f.exit ?: Exit.RULE).name.lowercase(), f.shares * f.price * costs(snapshot, factor)(f.code, f.k) / 2))
                }
                // Dividends paid in so far, on the evening's own row (earlier rows were written by earlier evenings).
                for (d in run.days) days.add(PaperDayRow(s.id, record, board.calendar[d.k], d.equity, d.held, if (d.k == last) run.dividends else 0.0))
            }
            val main = runs.getValue(Records.LEVELS)
            val twins = Yardsticks.randomTwins(s.rule, ctx, main, from, costs(snapshot, 1.0), cash, Levels.PLAN)
            val cashDays = Yardsticks.cash(board, from, cash)
            val index = if (snapshot.egx30.drop(from).any { !it.isNaN() }) Growth.of(Yardsticks.index(snapshot.egx30, from)) else null
            val random = twins.mapNotNull { t -> Growth.of(t.days, start = share)?.multiple }.sorted()
            val record = YardstickRecord(
                months = ((last - from + 1) / 20.4).toInt(),
                rule = Growth.of(main.days, start = share)!!,
                cash = Growth.of(cashDays)!!,
                index = index,
                random = random,
            )
            val verdict = EgxTiers.judge(s, record)
            yard.add(YardstickRow(s.id, board.calendar[last], record.cash.multiple, index?.multiple ?: Double.NaN,
                record.randomLow ?: Double.NaN, record.randomMiddle ?: Double.NaN, record.randomHigh ?: Double.NaN))
            val mine = if (s in on && verdict.tier.makesCalls) callsFor(s, main, snapshot, last, prefs, news, monthEnd) else emptyList()
            calls.addAll(mine)
            out.add(StrategyEvening(s, share, runs, record, verdict, mine))
        }
        val month = board.date(last).withDayOfMonth(1)
        val onIds = on.map { it.id }.toSet()
        val orders = fills.count { it.record == Records.LEVELS && it.strategy in onIds && !board.date(board.sessionAtOrAfter(it.day)).isBefore(month) }
        val reached = fills.filter { it.record == Records.LEVELS && it.day == board.calendar[last] && it.exit in setOf("stop", "target1", "target2") }
        return EveningResult(board.calendar[last], out, fills, days, yard, calls, monthEnd, orders, reached)
    }

    /**
     * The next session's calls of one strategy, from its paper account: a buy for each stock it wants while a slot is
     * free, with the pounds and whole shares of one slot of the account, the four levels, and when to place it (near
     * the close; or wait a day after a jump of 8%); a sell, at the open, for each stock it leaves; on the month-end list,
     * a "keep" for each stock that stays.
     */
    fun callsFor(s: EgxStrategy, run: SlotRun, snapshot: Snapshot, k: Int, prefs: EgxPrefs, news: Map<String, Pair<Long?, Long?>>, monthEnd: Boolean): List<CallRow> {
        val board = snapshot.board
        val day = board.calendar[k]
        val equity = run.days.lastOrNull()?.equity ?: return emptyList()
        val slot = equity / s.rule.slots
        val out = ArrayList<CallRow>()
        fun dividendDates(code: String): Pair<Long?, Long?> {
            if (s.rule !is DividendRun) return null to null
            val d = snapshot.dividends.of(code).firstOrNull { it.exDay > day } ?: return null to null
            val nd = news[code]
            return (nd?.first ?: board.calendar.getOrNull(board.sessionAtOrAfter(d.exDay) - 1)) to nd?.second
        }
        for ((code, why) in run.sells) {
            val h = run.held[code] ?: continue
            val st = board.stocks.getValue(code)
            val (cum, pay) = dividendDates(code)
            out.add(CallRow(day, s.id, code, "sell", floor(h.shares).toLong(), h.shares * st.close[k], st.close[k], null, null, null, null, null,
                "open", cum, pay, if (why == Exit.STOP) "stop" else if (s.rule is DividendRun) "dividend_last_day" else "rule", monthEnd))
        }
        var free = s.rule.slots - run.held.size + run.sells.size
        for (code in run.buys) {
            if (free <= 0) break
            if (code in run.held) continue
            val st = board.stocks.getValue(code)
            val close = st.close[k]
            if (close.isNaN() || !(close > 0)) continue
            val atr = st.atr[k]
            val lv = if (atr.isNaN()) null else CallLevels.of(close, atr)
            val jumped = k > 0 && st.alive(k - 1) && close / st.close[k - 1] - 1 >= JUMP
            val (cum, pay) = dividendDates(code)
            out.add(CallRow(day, s.id, code, "buy", floor(slot / close).toLong(), slot, close, lv?.entryLow, lv?.entryHigh, lv?.stop, lv?.target1, lv?.target2,
                if (jumped) "wait" else "close", cum, pay,
                when {
                    s.rule is DividendRun -> "dividend_announced"
                    s.monthly -> "monthly_list"
                    else -> "breakout"
                }, monthEnd))
            free--
        }
        if (monthEnd && s.monthly) {
            for ((code, h) in run.held) {
                if (code in run.sells) continue
                val st = board.stocks.getValue(code)
                val close = st.close[k]
                val lv = if (h.atr.isNaN()) null else CallLevels(Markets.EGX.round(h.price), Markets.EGX.round(h.price), Markets.EGX.round(h.price - 4 * h.atr), Markets.EGX.round(h.price + 5 * h.atr), Markets.EGX.round(h.price + 10 * h.atr))
                out.add(CallRow(day, s.id, code, "keep", floor(h.shares).toLong(), h.shares * close, close, lv?.entryLow, lv?.entryHigh, lv?.stop, lv?.target1, lv?.target2,
                    "none", null, null, "monthly_list", true))
            }
        }
        return out
    }

    /** The strategies that put calls on Today this evening, for the screens. */
    fun onToday(result: EveningResult, prefs: EgxPrefs): List<StrategyEvening> =
        result.strategies.filter { it.strategy.id in prefs.on && it.tier.tier.makesCalls }
}
