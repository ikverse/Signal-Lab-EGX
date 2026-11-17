package com.ikverse.signallab.research

import com.ikverse.signallab.engine.Markets
import com.ikverse.signallab.engine.egx.Board
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.PickAccount
import com.ikverse.signallab.engine.egx.PickLearning
import com.ikverse.signallab.engine.egx.PickStats
import com.ikverse.signallab.engine.egx.TechnicalList
import com.ikverse.signallab.engine.egx.Breakout
import com.ikverse.signallab.engine.egx.CalmStocks
import com.ikverse.signallab.engine.egx.Context
import com.ikverse.signallab.engine.egx.Decision
import com.ikverse.signallab.engine.egx.DividendBook
import com.ikverse.signallab.engine.egx.DividendPayers
import com.ikverse.signallab.engine.egx.DividendRun
import com.ikverse.signallab.engine.egx.Growth
import com.ikverse.signallab.engine.egx.HoldBusiest
import com.ikverse.signallab.engine.egx.Holding
import com.ikverse.signallab.engine.egx.Levels
import com.ikverse.signallab.engine.egx.LowRiskFormula
import com.ikverse.signallab.engine.egx.SlotDay
import com.ikverse.signallab.engine.egx.SlotRule
import com.ikverse.signallab.engine.egx.Slots
import com.ikverse.signallab.engine.egx.Stock
import com.ikverse.signallab.engine.egx.Yardsticks
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * The whole-account test on the Egyptian Exchange (docs/egx-research.md): the engine's own Egyptian rules over the
 * research's data, re-making the results table of docs/egx-plan.md so the engine and the research can be compared.
 *
 *     python -I tools/egx/download.py <yahoo_symbols.json> research/data/egx/raw
 *     python -I tools/egx/prepare.py research/data/egx/raw research/data/egx/data_typical typical
 *     python -I tools/egx/export.py research/data/egx/data_typical <dbn2.json> research/data/egx/raw
 *     gradlew :research:run --args="egx research/data/egx/data_typical"
 */
private const val DAY = 86_400_000L
private const val COST = 0.005
private val YEARS = (2010..2026).toList()

private class Egx(val board: Board, val ctx: Context, val cash: DoubleArray, val index: DoubleArray, val start: Int)

private fun loadEgx(dir: File): Egx {
    fun rows(f: File) = f.readLines().filter { it.isNotBlank() }.map { it.split(',') }
    val idxRows = rows(File(dir, "d1/BTCUSDT.csv"))
    val calendar = LongArray(idxRows.size) { idxRows[it][0].toLong() }
    val n = calendar.size
    val pos = calendar.withIndex().associate { it.value to it.index }
    val stocks = sortedMapOf<String, Stock>()
    for (f in File(dir, "d1").listFiles().orEmpty().sortedBy { it.name }) {
        val code = f.name.removeSuffix(".csv")
        if (code == "BTCUSDT") continue
        val r = rows(f)
        fun nan() = DoubleArray(n) { Double.NaN }
        val o = nan(); val h = nan(); val l = nan(); val c = nan(); val q = DoubleArray(n); val v = DoubleArray(n)
        for (row in r) {
            val k = pos.getValue(row[0].toLong())
            o[k] = row[1].toDouble(); h[k] = row[2].toDouble(); l[k] = row[3].toDouble(); c[k] = row[4].toDouble(); v[k] = row[5].toDouble(); q[k] = row[7].toDouble()
        }
        stocks[code] = Stock(code, pos.getValue(r[0][0].toLong()), o, h, l, c, q, v)
    }
    val board = Board(calendar, stocks, Board.holidaysOf(calendar))
    val rates = rows(File(dir, "cash.csv")).associate { it[0] to it[1].toDouble() }
    val cash = DoubleArray(n) { k -> Markets.EGX.sessionRate(rates.getValue(board.date(k).toString().substring(0, 7))) }
    val dividends = rows(File(dir, "dividends.csv")).map { DividendBook.announcedBefore(board, it[0], it[1].toLong(), it[2].toDouble()) }
    val proxy = File(dir.parentFile, "proxy_equal6.json").takeIf { it.exists() }?.readText()
        ?.substringAfter("\"c\"")?.substringAfter('[')?.substringBefore(']')?.split(',')?.map { it.trim().toDouble() }
    val index = DoubleArray(n) { proxy?.getOrNull(it) ?: Double.NaN }
    val start = (0 until n).first { board.date(it).year >= 2010 }
    return Egx(board, Context(board, DividendBook(dividends)), cash, index, start)
}

