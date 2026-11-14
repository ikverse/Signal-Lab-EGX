package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.CopyRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.EgxStrategy
import com.ikverse.signallab.engine.strategy.Tier
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The research behind each strategy, as `gradlew :research:run --args="egx"` re-made it (docs/egx-research.md §3): what
 * 1.00 became in 2015-22 and 2023-26, and with the levels.
 */
object Evidence {
    class Research(val old: Double, val recent: Double, val levelsOld: Double?, val levelsRecent: Double?, val worstFall: Double?)

    val BY_ID: Map<String, Research> = mapOf(
        EgxStrategies.dividendRun.id to Research(3.02, 2.99, 2.96, 2.85, -0.04),
        EgxStrategies.calm.id to Research(3.63, 5.63, 2.78, 4.34, -0.26),
        EgxStrategies.payers.id to Research(9.21, 3.79, 3.60, 2.74, -0.39),
        EgxStrategies.slowBreakout.id to Research(7.76, 5.15, null, null, null),
        EgxStrategies.lowRisk.id to Research(3.00, 5.49, null, null, null),
        EgxStrategies.fastBreakout.id to Research(10.95, 2.33, null, null, null),
    )
    const val CASH_OLD = 2.41
    const val CASH_RECENT = 1.98
    const val MARKET_OLD = 0.99
    const val MARKET_RECENT = 3.76
    const val EGX30_RECENT = 3.52
}

/** One row of a yardstick bar chart: a label and a growth share (0.31 = +31%). */
data class BarRow(val label: String, val value: Double?, val mine: Boolean = false)

/** A strategy as the Strategies page and the Ledger show it. */
data class StrategyView(
    val strategy: EgxStrategy,
    val name: String,
    val tier: Tier,
    val tierLine: String,
    val summary: String,
    val bars: List<BarRow>,
    val equity: Double?,
    val calls: Int,
    val holds: Int,
)

fun dayText(d: LocalDate, tr: Tr): String {
    val locale = if (tr.isArabic) Locale.forLanguageTag("ar-EG") else Locale.ENGLISH
    return tr.digits("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, locale)}")
}

fun signed(x: Double?, tr: Tr): String = x?.let { (if (it >= 0) "+" else "−") + tr.percent(kotlin.math.abs(it), 0) } ?: "–"

/** Tier labels, as the mockup words them. */
fun tierLine(t: Tier, tr: Tr): String = when (t) {
    Tier.PROVEN, Tier.PROMISING -> tr.t("Promising · calls on Today")
    Tier.WATCH -> tr.t("Watch · recorded, no calls")
    else -> tr.t("Control · silent, no calls")
}

/**
 * Every strategy's live record against its yardsticks: its paper account (with the levels) since the ledger started,
 * cash, the EGX30 and the middle of its random twins, over the same sessions.
 */
fun strategyViews(days: List<PaperDayRow>, yard: List<YardstickRow>, fills: List<PaperFillRow>, tr: Tr): List<StrategyView> =
    EgxStrategies.all.map { s ->
        val mine = days.filter { it.strategy == s.id && it.record == Records.LEVELS }.sortedBy { it.day }
        val y = yard.filter { it.strategy == s.id }.maxByOrNull { it.day }
        val growth = if (mine.size >= 1) mine.last().equity / mine.first().equity - 1 else null
        val myFills = fills.filter { it.strategy == s.id && it.record == Records.LEVELS }
        val held = myFills.groupBy { it.code }.count { (_, f) -> f.sumOf { if (it.buy) it.shares else -it.shares } > 1e-9 }
        val tier = if (s.silent) Tier.TESTING else s.launchTier
        StrategyView(
            s, tr.t(s.name), tier, tierLine(tier, tr), tr.t(s.summary),
            listOf(
                BarRow(tr.t("This strategy"), growth, mine = true),
                BarRow(tr.t("Cash"), y?.cash?.minus(1)),
                BarRow("EGX30", y?.egx30?.takeIf { !it.isNaN() }?.minus(1)),
                BarRow(tr.t("8 random stocks"), y?.randomMiddle?.takeIf { !it.isNaN() }?.minus(1)),
            ),
            mine.lastOrNull()?.equity, myFills.count { it.buy }, held,
        )
    }

