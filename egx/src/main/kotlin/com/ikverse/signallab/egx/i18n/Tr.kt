package com.ikverse.signallab.egx.i18n

import com.ikverse.signallab.egx.Language
import java.util.Locale

/**
 * The language tables (item 37). Every text of the app is written in English in the code and looked up, by that exact
 * English, in `assets/egx/strings_ar.tsv` (english, arabic, note) for Arabic. A text missing from the table shows in
 * English, and [missing] lists it, so a test can hold the table complete. Placeholders are `%1$s`, `%1$d`, as in the
 * table. Numbers follow the owner's choice of digits.
 */
class Tr(private val arabic: Map<String, String>, val language: Language, val phoneIsArabic: Boolean = false, val arabicDigits: Boolean = false) {
    val isArabic: Boolean get() = language == Language.ARABIC || (language == Language.FOLLOW_PHONE && phoneIsArabic)

    private val seen = HashSet<String>()

    /** English texts asked for that the Arabic table does not have. */
    val missing: Set<String> get() = seen.filterTo(HashSet()) { it !in arabic }

    fun t(en: String, vararg args: Any?): String {
        seen.add(en)
        val pattern = if (isArabic) arabic[en] ?: en else en
        val text = if (args.isEmpty()) pattern else String.format(Locale.US, pattern, *args)
        return digits(if (isArabic) PERCENT.replace(text) { ltr(it.value) } else text)
    }

    /**
     * [s] kept left to right as one piece inside Arabic text, so "4%", "+31%" or "2015–2022" never show as "%4" or
     * "2022–2015". Unchanged in English.
     */
    fun ltr(s: String): String = if (isArabic) "\u2066" + s + "\u2069" else s

    /** [s] with 0-9 as Arabic-Indic digits when the owner chose them and the app is in Arabic. */
    fun digits(s: String): String = if (isArabic && arabicDigits) buildString(s.length) {
        for (ch in s) append(if (ch in '0'..'9') ('٠' + (ch - '0')) else if (ch == '.' ) '٫' else ch)
    } else s

    /** A number of pounds, with thousands separated and no decimals at or above 1,000. */
    fun pounds(x: Double): String = digits(if (kotlin.math.abs(x) >= 1000) String.format(Locale.US, "%,.0f", x) else String.format(Locale.US, "%,.2f", x))

    /** A price, to the exchange's step. */
    fun price(x: Double): String = digits(String.format(Locale.US, "%.2f", x))

    /** A share such as 0.054 as "5.4%". */
    fun percent(x: Double, decimals: Int = 1): String = digits(String.format(Locale.US, "%.${decimals}f%%", 100 * x))

    fun count(n: Long): String = digits(String.format(Locale.US, "%,d", n))

    companion object {
        /** Reads the table's text: a header line, then english, arabic, note separated by tabs. */
        fun parse(tsv: String): Map<String, String> = tsv.lineSequence().drop(1).mapNotNull { line ->
            val cols = line.split('\t')
            val glossary = cols.size > 2 && cols[2] == "glossary"
            if (cols.size < 2 || cols[0].isBlank() || glossary) null else cols[0] to cols[1]
        }.toMap()

        val ENGLISH = Tr(emptyMap(), Language.ENGLISH)

        /** A number with its percent sign, and its sign when it has one. */
        private val PERCENT = Regex("[+\u2212-]?[0-9][0-9.,]*%")

        /** What every record says before the first evening pass has run. */
        const val NOT_YET = "The record starts after the first evening pass."
    }
}
