package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.FactsRow
import com.ikverse.signallab.egx.ledger.HeadlineRow
import com.ikverse.signallab.egx.ledger.MarketDayRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PickChoiceRow
import com.ikverse.signallab.egx.ledger.PickEventRow
import com.ikverse.signallab.egx.ledger.PickRecordRow
import com.ikverse.signallab.egx.ledger.PickRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.MidTermSetup
import com.ikverse.signallab.engine.egx.PickFamily
import com.ikverse.signallab.engine.egx.ShortTermSetup
import com.ikverse.signallab.engine.egx.TechnicalList
import java.util.Locale
import kotlin.math.floor

/** One level of a pick card: its name, price, and how far it is from the top of the entry range. */
data class PickLevel(val name: String, val price: String, val fromEntry: String, val kind: ChartLevel.Kind)

/** A pick's card, and the page it opens, ready to draw. */
data class PickCardView(
    val pick: PickRow,
    val name: String,
    val listName: String,
    /** "COMI · Short-term picks · 16 November". */
    val line: String,
    val levels: List<PickLevel>,
    /** What leaves a pick with no targets ("No targets: ..."), or how the rest leaves after target 1. */
    val exitNote: String,
    val shares: String,
    val why: String,
    val record: List<String>,
    val facts: List<Pair<String, String>>,
    val headlines: List<Pair<String, String>>,
    val flags: List<String>,
    /** Where it stands: waiting to be bought, held, or how it ended. */
    val status: String,
    val open: Boolean,
    val shareText: String,
)

/** One list on Today: its name and state, and the evening's picks. */
data class PickListView(val list: TechnicalList, val name: String, val state: String, val cards: List<PickCardView>, val shows: Boolean)

/** The technical picks on Today. */
data class PicksView(
    val market: String?,
    val lists: List<PickListView>,
    /** Picks still waiting or held, newest first. */
    val following: List<PickCardView>,
    /** The day of the evening's picks, or null before the first. */
    val day: Long?,
)

/** What [EgxStrategies.technical]'s lists are measured by on the Learn page: the engine's re-run of the research. */
object PickEvidence {
    /** What 1.00 became in 2015-22 and 2023-26: the registered version with its levels, by the rule alone, chosen month by month, random stocks. */
    class Research(
        val old: Double, val recent: Double, val ruleOld: Double, val ruleRecent: Double, val learnedOld: Double, val learnedRecent: Double,
        val randomOld: Double, val randomRecent: Double, val won: Double, val target1: Double?, val stopped: Double, val average: Double, val days: Int,
    )

    val BY_ID: Map<String, Research> = mapOf(
        EgxStrategies.shortTerm.id to Research(5.59, 4.94, 4.67, 2.90, 2.83, 3.77, 1.41, 2.23, 0.51, 0.13, 0.21, 0.027, 21),
        EgxStrategies.midTerm.id to Research(5.89, 3.93, 7.18, 2.90, 5.78, 3.50, 1.58, 3.23, 0.33, null, 0.26, 0.028, 7),
    )
    const val CASH_OLD = 2.41
    const val CASH_RECENT = 1.98
    const val INDEX_OLD = 0.83
    const val INDEX_RECENT = 3.60
}

private fun locale(tr: Tr): Locale = if (tr.isArabic) Locale.forLanguageTag("ar-EG") else Locale.ENGLISH

private fun two(x: Double, tr: Tr): String = tr.digits(String.format(Locale.US, "%.2f", x))

/** A pick's distance from the top of its entry range, signed. */
private fun from(entry: Double, price: Double, tr: Tr): String = tr.ltr(signed1(price / entry - 1, tr))

private fun signed1(x: Double, tr: Tr): String = (if (x >= 0) "+" else "−") + tr.percent(kotlin.math.abs(x), 1)

/** A big number of pounds: "EGP 61.0 bn", "EGP 4.6 m". */
fun bigPounds(x: Double, tr: Tr): String {
    val a = kotlin.math.abs(x)
    val sign = if (x < 0) "−" else ""
    return when {
        a >= 1e9 -> tr.t("EGP %1\$s bn", sign + tr.digits(String.format(Locale.US, "%.1f", a / 1e9)))
        a >= 1e6 -> tr.t("EGP %1\$s m", sign + tr.digits(String.format(Locale.US, "%.1f", a / 1e6)))
        else -> tr.t("EGP %1\$s", sign + tr.pounds(a))
    }
}

/** The list's name in the app's language. */
fun listName(list: TechnicalList, tr: Tr): String = tr.t(list.name)