/** The call page (item 31), ready to draw. */
data class CallView(
    val call: CallRow,
    val action: String,
    val buy: Boolean,
    val name: String,
    val strategy: String,
    val closeLine: String,
    val chips: List<String>,
    val whenText: String,
    val levels: List<Triple<Double, String, String>>,
    val levelsNote: String,
    val amount: List<Pair<String, String>>,
    val amountNote: String,
    val dividend: List<Pair<String, String>>,
    val dividendNote: String?,
    val reason: String,
    val copyText: String,
)

fun callView(c: CallRow, name: String, prefs: EgxPrefs, tr: Tr): CallView {
    val s = EgxStrategies.of(c.strategy)
    val next = com.ikverse.signallab.engine.egx.Sessions.nextSession(EgxTime.date(c.day), prefs.holidays)
    val whenText = when (c.timing) {
        "close" -> tr.t("Tomorrow, %1\$s, near the close: between 2:00 and 2:30 pm. Buying late in the day costs less than buying in the morning.", dayText(next, tr))
        "open" -> tr.t("Sell at the open: in the first minutes of the session.")
        "wait" -> tr.t("Wait a day: the stock jumped %1\$s today. Buying the morning after a jump usually costs more than it gains.", "8%+")
        else -> ""
    }
    val levels = buildList {
        c.target2?.let { add(Triple(it, tr.t("Target 2"), tr.t("Sell the rest"))) }
        c.target1?.let { add(Triple(it, tr.t("Target 1"), tr.t("Sell half · %1\$d shares", c.shares / 2))) }
        if (c.entryHigh != null && c.entryLow != null) add(Triple(c.entryHigh, tr.t("Entry range"), tr.t("%1\$s to %2\$s · above it, wait", tr.price(c.entryLow), tr.price(c.entryHigh))))
        c.stop?.let { add(Triple(it, tr.t("Stop loss"), tr.t("Sell if a day closes below"))) }
    }
    val cost = c.pounds * c.roundTrip
    val onCount = maxOf(1, prefs.on.size)
    return CallView(
        c, when (c.action) { "buy" -> tr.t("Buy"); "keep" -> tr.t("Keep"); else -> tr.t("Sell") }, c.action == "buy", name,
        s?.name?.let { tr.t(it) } ?: c.strategy, tr.t("last close %1\$s", tr.price(c.close)),
        listOfNotNull(tr.t("%1\$d shares", c.shares), tr.t("EGP %1\$s", tr.pounds(c.pounds)), when (c.timing) { "close" -> tr.t("Near the close"); "open" -> tr.t("At the open"); "wait" -> tr.t("Wait a day"); else -> null }),
        whenText, levels,
        tr.t("A normal day's move for %1\$s is %2\$s. The stop is four moves below the entry, the targets five and ten above.", c.code, tr.price(c.atr)),
        listOf(
            tr.t("Pounds") to tr.t("EGP %1\$s", tr.pounds(c.pounds)),
            tr.t("Whole shares at %1\$s", tr.price(c.close)) to tr.digits("${c.shares} = ${tr.pounds(c.shares * c.close)}"),
            tr.t("Costs, buy and sell") to tr.t("about %1\$s · at double %2\$s", tr.pounds(cost), tr.pounds(2 * cost)),
        ),
        tr.t("Each strategy that is on gets an equal share of your %1\$s: %2\$s.", tr.t("EGP %1\$s", tr.pounds(prefs.money)), tr.t("EGP %1\$s", tr.pounds(prefs.money / onCount))),
        buildList {
            c.lastCumDay?.let { add(tr.t("Last day to hold") to dayText(EgxTime.date(it), tr)) }
            c.paymentDay?.let { add(tr.t("Cash date") to dayText(EgxTime.date(it), tr)) }
        },
        if (c.lastCumDay != null) tr.t("The call sells at the open of the last day to hold, before the dividend: the run is paid by the rise before it, not by the dividend itself.") else null,
        when (c.reason) {
            "dividend_announced" -> tr.t("A general assembly fixed a cash dividend worth 4% or more of the price; stocks like this have tended to rise until the dividend's last day.")
            "dividend_last_day" -> tr.t("Tomorrow is the last day that carries the dividend.")
            "monthly_list" -> if (c.action == "buy") tr.t("Joined the list") else if (c.action == "keep") tr.t("Still on the list") else tr.t("Left the list")
            "stop" -> tr.t("stop loss hit")
            "breakout" -> tr.t("A new 100-day high.")
            else -> tr.t(s?.summary ?: "")
        },
        c.shares.toString(),
    )
}

/** What "I took it" compares: the real price against the plan, as a share. */
fun copyGap(c: CallRow, price: Double): Double = if (c.action == "buy") price / c.close - 1 else c.close / price - 1

