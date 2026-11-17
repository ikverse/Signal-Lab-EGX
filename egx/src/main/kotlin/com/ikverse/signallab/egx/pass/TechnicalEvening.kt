package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.ledger.MarketDayRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PickChoiceRow
import com.ikverse.signallab.egx.ledger.PickEventRow
import com.ikverse.signallab.egx.ledger.PickRecordRow
import com.ikverse.signallab.egx.ledger.PickRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.egx.Context
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.EgxTiers
import com.ikverse.signallab.engine.egx.Growth
import com.ikverse.signallab.engine.egx.MarketState
import com.ikverse.signallab.engine.egx.Pick
import com.ikverse.signallab.engine.egx.PickAccount
import com.ikverse.signallab.engine.egx.PickEvent
import com.ikverse.signallab.engine.egx.PickExit
import com.ikverse.signallab.engine.egx.PickLearning
import com.ikverse.signallab.engine.egx.PickRun
import com.ikverse.signallab.engine.egx.PickStats
import com.ikverse.signallab.engine.egx.Slots
import com.ikverse.signallab.engine.egx.TechnicalList
import com.ikverse.signallab.engine.egx.YardstickRecord
import com.ikverse.signallab.engine.egx.Yardsticks

/** One list of technical picks after an evening. */
class ListEvening(
    val list: TechnicalList,
    val verdict: EgxTiers.Verdict,
    /** True when it puts its picks on Today: the owner has it on and its record has not switched it off. */
    val shows: Boolean,
    val run: PickRun,
    val choices: List<PickLearning.Choice>,
    val record: YardstickRecord,
    /** Its finished trades in the backtest before it went live (the version of each month), and live. */
    val backtest: PickStats?,
    val live: PickStats?,
    /** The picks made at the evening's close, best first. */
    val today: List<Pick>,
)

/** What the technical part of an evening produced. */
class TechnicalResult(
    val session: Long,
    val lists: List<ListEvening>,
    val market: MarketState,
    val days: List<PaperDayRow>,
    val yardsticks: List<YardstickRow>,
    val events: List<PickEventRow>,
    val choices: List<PickChoiceRow>,
    val records: List<PickRecordRow>,
    val marketRow: MarketDayRow,
) {
    /** The evening's new picks of the lists that show, for the notification. */
    val shownToday: List<Pick> get() = lists.filter { it.shows }.flatMap { it.today }

    /** Today's events at a target, and sells decided at the close, of the lists that show. */
    val reached: List<PickEventRow> get() {
        val shown = lists.filter { it.shows }.map { it.list.id }.toSet()
        return events.filter { it.day == session && it.list in shown && it.kind in REACHED }
    }

    companion object {
        val REACHED = setOf("target1", "target2", "sell_stop")
    }
}

/**
 * The technical picks' arithmetic for one evening (docs/egx-plan.md, technical picks), with no Android in it. Each
 * list's paper account runs from the session its ledger started on, with the version the monthly learning step chose
 * for each month from all the history before it (since 2010), against cash, the EGX30 and random twins.
 */
object TechnicalEvening {
    /** The learning step measures from the first session of this year, or the board's start. */
    const val HISTORY_FROM_YEAR = 2010