/** A setup version in words, for the Learn page. */
fun versionText(id: String, tr: Tr): String {
    val v = EgxStrategies.technical.flatMap { it.versions }.firstOrNull { it.id == id } ?: return id
    return when (v) {
        is ShortTermSetup -> tr.t("A new %1\$d-day high on at least %2\$s times its average volume, held up to %3\$d sessions", v.enter, tr.digits(String.format(Locale.US, "%.1f", v.volume)), v.limit)
        is MidTermSetup -> when {
            v.template && !v.tenth -> tr.t("Trend template, tightest fifth, held up to %1\$d sessions", v.limit)
            v.template -> tr.t("Trend template, tightest tenth, held up to %1\$d sessions", v.limit)
            !v.tenth -> tr.t("Plain uptrend, tightest fifth, held up to %1\$d sessions", v.limit)
            else -> tr.t("Plain uptrend, tightest tenth, held up to %1\$d sessions", v.limit)
        }
        else -> id
    }
}

/** "Finished trades: 41 · won 51% · reached target 1 13% · stopped 21% · average +2.7% · 21 sessions". */
private fun recordLine(r: PickRecordRow, family: PickFamily, tr: Tr): String =
    if (family == PickFamily.SHORT) tr.t("%1\$d finished trades · won %2\$s · reached target 1 %3\$s · stopped %4\$s · average %5\$s · %6\$d sessions held",
        r.trades, tr.percent(r.won, 0), tr.percent(r.target1, 0), tr.percent(r.stopped, 0), tr.ltr(signed1(r.average, tr)), r.days)
    else tr.t("%1\$d finished trades · won %2\$s · stopped %3\$s · average %4\$s · %5\$d sessions held",
        r.trades, tr.percent(r.won, 0), tr.percent(r.stopped, 0), tr.ltr(signed1(r.average, tr)), r.days)

/** How a pick stands, from its events and the last close. */
private fun status(p: PickRow, events: List<PickEventRow>, close: Double?, tr: Tr): Pair<String, Boolean> {
    val mine = events.filter { it.list == p.list && it.code == p.code && it.pickDay == p.day }.sortedBy { it.day }
    val bought = mine.firstOrNull { it.kind == "bought" }
    val end = mine.lastOrNull { it.kind in setOf("target2", "stop", "time", "rule", "delisted", "expired") }
    val half = mine.any { it.kind == "target1" }
    if (end != null) {
        val text = when (end.kind) {
            "expired" -> tr.t("Not bought: the price never came back into the entry range")
            "target2" -> tr.t("Ended at target 2 (%1\$s)", tr.price(end.price))
            "stop" -> tr.t("Ended at the stop loss: sold at %1\$s", tr.price(end.price))
            "time" -> tr.t("Ended at its time limit: sold at %1\$s", tr.price(end.price))
            "rule" -> tr.t("Ended under its 50-day average: sold at %1\$s", tr.price(end.price))
            else -> tr.t("Ended: the stock left the data")
        }
        val result = bought?.let { b -> " · " + tr.ltr(signed1(end.price / b.price - 1, tr)) }.takeIf { end.kind != "expired" } ?: ""
        return text + result to false
    }
    if (bought == null) return tr.t("Waiting to be bought, three sessions at most") to true
    val since = close?.let { " · " + tr.t("%1\$s since", tr.ltr(signed1(it / bought.price - 1, tr))) } ?: ""
    return (if (half) tr.t("Half sold at target 1; the rest held from %1\$s", tr.price(bought.price)) else tr.t("Bought at %1\$s", tr.price(bought.price))) + since to true
}

