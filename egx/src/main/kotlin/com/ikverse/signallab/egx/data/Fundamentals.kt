package com.ikverse.signallab.egx.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** A company's facts at one date: what the pick card shows beside the chart. Null where the source has none. */
data class StockFacts(
    val code: String,
    /** When the facts were read. */
    val fetched: Long,
    val peRatio: Double?,
    /** Net income over the last twelve months, in pounds: below zero is a loss. */
    val netIncome: Double?,
    val eps: Double?,
    val totalDebt: Double?,
    val debtToEquity: Double?,
) {
    val lossMaking: Boolean? get() = netIncome?.let { it < 0 }
}

/**
 * Reads stockanalysis.com's statistics page of an EGX stock (server-rendered HTML). Each figure is a table row: the
 * label in the first cell, the value in the second, whose `title` holds the exact number ("1,028,174,764") and whose
 * text the short one ("1.03B"). "n/a" is no value. The page's prose repeats some labels ("a Debt / Equity ratio of
 * 0.14"), so only table rows are read.
 */
object StockAnalysisParser {
    private val ROW = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("""<td\b([^>]*)>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TITLE = Regex("""title\s*=\s*"([^"]*)"""")
    private val TAG = Regex("<[^>]*>|<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val SPACE = Regex("\\s+")

    const val PE = "PE Ratio"
    const val NET_INCOME = "Net Income"
    const val EPS = "Earnings Per Share (EPS)"
    const val DEBT = "Total Debt"
    const val DEBT_EQUITY = "Debt / Equity"

    /** Label to value of every table row whose value reads as a number. */
    fun rows(html: String): Map<String, Double?> {
        val out = LinkedHashMap<String, Double?>()
        for (r in ROW.findAll(html)) {
            val cells = CELL.findAll(r.groupValues[1]).toList()
            if (cells.size < 2) continue
            val label = text(cells[0].groupValues[2])
            if (label.isEmpty() || label in out) continue
            val title = TITLE.find(cells[1].groupValues[1])?.groupValues?.get(1)
            out[label] = number(title) ?: number(text(cells[1].groupValues[2]))
        }
        return out
    }

    fun parse(code: String, html: String, now: Long): StockFacts {
        val r = rows(html)
        return StockFacts(code, now, r[PE], r[NET_INCOME], r[EPS], r[DEBT], r[DEBT_EQUITY])
    }

    /** "1,028,174,764", "-4.59M", "2.87", "17.86%": a number with its B, M or K; null for "n/a" or anything else. */
    fun number(s: String?): Double? {
        if (s == null) return null
        val t = s.trim().replace(",", "").removeSuffix("%")
        if (t.isEmpty() || t.equals("n/a", true) || t == "-") return null
        val scale = when (t.last()) { 'B' -> 1e9; 'M' -> 1e6; 'K' -> 1e3; 'T' -> 1e12; else -> 1.0 }
        val digits = if (scale == 1.0) t else t.dropLast(1)
        return digits.toDoubleOrNull()?.times(scale)
    }

    private fun text(html: String): String = SPACE.replace(TAG.replace(html, " ").replace("&amp;", "&").replace("&nbsp;", " "), " ").trim()
}

/** stockanalysis.com's public statistics pages; no account is needed. */
class StockAnalysisClient(private val http: EgxHttp = EgxHttp(), private val site: String = SITE) {
    /** The company's facts, or null when the page does not exist. */
    suspend fun facts(code: String, now: Long): StockFacts? {
        val html = http.get("$site/quote/egx/$code/statistics/".toHttpUrl(), accept = "text/html") ?: return null
        return kotlinx.coroutines.withContext(http.io) { StockAnalysisParser.parse(code, html, now) }
    }

    companion object {
        const val SITE = "https://stockanalysis.com"

        /** Facts are read again when older than this. */
        const val KEEP_MS = 7 * EgxTime.DAY_MS
    }
}