    fun run(snapshot: Snapshot, prefs: EgxPrefs, liveStart: Long): TechnicalResult {
        val board = snapshot.board
        val last = board.size - 1
        val from = board.sessionAtOrAfter(liveStart).coerceAtMost(last)
        val ctx = Context(board, snapshot.dividends.knownAt(board.calendar[last]))
        val cash = Slots.flatCash(board, prefs.cashRate)
        val cost = Evening.costs(snapshot, 1.0)
        val historyStart = (0..last).firstOrNull { board.date(it).year >= HISTORY_FROM_YEAR } ?: 0
        val lists = ArrayList<ListEvening>()
        val days = ArrayList<PaperDayRow>()
        val yard = ArrayList<YardstickRow>()
        val events = ArrayList<PickEventRow>()
        val choiceRows = ArrayList<PickChoiceRow>()
        val records = ArrayList<PickRecordRow>()
        val day = board.calendar[last]
        for (list in EgxStrategies.technical) {
            val history = PickLearning.history(list.versions, ctx, historyStart, cost, cash)
            val choices = PickLearning.choices(list.versions, history, board, from, historyStart)
            val setupAt = PickLearning.setupAt(board, choices, list.registered)
            val run = PickAccount.run(setupAt, ctx, from, cost, cash, start = prefs.pickAmount * PickAccount.SLOTS)
            val twins = PickAccount.twins(setupAt, ctx, from, cost, cash)
            val index = if (snapshot.egx30.drop(from).any { !it.isNaN() }) Growth.of(Yardsticks.index(snapshot.egx30, from)) else null
            val record = YardstickRecord(
                months = ((last - from + 1) / 20.4).toInt(),
                rule = Growth.of(run.days, start = prefs.pickAmount * PickAccount.SLOTS)!!,
                cash = Growth.of(Yardsticks.cash(board, from, cash))!!,
                index = index,
                random = twins.mapNotNull { t -> Growth.of(t.days)?.multiple }.sorted(),
            )
            val verdict = EgxTiers.judge(list, record)
            val shows = list.id in prefs.picksOn && EgxTiers.shows(verdict)
            // The backtest record: the trades of each month's chosen version, in the months before the list went live.
            val byMonth = PickLearning.choices(list.versions, history, board, historyStart, historyStart).associate { it.month to it.setup.id }
            val backTrades = list.versions.indices.flatMap { i ->
                history[i].trades.filter { t -> t.buyK < from && byMonth[java.time.YearMonth.from(board.date(t.pick.k))] == list.versions[i].id }
            }
            val backtest = PickStats.of(backTrades)
            val live = PickStats.of(run.trades)
            val today = run.picks.filter { it.k == last }
            lists.add(ListEvening(list, verdict, shows, run, choices, record, backtest, live, today))
            for (d in run.days) days.add(PaperDayRow(list.id, Records.PICKS, board.calendar[d.k], d.equity, d.held, 0.0))
            yard.add(YardstickRow(list.id, day, record.cash.multiple, index?.multiple ?: Double.NaN,
                record.randomLow ?: Double.NaN, record.randomMiddle ?: Double.NaN, record.randomHigh ?: Double.NaN))
            for (e in run.events) events.add(PickEventRow(board.calendar[e.k], list.id, e.pick.code, board.calendar[e.pick.k], kind(e.kind), if (e.price.isNaN()) 0.0 else e.price))
            for ((code, why) in run.sells) {
                val t = run.trades.lastOrNull { it.pick.code == code && it.exit == null } ?: continue
                val kind = when (why) { PickExit.STOP -> "sell_stop"; PickExit.TIME -> "sell_time"; else -> "sell_rule" }
                events.add(PickEventRow(day, list.id, code, board.calendar[t.pick.k], kind, board.stocks.getValue(code).close[last]))
            }
            for (c in choices) {
                val chosen = c.growth.getValue(c.setup.id)
                choiceRows.add(PickChoiceRow(c.month.toString(), list.id, c.setup.id, chosen, c.growth.getValue(list.registered.id)))
            }
            backtest?.let { records.add(recordRow(list.id, "backtest", day, it)) }
            live?.let { records.add(recordRow(list.id, "live", day, it)) }
        }
        val market = MarketState.at(board, snapshot.egx30, last)
        return TechnicalResult(day, lists, market, days, yard, events, choiceRows, records,
            MarketDayRow(day, market.egx30, market.above50, market.above200, market.uptrend, market.counted))
    }

    private fun recordRow(list: String, scope: String, day: Long, s: PickStats) =
        PickRecordRow(list, scope, day, s.trades, s.won, s.target1, s.target2, s.stopped, s.average, s.days)

    fun kind(k: PickEvent.Kind): String = when (k) {
        PickEvent.Kind.BOUGHT -> "bought"
        PickEvent.Kind.TARGET1 -> "target1"
        PickEvent.Kind.TARGET2 -> "target2"
        PickEvent.Kind.STOP -> "stop"
        PickEvent.Kind.TIME -> "time"
        PickEvent.Kind.RULE -> "rule"
        PickEvent.Kind.DELISTED -> "delisted"
        PickEvent.Kind.EXPIRED -> "expired"
    }

    /** A pick as its row, with what the news and the company's facts said about it on the evening. */
    fun row(p: Pick, list: TechnicalList, snapshot: Snapshot, shown: Boolean, hadNews: Boolean, flags: Set<String>, lossMaking: Boolean?): PickRow {
        val board = snapshot.board
        val s = board.stocks.getValue(p.code)
        val lv = p.levels
        return PickRow(
            day = board.calendar[p.k], list = list.id, version = p.setup.id, code = p.code, rank = p.rank,
            close = s.close[p.k], atr = s.atr[p.k], entryLow = lv.entryLow, entryHigh = lv.entryHigh, stop = lv.stop,
            target1 = lv.target1, target2 = lv.target2, limit = p.setup.limit, exitAverage = p.setup.exitAverage,
            breakout = p.candidate.floor, near = p.candidate.near, volumeRatio = p.candidate.volumeRatio,
            tightness = p.candidate.tightness.takeIf { !it.isNaN() } ?: 0.0, shown = shown,
            hadNews = hadNews, flags = flags.sorted().joinToString(","), lossMaking = lossMaking,
        )
    }
}