/** One pick's card and page. */
fun pickCard(
    p: PickRow, events: List<PickEventRow>, records: List<PickRecordRow>, facts: FactsRow?, headlines: List<HeadlineRow>, close: Double?,
    prefs: EgxPrefs, tr: Tr, name: String,
): PickCardView {
    val list = EgxStrategies.technicalOf(p.list) ?: EgxStrategies.shortTerm
    val m = Markets.EGX
    val lo = m.round(p.entryLow)
    val hi = m.round(p.entryHigh)
    val levels = buildList {
        add(PickLevel(tr.t("Entry range"), "${tr.price(lo)} – ${tr.price(hi)}", from(hi, lo, tr), ChartLevel.Kind.ENTRY))
        p.target1?.let { add(PickLevel(tr.t("Target 1"), tr.price(m.round(it)), from(hi, it, tr), ChartLevel.Kind.TARGET)) }
        p.target2?.let { add(PickLevel(tr.t("Target 2"), tr.price(m.round(it)), from(hi, it, tr), ChartLevel.Kind.TARGET)) }
        add(PickLevel(tr.t("Stop loss"), tr.price(m.round(p.stop)), from(hi, p.stop, tr), ChartLevel.Kind.STOP))
    }
    val exitNote = if (p.target1 == null) {
        if (p.exitAverage != null) tr.t("No targets: hold up to %1\$d sessions; sell at the next open after a close under the stop loss or under the %2\$d-day average.", p.limit, p.exitAverage)
        else tr.t("No targets: hold up to %1\$d sessions; sell at the next open after a close under the stop loss.", p.limit)
    } else tr.t("Sell half at target 1 and the rest at target 2. Sell at the next open after a close under the stop loss, or after %1\$d sessions.", p.limit)
    val shares = floor(prefs.pickAmount / hi).toLong()
    val sharesText = tr.t("%1\$s shares for EGP %2\$s", tr.count(shares), tr.pounds(prefs.pickAmount))
    val why = when (list.family) {
        PickFamily.SHORT -> tr.t("Closed above its high of the last weeks (%1\$s) on %2\$s times its average volume, in an uptrend, at %3\$s of its high of the last year.",
            p.breakout?.let { tr.price(m.round(it)) } ?: "–", tr.digits(String.format(Locale.US, "%.1f", p.volumeRatio)), tr.percent(p.near, 0))
        PickFamily.MID -> tr.t("Its last 10 sessions moved within %1\$s, among its quietest stretches of the last 120 sessions, inside a strong uptrend at %2\$s of its high of the last year.",
            tr.percent(p.tightness, 1), tr.percent(p.near, 0))
    }
    val record = buildList {
        records.firstOrNull { it.list == p.list && it.scope == "backtest" }?.let { add(tr.t("Backtest since 2010") + ": " + recordLine(it, list.family, tr)) }
        val live = records.firstOrNull { it.list == p.list && it.scope == "live" }
        add(if (live == null) tr.t("Live: no finished trades yet.") else tr.t("Live") + ": " + recordLine(live, list.family, tr))
    }
    val factLines = listOf(
        tr.t("Price to earnings") to (facts?.peRatio?.let { two(it, tr) } ?: "–"),
        (if ((facts?.netIncome ?: 0.0) < 0) tr.t("Loss, last 12 months") else tr.t("Profit, last 12 months")) to (facts?.netIncome?.let { bigPounds(it, tr) } ?: "–"),
        tr.t("Debt to equity") to (facts?.debtToEquity?.let { two(it, tr) } ?: "–"),
    )
    val heads = headlines.filter { it.code == p.code }.sortedByDescending { it.published ?: 0 }.take(3).map { h ->
        h.headline to (h.published?.let { dayText(EgxTime.date(it), tr) } ?: "")
    }
    val flags = buildList {
        if (p.hadNews) add(tr.t("News in the last 10 days"))
        for (f in p.flags.split(',').filter { it.isNotEmpty() }) when (f) {
            "results" -> add(tr.t("Results"))
            "assembly" -> add(tr.t("General assembly"))
            "capital_increase" -> add(tr.t("Capital increase"))
            "dividend" -> add(tr.t("Dividend"))
        }
        if (p.lossMaking == true) add(tr.t("Made a loss"))
    }
    val (st, open) = status(p, events, close, tr)
    val dayLine = dayText(EgxTime.date(p.day), tr)
    val share = buildString {
        append("${p.code} · $name\n")
        append(tr.t("%1\$s, made after the close of %2\$s", listName(list, tr), dayLine)).append('\n')
        for (l in levels) append("${l.name}: ${l.price} (${l.fromEntry})\n")
        append(exitNote).append('\n')
        append(tr.t("Signal Lab EGX: a paper pick from chart rules, not financial advice."))
    }
    return PickCardView(p, name, listName(list, tr), "${p.code} · ${listName(list, tr)} · $dayLine", levels, exitNote, sharesText, why, record,
        factLines, heads, flags, st, open, share)
}

/** The market line: the EGX30 against its averages, and how many of the busiest stocks are in an uptrend. */
fun marketLine(m: MarketDayRow?, tr: Tr): String? {
    if (m == null) return null
    val index = when {
        m.above50 == true && m.above200 == true -> tr.t("EGX30 above its 50 and 200-day averages")
        m.above50 == false && m.above200 == false -> tr.t("EGX30 below its 50 and 200-day averages")
        m.above200 == true -> tr.t("EGX30 above its 200-day average, below its 50-day")
        m.above200 == false -> tr.t("EGX30 below its 200-day average, above its 50-day")
        else -> null
    }
    val breadth = if (m.counted > 0) tr.t("%1\$d of %2\$d busiest stocks in an uptrend", m.uptrend, m.counted) else null
    return listOfNotNull(index, breadth).joinToString(" · ").ifEmpty { null }
}