/** Six-month momentum, monthly, out of the market when the index is under its 200-day average: the research's fourth family. */
private class Momentum(val gate: Boolean, val form: Int, val skip: Int, val top: Int, val index: DoubleArray) : SlotRule {
    override val id = "research_momentum"
    override val slots get() = top
    override val universeSize = 80
    private val avg200: DoubleArray = DoubleArray(index.size) { Double.NaN }.also { out ->
        var s = 0.0
        for (k in index.indices) {
            s += index[k]
            if (k >= 200) s -= index[k - 200]
            if (k >= 199) out[k] = s / 200
        }
    }

    override fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision {
        if (ctx.board.monthEnding(k) == null) return Decision.NONE
        if (gate && !(!avg200[k].isNaN() && index[k] > avg200[k])) return Decision(held.keys.toList(), emptyList())
        val scores = ArrayList<Pair<Double, String>>()
        for (code in ctx.board.universe(k, universeSize)) {
            val s = ctx.board.stocks.getValue(code)
            if (k - form - skip < s.first) continue
            val a = s.close[k - form - skip]
            val b = s.close[k - skip]
            if (a > 0 && b > 0) scores.add(b / a - 1 to code)
        }
        return Decision.toList(held, scores.sortedWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second }).take(top).map { it.second })
    }
}

/** The research's own fill rule ([wait] false) or the engine's, where a blocked buy waits (item 5). */
private var wait = false

private fun Egx.go(rule: SlotRule, levels: Levels = Levels.NONE): List<SlotDay> =
    Slots.run(rule, ctx, start, { _, _ -> COST }, cash, levels, buysWait = wait).days

/** What 1.00 became in each year of [YEARS]. */
private fun Egx.yearly(days: List<SlotDay>): List<Double> = YEARS.map { y ->
    val ks = days.filter { board.date(it.k).year == y }
    if (ks.isEmpty()) 1.0 else ks.last().equity / (days.lastOrNull { it.k < ks.first().k }?.equity ?: 1.0)
}

private fun span(ys: List<Double>, a: Int, b: Int): Double = ys.subList(YEARS.indexOf(a), YEARS.indexOf(b) + 1).fold(1.0) { x, y -> x * y }
private fun prod(ys: List<Double>): Double = ys.fold(1.0) { x, y -> x * y }

private fun Egx.between(days: List<SlotDay>, a: Int, b: Int): Growth =
    Growth.of(days, (0 until board.size).first { board.date(it).year >= a }, (board.size - 1 downTo 0).first { board.date(it).year <= b })!!

private class Line(val label: String, val old: Double, val recent: Double, val resOld: Double?, val resRecent: Double?)

