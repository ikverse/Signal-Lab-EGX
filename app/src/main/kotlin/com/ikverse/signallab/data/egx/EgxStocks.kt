package com.ikverse.signallab.data.egx

import android.content.Context
import org.json.JSONArray
import java.io.FileNotFoundException

/** One listed company, as the stock table knows it. */
data class EgxStock(
    /** The EGX ticker, such as COMI. Always Latin letters, in either language. */
    val code: String,
    val isin: String,
    /** Yahoo's ISIN symbol, which carries the hourly bars (EGS60121C018.CA). */
    val hourlySymbol: String,
    val nameEn: String,
    /** The Arabic name, when the names table has it. */
    val nameAr: String? = null,
    /** How EGX Analyzer checked the ISIN symbol: "price" (prices matched) or "name". */
    val verified: String = "",
) {
    /** Yahoo's legacy symbol, which carries the full daily history, dividends and splits. */
    val dailySymbol: String get() = "$code.CA"
}

/**
 * The stock table: every EGX company with its Yahoo names, seeded from EGX Analyzer's `yahoo_symbols.json` (220 rows),
 * with Arabic names added from `names_ar.tsv` when that file is present.
 */
class EgxStocks(val all: List<EgxStock>) {
    private val byCode = all.associateBy { it.code }

    operator fun get(code: String): EgxStock? = byCode[code]

    val codes: Set<String> get() = byCode.keys

    val size: Int get() = all.size

    companion object {
        const val SYMBOLS_ASSET = "egx/yahoo_symbols.json"
        const val ARABIC_ASSET = "egx/names_ar.tsv"

        /** Reads the table from the app's assets. The Arabic names are optional. */
        fun fromAssets(context: Context): EgxStocks {
            val assets = context.assets
            val symbols = assets.open(SYMBOLS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arabic = try {
                assets.open(ARABIC_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: FileNotFoundException) {
                null
            }
            return parse(symbols, arabic)
        }

        /** The table from the JSON and, optionally, the Arabic names. Sorted by code; a repeated code keeps its first row. */
        fun parse(symbolsJson: String, arabicTsv: String? = null): EgxStocks {
            val names = arabicTsv?.let(::parseArabicNames).orEmpty()
            val arr = JSONArray(symbolsJson)
            val seen = LinkedHashMap<String, EgxStock>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val code = r.optString("egx").trim().uppercase()
                val yahoo = r.optString("yahoo").trim()
                if (code.isEmpty() || yahoo.isEmpty() || code in seen) continue
                seen[code] = EgxStock(
                    code = code,
                    isin = r.optString("isin").trim().ifEmpty { yahoo.removeSuffix(".CA") },
                    hourlySymbol = yahoo,
                    nameEn = r.optString("company").trim(),
                    nameAr = names[code],
                    verified = r.optString("verified"),
                )
            }
            return EgxStocks(seen.values.sortedBy { it.code })
        }

        /** `egx<TAB>arabic`, one company a line. A header line, blank lines and lines starting with # are skipped. */
        fun parseArabicNames(tsv: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (raw in tsv.lineSequence()) {
                val line = raw.removePrefix("﻿").trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val code = line.substring(0, tab).trim().uppercase()
                val name = line.substring(tab + 1).trim()
                if (code == "EGX" || name.isEmpty()) continue
                out[code] = name
            }
            return out
        }
    }
}