/** The technical picks on Today: the market line, each list's latest picks, and the picks still open. */
fun picksView(
    picks: List<PickRow>, events: List<PickEventRow>, records: List<PickRecordRow>, market: MarketDayRow?, facts: Map<String, FactsRow>,
    headlines: List<HeadlineRow>, closes: Map<String, Double>, prefs: EgxPrefs, tr: Tr, names: (String) -> String,
): PicksView {
    val day = picks.filter { it.shown }.maxOfOrNull { it.day } ?: picks.maxOfOrNull { it.day }
    fun card(p: PickRow) = pickCard(p, events, records, facts[p.code], headlines, closes[p.code], prefs, tr, names(p.code))
    val lists = EgxStrategies.technical.map { l ->
        val on = l.id in prefs.picksOn
        // A list that is on but whose latest picks were recorded without being shown was switched off by its record.
        val latest = picks.filter { it.list == l.id }.maxOfOrNull { it.day }
        val switchedOff = on && latest != null && picks.none { it.list == l.id && it.day == latest && it.shown }
        val state = when {
            !on -> tr.t("Off in Settings")
            switchedOff -> tr.t("Switched off by its record: picks are recorded, not shown")
            else -> tr.t("Testing · on paper")
        }
        val today = if (day == null) emptyList() else picks.filter { it.list == l.id && it.day == day && it.shown }.sortedBy { it.rank }.map(::card)
        PickListView(l, listName(l, tr), state, today, on && !switchedOff)
    }
    val following = picks.filter { it.shown && it.day != day }.map(::card).filter { it.open }
    return PicksView(marketLine(market, tr), lists, following, day)
}

/** A list on the Learn page. */
data class PickEvidenceView(
    val list: TechnicalList,
    val name: String,
    val summary: String,
    val how: List<String>,
    val bars: List<BarRow>,
    val research: PickEvidence.Research?,
    val records: List<String>,
    val version: String,
    val choices: List<Pair<String, String>>,
)

/** Each list explained, with its research, its record and the learning step's choices. */
fun pickEvidence(days: List<PaperDayRow>, yard: List<YardstickRow>, records: List<PickRecordRow>, choices: List<PickChoiceRow>, tr: Tr): List<PickEvidenceView> =
    EgxStrategies.technical.map { l ->
        val mine = days.filter { it.strategy == l.id && it.record == Records.PICKS }.sortedBy { it.day }
        val y = yard.filter { it.strategy == l.id }.maxByOrNull { it.day }
        val growth = if (mine.isNotEmpty()) mine.last().equity / mine.first().equity - 1 else null
        val how = when (l.family) {
            PickFamily.SHORT -> listOf(
                tr.t("Each evening it looks for stocks among the 80 busiest that closed above their highest high of the last 20 sessions, on at least their average volume, while above their 50 and 200-day averages. Never the day after a jump of 8%."),
                tr.t("Entry range: from the breakout level, or half a normal day's move under the close, up to the close. The order stands three sessions."),
                tr.t("Stop loss: just under the lowest low of the last 10 sessions, between 1.5 and 4 normal moves under the close. Target 1: the first earlier peak at least twice the risk away; target 2: the next one at least one risk further."),
            )
            PickFamily.MID -> listOf(
                tr.t("Each evening it looks for stocks among the 80 busiest in a strong uptrend (above their 50, 150 and 200-day averages, in that order, the 200-day rising) whose last 10 sessions were among their quietest of the last 120."),
                tr.t("Entry range: half a normal day's move under the close up to the close. Stop loss: just under the lowest low of the last 10 sessions."),
                tr.t("No targets: the research found they sold the winners too early. A pick is held until a close under the stop or the 50-day average, or for 120 sessions."),
            )
        }
        val recs = buildList {
            records.firstOrNull { it.list == l.id && it.scope == "backtest" }?.let { add(tr.t("Backtest since 2010") + ": " + recordLine(it, l.family, tr)) }
            val live = records.firstOrNull { it.list == l.id && it.scope == "live" }
            add(if (live == null) tr.t("Live: no finished trades yet.") else tr.t("Live") + ": " + recordLine(live, l.family, tr))
        }
        val mineChoices = choices.filter { it.list == l.id }.sortedBy { it.month }
        PickEvidenceView(
            l, listName(l, tr), tr.t(l.summary), how,
            listOf(
                BarRow(tr.t("This list"), growth, mine = true),
                BarRow(tr.t("Cash"), y?.cash?.minus(1)),
                BarRow("EGX30", y?.egx30?.takeIf { !it.isNaN() }?.minus(1)),
                BarRow(tr.t("Random stocks"), y?.randomMiddle?.takeIf { !it.isNaN() }?.minus(1)),
            ),
            PickEvidence.BY_ID[l.id], recs,
            versionText(mineChoices.lastOrNull()?.version ?: l.registered.id, tr),
            mineChoices.takeLast(6).reversed().map { c -> tr.digits(c.month) to versionText(c.version, tr) },
        )
    }