fun egxMain(dir: File, buysWait: Boolean = false) {
    wait = buysWait
    val e = loadEgx(dir)
    println(if (buysWait) "Blocked buys wait for the stock to trade (the engine as registered)" else "Blocked buys are dropped (the research's fill rule)")
    println("${e.board.stocks.size} stocks, ${e.board.size} sessions from ${e.board.date(0)} to ${e.board.date(e.board.size - 1)}; costs 0.5% a round trip")
    val lines = ArrayList<Line>()
    val cashY = e.yearly(Yardsticks.cash(e.board, e.start, e.cash))
    lines.add(Line("Cash", span(cashY, 2015, 2022), span(cashY, 2023, 2026), 2.41, 1.98))
    val market = e.go(HoldBusiest())
    val marketY = e.yearly(market)
    println("  Holding the 30 busiest, at each year's end: " + YEARS.joinToString(" ") { y ->
        String.format(Locale.US, "%d:%.6f", y, market.last { e.board.date(it.k).year == y }.equity)
    })
    lines.add(Line("Holding the 30 busiest stocks", span(marketY, 2015, 2022), span(marketY, 2023, 2026), 0.99, 3.76))

    val families = linkedMapOf<String, List<Pair<String, SlotRule>>>(
        "Calm stocks" to buildList {
            for (t in listOf(5, 8, 12, 20)) for (w in listOf(20, 60, 120, 250)) for (tr in listOf(0, 100, 200)) for (u in listOf(40, 80)) {
                add("($t, $w, $tr, top$u)" to CalmStocks(t, u, tr, w))
            }
        },
        "Breakout" to buildList {
            for (en in listOf(20, 50, 100, 150, 250)) for (lim in listOf(60, null)) for (u in listOf(40, 80)) {
                add("($en, $lim, top$u)" to Breakout(en, (en * 0.4).toInt(), lim, u, chase = true, id = "research_breakout"))
            }
        },
        "Dividend payers" to buildList {
            for (t in listOf(5, 8, 12, 15)) for (u in listOf(40, 80)) for (up in listOf(false, true)) add("($t, top$u, $up)" to DividendPayers(t, u, up))
        },
        "Momentum" to buildList {
            for (f in listOf(63, 126, 250)) for (t in listOf(5, 8, 12)) for (g in listOf(true, false)) add("($f, $t, $g)" to Momentum(g, f, if (f > 63) 21 else 5, t, e.index))
        },
    )
    val research = mapOf("Calm stocks" to (3.63 to 5.63), "Breakout" to (10.95 to 2.33), "Dividend payers" to (9.21 to 3.79), "Momentum" to (2.77 to 1.89))
    val wf = LinkedHashMap<String, List<Double>>()
    for ((fam, settings) in families) {
        val table = settings.map { (key, rule) -> key to e.yearly(e.go(rule)) }
        val ys = ArrayList(cashY.subList(0, 5))
        var lastPick: String? = null
        val picked = ArrayList<String>()
        for ((i, y) in YEARS.withIndex()) {
            if (y < 2015) continue
            val best = table.maxByOrNull { prod(it.second.subList(0, i)) }!!
            ys.add(best.second[i] * (if (lastPick != null && lastPick != best.first) 0.995 else 1.0))
            lastPick = best.first
            picked.add("$y:${best.first}")
        }
        wf[fam] = ys
        val (ro, rr) = research.getValue(fam)
        lines.add(Line("$fam, settings chosen from the past only (${table.size})", span(ys, 2015, 2022), span(ys, 2023, 2026), ro, rr))
        println("  $fam picked ${picked.filter { it.startsWith("2015") || it.startsWith("2019") || it.startsWith("2023") || it.startsWith("2026") }}")
    }
    val three = listOf("Calm stocks", "Breakout", "Dividend payers")
    val split = YEARS.indices.map { i -> three.sumOf { wf.getValue(it)[i] } / 3 }
    lines.add(Line("Equal split of calm stocks, dividend payers and breakout", span(split, 2015, 2022), span(split, 2023, 2026), 7.94, 3.93))
    val lead = ArrayList(cashY.subList(0, 5))
    for ((i, y) in YEARS.withIndex()) {
        if (y < 2015) continue
        val cands = LinkedHashMap(wf).also { it["Cash"] = cashY }
        lead.add(cands.values.maxByOrNull { prod(it.subList(maxOf(0, i - 3), i)) }!![i])
    }
    lines.add(Line("Switching to whichever rule led the last three years", span(lead, 2015, 2022), span(lead, 2023, 2026), 2.51, 1.44))
    for ((top, ro, rr) in listOf(Triple(8, 3.78, 5.15), Triple(10, 3.00, 5.49), Triple(15, 2.27, 5.14))) {
        val ys = e.yearly(e.go(LowRiskFormula(top)))
        lines.add(Line("Low-risk formula, $top stocks", span(ys, 2015, 2022), span(ys, 2023, 2026), ro, rr))
    }
    val run = e.go(DividendRun())
    lines.add(Line("Dividend run (from the assembly, 4%+)", e.between(run, 2015, 2022).multiple, e.between(run, 2023, 2026).multiple, 3.02, 2.99))

    println("\nThe levels test (what 1.00 became; research figures from levels.py)")
    val levelRules = listOf(
        Triple("Calm stocks", CalmStocks(), listOf(5.12, 5.95, 2.78, 4.34)),
        Triple("Dividend payers", DividendPayers(), listOf(7.06, 3.59, 3.60, 2.74)),
        Triple("Dividend run", DividendRun(), listOf(3.02, 2.99, 2.96, 2.85)),
        Triple("Slow breakout (as tested: chases jumps)", Breakout(100, 40, null, 80, chase = true, id = "research_slow"), listOf(5.68, 4.92, 3.44, 3.68)),
    )
    for ((name, rule, r) in levelRules) {
        val plain = e.go(rule)
        val lv = e.go(rule, Levels.PLAN)
        lines.add(Line("$name, by the rule alone", e.between(plain, 2015, 2022).multiple, e.between(plain, 2023, 2026).multiple, r[0], r[1]))
        lines.add(Line("$name, range, stop 4, targets 5 and 10", e.between(lv, 2015, 2022).multiple, e.between(lv, 2023, 2026).multiple, r[2], r[3]))
    }
    val slow = e.go(Breakout(100, 40, null, 80, chase = false, id = "egx_slow_breakout"))
    // The research's 4.75 for this version came from the third pass, before idle money earned interest: no like-for-like figure.
    lines.add(Line("Slow breakout as registered (never the day after an 8% jump)", e.between(slow, 2015, 2022).multiple, e.between(slow, 2023, 2026).multiple, null, null))

    println(String.format(Locale.US, "\n%-64s %8s %8s   %8s %8s   %s", "", "2015-22", "2023-26", "research", "", "largest gap"))
    var worst = 0.0
    for (l in lines) {
        val gaps = listOfNotNull(l.resOld?.let { l.old / it - 1 }, l.resRecent?.let { l.recent / it - 1 })
        val gap = gaps.maxByOrNull { abs(it) } ?: 0.0
        worst = maxOf(worst, abs(gap))
        println(String.format(Locale.US, "%-64s %7.2fx %7.2fx   %8s %8s   %+5.1f%%%s", l.label, l.old, l.recent,
            l.resOld?.let { String.format(Locale.US, "%.2fx", it) } ?: "", l.resRecent?.let { String.format(Locale.US, "%.2fx", it) } ?: "",
            100 * gap, if (abs(gap) > 0.2) "  MORE THAN A FIFTH" else ""))
    }
    println(String.format(Locale.US, "\nLargest gap from the research: %.1f%%%s", 100 * worst, if (worst > 0.2) " (more than a fifth: stop and tell the owner)" else " (within a fifth)"))
}

