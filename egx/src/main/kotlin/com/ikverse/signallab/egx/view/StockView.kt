package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.data.DailyRow
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.egx.EgxStrategies

/** How much history the stock page's chart shows. */
enum class ChartRange(val sessions: Int) { MONTHS_3(63), YEAR(245), ALL(Int.MAX_VALUE) }

/** A level drawn across the chart: its price, its name and whether it is the stop (red), the entry (green) or a target. */
data class ChartLevel(val price: Double, val name: String, val kind: Kind) { enum class Kind { ENTRY, STOP, TARGET } }

/** One closed paper trade on the stock: who made it, when it opened and closed, and what it made after costs. */
data class StockTrade(val strategy: String, val opened: Long, val closed: Long?, val result: Double?, val line: String)

/** The stock page, ready to draw. */
data class StockView(
    val code: String,
    val name: String,
    val lastLine: String,
    /** Session days and closes in the chosen range, oldest first. */
    val points: List<Pair<Long, Double>>,
    val levels: List<ChartLevel>,
    val rangeLine: String,
    val dividends: List<Pair<String, String>>,
    val trades: List<StockTrade>,
    val callsLine: String,
)

/**
 * Builds the stock page from what the phone already holds: the stock's stored closes, its dividends, every call the
 * strategies made on it and the paper fills. [open] is the call whose levels the chart draws, if one is open.
 */
fun stockView(
    code: String,
    name: String,
    rows: List<DailyRow>,
    dividends: List<EgxDividend>,
    calls: List<CallRow>,
    fills: List<PaperFillRow>,
    open: CallRow?,
    range: ChartRange,
    tr: Tr,
): StockView {
    val shown = rows.takeLast(range.sessions).map { it.day to it.close }
    val last = rows.lastOrNull()
    val first = shown.firstOrNull()
    val change = if (last != null && first != null && first.second > 0) last.close / first.second - 1 else null
    val levels = if (open == null) emptyList() else buildList {
        open.target2?.let { add(ChartLevel(it, tr.t("Target 2"), ChartLevel.Kind.TARGET)) }
        open.target1?.let { add(ChartLevel(it, tr.t("Target 1"), ChartLevel.Kind.TARGET)) }
        open.entryHigh?.let { add(ChartLevel(it, tr.t("Entry range"), ChartLevel.Kind.ENTRY)) }
        open.entryLow?.let { add(ChartLevel(it, tr.t("Entry range"), ChartLevel.Kind.ENTRY)) }
        open.stop?.let { add(ChartLevel(it, tr.t("Stop loss"), ChartLevel.Kind.STOP)) }
    }
    val mine = dividends.filter { it.code == code && !it.cancelled }.sortedByDescending { it.lastCumDay ?: it.anchor ?: 0L }
    val dividendLines = mine.take(8).map { d ->
        val amount = d.amount?.let { tr.t("EGP %1\$s a share", tr.price(it)) } ?: tr.t("Amount not known yet")
        val day = d.lastCumDay?.let { tr.t("last day to hold %1\$s", dayYear(it, tr)) }
            ?: d.paymentDay?.let { tr.t("cash date %1\$s", dayYear(it, tr)) }
            ?: tr.t("Date not known yet")
        amount to day
    }
    val trades = trades(code, fills, tr)
    val made = calls.filter { it.code == code && it.action != "keep" }
    return StockView(
        code, name,
        if (last == null) tr.t("No prices stored yet.") else tr.t("last close %1\$s on %2\$s", tr.price(last.close), dayYear(last.day, tr)),
        shown, levels,
        if (change == null) "" else tr.t("%1\$s over %2\$s", signed(change, tr), rangeName(range, tr)),
        dividendLines, trades,
        tr.t("%1\$d calls on this stock so far.", made.distinctBy { "${it.day}|${it.strategy}|${it.action}" }.size),
    )
}

/**
 * The paper trades on [code], one per strategy and holding: from the buy until the shares are back to none. A trade
 * still open has no result yet.
 */
fun trades(code: String, fills: List<PaperFillRow>, tr: Tr): List<StockTrade> {
    val out = ArrayList<StockTrade>()
    for ((strategy, list) in fills.filter { it.code == code && it.record == Records.LEVELS }.groupBy { it.strategy }) {
        var held = 0.0
        var paid = 0.0
        var got = 0.0
        var opened = 0L
        for (f in list.sortedWith(compareBy({ it.day }, { !it.buy }))) {
            if (f.buy) {
                if (held <= 1e-9) { opened = f.day; paid = 0.0; got = 0.0 }
                held += f.shares
                paid += f.shares * f.price + f.cost
            } else {
                held -= f.shares
                got += f.shares * f.price - f.cost
                if (held <= 1e-9 && paid > 0) {
                    val r = got / paid - 1
                    out += StockTrade(strategy, opened, f.day, r, tradeLine(strategy, opened, f.day, r, tr))
                    paid = 0.0
                }
            }
        }
        if (held > 1e-9) out += StockTrade(strategy, opened, null, null, tradeLine(strategy, opened, null, null, tr))
    }
    return out.sortedByDescending { it.opened }
}

private fun tradeLine(strategy: String, opened: Long, closed: Long?, result: Double?, tr: Tr): String {
    val name = EgxStrategies.of(strategy)?.name?.let { tr.t(it) } ?: strategy
    return if (closed == null || result == null) tr.t("%1\$s · bought %2\$s · still held", name, dayYear(opened, tr))
    else tr.t("%1\$s · %2\$s to %3\$s · %4\$s", name, dayYear(opened, tr), dayYear(closed, tr), signed(result, tr))
}

/** "12 November 2026" in the app's language. */
fun dayYear(day: Long, tr: Tr): String {
    val d = EgxTime.date(day)
    return "${dayText(d, tr)} ${tr.digits(d.year.toString())}"
}

/** A chart range's name, written out so the Arabic table test finds each text. */
fun rangeName(r: ChartRange, tr: Tr): String = when (r) {
    ChartRange.MONTHS_3 -> tr.t("3 months")
    ChartRange.YEAR -> tr.t("1 year")
    ChartRange.ALL -> tr.t("All")
}
