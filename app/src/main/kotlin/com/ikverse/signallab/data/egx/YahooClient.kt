package com.ikverse.signallab.data.egx

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One bar exactly as Yahoo sends it; a missing value is NaN. [time] is unix seconds. */
data class YahooBar(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/** A cash dividend as Yahoo records it: [exDay] is the first session that no longer carries it. */
data class YahooDividend(val exDay: Long, val amount: Double)

/** A split or bonus issue; [ratio] is new shares per old (11:10 is 1.1). */
data class YahooSplit(val day: Long, val ratio: Double)

/** One answer from Yahoo's chart API. */
data class YahooChart(
    val symbol: String,
    val offsetSec: Int,
    /** "1d" or "1h". */
    val granularity: String,
    val bars: List<YahooBar>,
    val dividends: List<YahooDividend> = emptyList(),
    val splits: List<YahooSplit> = emptyList(),
) {
    /** The session day each bar belongs to. */
    fun dayOf(bar: YahooBar): Long = EgxTime.dayOf(bar.time, offsetSec)
}

/** Reads Yahoo's chart JSON. Pure, so it is tested on recorded answers. */
object YahooParser {
    /** The chart in [text]; null when Yahoo answered but has no data for the symbol. */
    fun parse(text: String): YahooChart? = try {
        val chart = JSONObject(text).getJSONObject("chart")
        val results = chart.optJSONArray("result")
        if (results == null || results.length() == 0) null else read(results.getJSONObject(0))
    } catch (e: JSONException) {
        throw EgxDataException.BadResponse(text.take(120), e)
    }

    private fun read(r: JSONObject): YahooChart {
        val meta = r.optJSONObject("meta") ?: JSONObject()
        val offset = meta.optInt("gmtoffset", EgxTime.DEFAULT_OFFSET_SEC)
        val ts = r.optJSONArray("timestamp") ?: JSONArray()
        val quote = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val open = quote?.optJSONArray("open")
        val high = quote?.optJSONArray("high")
        val low = quote?.optJSONArray("low")
        val close = quote?.optJSONArray("close")
        val volume = quote?.optJSONArray("volume")
        val bars = List(ts.length()) { i ->
            YahooBar(ts.getLong(i), open.num(i), high.num(i), low.num(i), close.num(i), volume.num(i))
        }
        val events = r.optJSONObject("events")
        val dividends = ArrayList<YahooDividend>()
        events?.optJSONObject("dividends")?.let { all ->
            for (key in all.keys()) {
                val d = all.getJSONObject(key)
                val amount = d.optDouble("amount", Double.NaN)
                if (amount > 0) dividends += YahooDividend(EgxTime.dayOf(d.getLong("date"), offset), amount)
            }
        }
        val splits = ArrayList<YahooSplit>()
        events?.optJSONObject("splits")?.let { all ->
            for (key in all.keys()) {
                val s = all.getJSONObject(key)
                val num = s.optDouble("numerator", Double.NaN)
                val den = s.optDouble("denominator", Double.NaN)
                if (num > 0 && den > 0) splits += YahooSplit(EgxTime.dayOf(s.getLong("date"), offset), num / den)
            }
        }
        return YahooChart(
            symbol = meta.optString("symbol"),
            offsetSec = offset,
            granularity = meta.optString("dataGranularity"),
            bars = bars,
            dividends = dividends.sortedBy { it.exDay },
            splits = splits.sortedBy { it.day },
        )
    }

    private fun JSONArray?.num(i: Int): Double =
        if (this == null || i >= length() || isNull(i)) Double.NaN else optDouble(i, Double.NaN)
}

/**
 * Yahoo's unofficial chart API, which needs no key. EGX stocks appear twice there: under the legacy `CODE.CA` symbol,
 * which carries the full daily history, dividends and splits; and under the ISIN symbol (`EGS60121C018.CA`), which
 * carries hourly bars for about two years. The daily feed's "open" is the previous close, so the real opening price
 * comes from the first hourly bar of the session ([EgxCleaning.sessionOpens]).
 */
class YahooClient(
    private val http: EgxHttp = EgxHttp(),
    private val host: String = HOST,
) {
    /** Daily history of one stock from [fromSec] (unix seconds; 0 is all of it), with dividends and splits. */
    suspend fun daily(stock: EgxStock, fromSec: Long = 0): YahooChart? =
        fetch(chartUrl(stock.dailySymbol) {
            addQueryParameter("period1", fromSec.toString())
            addQueryParameter("period2", "9999999999")
            addQueryParameter("interval", "1d")
            addQueryParameter("events", "div,splits")
        })

    /** Hourly bars of one stock (by its ISIN symbol) over Yahoo's [range] ("5d", "1mo", "2y"). */
    suspend fun hourly(stock: EgxStock, range: String = "5d"): YahooChart? = hourly(stock.hourlySymbol, range)

    /** Hourly bars of any symbol, such as the EGX30 ([Egx30.SYMBOL]). */
    suspend fun hourly(symbol: String, range: String): YahooChart? =
        fetch(chartUrl(symbol) {
            addQueryParameter("range", range)
            addQueryParameter("interval", "1h")
        })

    /** Both feeds of one stock, ready for [EgxCleaning.series]. */
    suspend fun sessions(stock: EgxStock, fromSec: Long = 0, hourlyRange: String = "5d"): StockFeeds? {
        val daily = daily(stock, fromSec) ?: return null
        return StockFeeds(stock, daily, hourly(stock, hourlyRange))
    }

    private suspend fun fetch(url: HttpUrl): YahooChart? {
        val body = http.get(url) ?: return null
        return kotlinx.coroutines.withContext(http.io) { YahooParser.parse(body) }
    }

    private inline fun chartUrl(symbol: String, query: HttpUrl.Builder.() -> Unit): HttpUrl =
        host.toHttpUrl().newBuilder()
            .addPathSegments("v8/finance/chart")
            .addPathSegment(symbol)
            .apply(query)
            .build()

    companion object {
        const val HOST = "https://query1.finance.yahoo.com"
    }
}

/** A stock's daily feed and, when Yahoo has it, its hourly feed. */
data class StockFeeds(val stock: EgxStock, val daily: YahooChart, val hourly: YahooChart?) {
    /** The cleaned rows and session opens merged, one entry per traded session. */
    fun merged(): List<MergedSession> {
        val opens = hourly?.let(EgxCleaning::sessionOpens).orEmpty()
        return EgxCleaning.dailyRows(daily).map { MergedSession(it, opens[it.day]) }
    }
}

/** One traded session: the daily row, and the hourly open when there is one. */
data class MergedSession(val row: DailyRow, val open: SessionOpen?)
