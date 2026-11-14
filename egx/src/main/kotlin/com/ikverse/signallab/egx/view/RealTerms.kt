package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.data.Macro
import com.ikverse.signallab.egx.i18n.Tr

/** What the Ledger's figures are counted in. */
enum class Measure { POUNDS, DOLLARS, GOLD }

/** The Ledger in real terms, ready to draw. */
data class RealTermsView(
    /** The account's yardstick bars counted in each measure: you, cash, the EGX30, held dollars, held gold. */
    val bars: Map<Measure, List<BarRow>>,
    /** What the account became in pounds, in dollars and in grams of gold since it started. */
    val became: List<Pair<String, String>>,
    val note: String,
    /** False when the dollar is not known for the ledger's first day yet. */
    val known: Boolean,
)

/**
 * The paper account re-counted in dollars and in gold. The stored ledger is in pounds and never changes; this is done on
 * display. A growth share g in pounds between [start] and [end] is, in a measure that costs m pounds a unit,
 * (1 + g) · m(start) / m(end) − 1. Held dollars and held gold are what a pound kept in them became, counted the same way.
 */
fun realTerms(
    start: Long?,
    end: Long?,
    money: Double,
    account: Double,
    cash: Double?,
    egx30: Double?,
    usd: List<Pair<Long, Double>>,
    gold: List<Pair<Long, Double>>,
    tr: Tr,
): RealTermsView {
    val u0 = start?.let { Macro.at(usd, it) }
    val u1 = end?.let { Macro.at(usd, it) }
    val g0 = start?.let { Macro.at(gold, it) }
    val g1 = end?.let { Macro.at(gold, it) }
    /** Pounds a unit of [m] cost at the start and at the end. */
    fun price(m: Measure): Pair<Double, Double>? = when (m) {
        Measure.POUNDS -> 1.0 to 1.0
        Measure.DOLLARS -> if (u0 != null && u1 != null) u0 to u1 else null
        Measure.GOLD -> if (u0 != null && u1 != null && g0 != null && g1 != null) u0 * g0 / Macro.GRAMS_PER_OUNCE to u1 * g1 / Macro.GRAMS_PER_OUNCE else null
    }
    val you = account / money - 1
    val heldUsd = price(Measure.DOLLARS)?.let { (a, b) -> b / a - 1 }
    val heldGold = price(Measure.GOLD)?.let { (a, b) -> b / a - 1 }
    val bars = Measure.entries.associateWith { m ->
        val p = price(m)
        fun inM(g: Double?): Double? = if (g == null || p == null) null else (1 + g) * p.first / p.second - 1
        listOf(
            BarRow(tr.t("You"), inM(you), true),
            BarRow(tr.t("Cash"), inM(cash)),
            BarRow("EGX30", inM(egx30)),
            BarRow(tr.t("Held dollars"), inM(heldUsd)),
            BarRow(tr.t("Held gold"), inM(heldGold)),
        )
    }
    val became = buildList {
        add(tr.t("In pounds") to tr.t("EGP %1\$s → %2\$s", tr.pounds(money), tr.pounds(account)))
        price(Measure.DOLLARS)?.let { (a, b) -> add(tr.t("In dollars") to tr.t("$%1\$s → $%2\$s", tr.pounds(money / a), tr.pounds(account / b))) }
        price(Measure.GOLD)?.let { (a, b) -> add(tr.t("In gold") to tr.t("%1\$s g → %2\$s g", tr.pounds(money / a), tr.pounds(account / b))) }
    }
    val lastUsd = usd.lastOrNull()?.first
    val lastGold = gold.lastOrNull()?.first
    val note = listOfNotNull(
        tr.t("The dollar is Yahoo's official rate, not the parallel market's. Gold is the front gold future in dollars."),
        if (lastUsd != null && end != null && lastUsd < end) tr.t("The dollar is known up to %1\$s.", dayYear(lastUsd, tr)) else null,
        if (lastGold != null && end != null && lastGold < end) tr.t("Gold is known up to %1\$s.", dayYear(lastGold, tr)) else null,
    ).joinToString(" ")
    return RealTermsView(bars, became, note, u0 != null && u1 != null)
}