/** The Ledger (item 33), ready to draw. */
data class LedgerView(
    val since: String,
    val lead: String,
    val account: Double,
    val accountBars: List<BarRow>,
    val withTargets: Double,
    val byRule: Double,
    val targetsCost: String,
    val byStrategy: List<StrategyView>,
    val watched: List<StrategyView>,
    val costsPaid: Double,
    val atDouble: Double,
    val dividends: Double,
    val copies: List<CopyRow>,
    val copiesLine: String,
    /** False until the first evening pass has written the ledger: the page then says so instead of showing empty figures. */
    val started: Boolean = true,
)

fun ledgerView(days: Map<String, List<PaperDayRow>>, yard: List<YardstickRow>, fills: Map<String, List<PaperFillRow>>, copies: List<CopyRow>, callsCount: Int, prefs: EgxPrefs, tr: Tr): LedgerView {
    val on = prefs.on
    fun total(record: String): Double {
        val latest = days[record].orEmpty().filter { it.strategy in on }.groupBy { it.strategy }.mapValues { it.value.maxBy { d -> d.day } }
        return if (latest.isEmpty()) prefs.money else latest.values.sumOf { it.equity }
    }
    val withTargets = total(Records.LEVELS)
    val byRule = total(Records.RULE)
    val y = yard.filter { it.strategy in on }.maxByOrNull { it.day }
    val started = days[Records.LEVELS].orEmpty().any { it.strategy in on }
    val since = prefs.liveStart?.let { dayText(EgxTime.date(it), tr) } ?: "–"
    val you = withTargets / prefs.money - 1
    val views = strategyViews(days[Records.LEVELS].orEmpty(), yard, fills[Records.LEVELS].orEmpty(), tr)
    val dividends = days[Records.LEVELS].orEmpty().filter { it.strategy in on }.groupBy { it.strategy }.values.sumOf { l -> l.maxBy { it.day }.dividends }
    return LedgerView(
        if (started) tr.t("Since %1\$s · on paper", since) else "",
        if (!started) tr.t(Tr.NOT_YET) else tr.t("The paper account made %1\$s since %2\$s. The EGX30 made %3\$s and cash %4\$s.", signed(you, tr), since, signed(y?.egx30?.takeIf { !it.isNaN() }?.minus(1), tr), signed(y?.cash?.minus(1), tr)),
        withTargets,
        listOf(BarRow(tr.t("You"), you, true), BarRow(tr.t("Cash"), y?.cash?.minus(1)), BarRow("EGX30", y?.egx30?.takeIf { !it.isNaN() }?.minus(1))),
        withTargets, byRule,
        if (byRule >= withTargets) tr.t("Selling at the targets has cost %1\$s so far.", tr.t("EGP %1\$s", tr.pounds(byRule - withTargets)))
        else tr.t("Selling at the targets has saved %1\$s so far.", tr.t("EGP %1\$s", tr.pounds(withTargets - byRule))),
        views.filter { it.strategy.id in on }, views.filter { it.strategy.id !in on },
        fills[Records.LEVELS].orEmpty().filter { it.strategy in on }.sumOf { it.cost },
        total(Records.LEVELS_DOUBLE), dividends, copies,
        tr.t("You copied %1\$d of %2\$d calls.", copies.size, callsCount), started,
    )
}

/** The month-end list (item 34): sells, buys and keeps of the evening a month ended, with the money they move. */
data class MonthEndView(val title: String, val sells: List<CallRow>, val buys: List<CallRow>, val keeps: List<CallRow>, val bring: Double, val need: Double, val ordersUsed: Int, val listOrders: Int)

fun monthEndView(calls: List<CallRow>, ordersUsed: Int, tr: Tr): MonthEndView {
    val month = calls.filter { it.monthEnd }
    val sells = month.filter { it.action == "sell" }
    val buys = month.filter { it.action == "buy" }
    val keeps = month.filter { it.action == "keep" }
    return MonthEndView(
        tr.t("Month-end list: sell %1\$d, buy %2\$d, keep %3\$d", sells.size, buys.size, keeps.size), sells, buys, keeps,
        sells.sumOf { it.pounds }, buys.sumOf { it.pounds }, ordersUsed, sells.size + buys.size,
    )
}

/** The round trip of a stock that is not among the 30 busiest, for the Settings page's line. */
val OTHERS_ROUND_TRIP: Double = Markets.EGX.roundTrip(null)
