package com.ikverse.signallab.egx.data

/**
 * The two daily series the Ledger uses to show the paper account in real terms: the dollar in pounds (Yahoo's `EGP=X`,
 * the official rate, not the parallel market's) and gold in dollars an ounce (`GC=F`, the front gold future).
 */
object Macro {
    const val USD = "EGP=X"
    const val GOLD = "GC=F"
    val ALL = listOf(USD, GOLD)

    const val GRAMS_PER_OUNCE = 31.1034768

    /** The first download reaches this far back; later ones re-read the last [OVERLAP_DAYS]. */
    const val FIRST_YEARS = 4L
    const val OVERLAP_DAYS = 10L

    /** One close per calendar day, as the chart's own time zone dates it; bars with no close are dropped. */
    fun closes(chart: YahooChart): List<Pair<Long, Double>> =
        chart.bars.filter { !it.close.isNaN() && it.close > 0 }.associate { chart.dayOf(it) to it.close }.toList().sortedBy { it.first }

    /** The last close of [series] (oldest first) on or before [day]; null when the series starts later. */
    fun at(series: List<Pair<Long, Double>>, day: Long): Double? {
        var lo = 0
        var hi = series.size - 1
        var best: Double? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].first <= day) { best = series[mid].second; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}