/**
 * The technical picks (docs/egx-research.md section 4) as the engine follows them, against `technical.py`'s figures:
 * the registered version of each list with its levels and without, and the monthly learning step from 2015 on.
 *
 *     gradlew :research:run --args="egx technical research/data/egx/data_typical"
 */
fun egxTechnical(dir: File) {
    val e = loadEgx(dir)
    val cost: (String, Int) -> Double = { code, k -> Markets.EGX.roundTrip(e.board.rank(k, code)) }
    fun show(label: String, days: List<SlotDay>, extra: String = "") {
        val ys = e.yearly(days)
        println(String.format(Locale.ROOT, "%-58s %6.2fx %6.2fx  %s", label, span(ys, 2015, 2022), span(ys, 2023, 2026), extra))
    }
    fun stats(s: PickStats?) = if (s == null) "" else String.format(Locale.ROOT, "win %3.0f%%  T1 %3.0f%%  stop %3.0f%%  avg %+5.1f%%  %dd",
        100 * s.won, 100 * s.target1, 100 * s.stopped, 100 * s.average, s.days)
    println(String.format(Locale.ROOT, "%-58s %7s %7s", "", "2015-22", "2023-26"))
    show("Cash", Yardsticks.cash(e.board, e.start, e.cash))
    val research = mapOf(EgxStrategies.shortTerm to "research 5.59, 4.94; rule alone 4.67, 2.90", EgxStrategies.midTerm to "research 5.62, 3.93; rule alone 7.37, 2.90")
    for (list in EgxStrategies.technical) {
        val v = list.registered
        println("${list.name}: ${v.id}   (${research[list]})")
        val main = PickAccount.run({ v }, e.ctx, e.start, cost, e.cash)
        show("  with its levels", main.days, stats(PickStats.of(main.trades)))
        show("  by the rule alone", PickAccount.run({ v }, e.ctx, e.start, cost, e.cash, useRange = false, useStop = false, useTargets = false).days)
        val twins = (0 until 10).map { e.yearly(PickAccount.run({ v }, e.ctx, e.start, cost, e.cash, twin = it).days) }
        for ((a, b) in listOf(2015 to 2022, 2023 to 2026)) {
            val m = twins.map { span(it, a, b) }.sorted()
            println(String.format(Locale.ROOT, "  random stocks %d-%d: low %.2fx middle %.2fx high %.2fx", a, b, m.first(), m[m.size / 2], m.last()))
        }
        learning(e, list, cost)
    }
}

private fun learning(e: Egx, list: TechnicalList, cost: (String, Int) -> Double) {
    val from = (0 until e.board.size).first { e.board.date(it).year >= 2015 }
    val choices = PickLearning.choices(list.versions, e.ctx, from, e.start, cost, e.cash)
    val run = PickAccount.run(PickLearning.setupAt(e.board, choices, list.registered), e.ctx, e.start, cost, e.cash)
    val ys = e.yearly(run.days)
    println(String.format(Locale.ROOT, "  %-56s %6.2fx %6.2fx", "monthly learning step from 2015", span(ys, 2015, 2022), span(ys, 2023, 2026)))
    println("    chosen: " + choices.filter { it.month.monthValue == 1 && it.month.year % 4 == 3 }.joinToString { "${it.month}: ${it.setup.id}" })
}
