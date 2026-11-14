package com.ikverse.signallab.egx.data

/**
 * Recorded answers, trimmed from real downloads made on 2026-11-14 (`research/data/egx/` of the main checkout):
 * - `daily_COMI.json`: COMI.CA daily, 14 Apr to 12 Nov 2026, with Yahoo's dividend and split tables;
 * - `hourly_COMI.json`: EGS60121C018.CA hourly, 5 to 12 Nov 2026;
 * - `daily_ZMID.json`: ZMID.CA daily, 1 to 17 Dec 2024, around an unadjusted -48% day on 10 Dec;
 * - `hourly_CASE30.json`: ^CASE30 hourly, 5 to 12 Nov 2026;
 * - `arabfinance_dividends.html`: Arab Finance's dividend news list as served on 14 Nov 2026.
 */
internal object EgxFixtures {
    fun text(name: String): String =
        requireNotNull(EgxFixtures::class.java.getResource("/egx/$name")) { "missing test resource egx/$name" }
            .readText(Charsets.UTF_8)

    fun chart(name: String): YahooChart = requireNotNull(YahooParser.parse(text(name)))

    val comiDaily: YahooChart by lazy { chart("daily_COMI.json") }
    val comiHourly: YahooChart by lazy { chart("hourly_COMI.json") }
    val zmidDaily: YahooChart by lazy { chart("daily_ZMID.json") }
    val case30Hourly: YahooChart by lazy { chart("hourly_CASE30.json") }

    fun day(y: Int, m: Int, d: Int): Long = EgxTime.day(y, m, d)

    val COMI = EgxStock("COMI", "EGS60121C018", "EGS60121C018.CA", "Commercial International Bank")
    val ZMID = EgxStock("ZMID", "EGS21171C011", "EGS21171C011.CA", "Zahraa Maadi")

    /** A calendar of every Sunday to Thursday from [from] to [to], less [holidays]. */
    fun weekdays(from: Long, to: Long, holidays: Set<Long> = emptySet()): EgxCalendar {
        val days = ArrayList<Long>()
        var d = from
        while (d <= to) {
            if (EgxTime.isWeekday(d) && d !in holidays) days += d
            d += EgxTime.DAY_MS
        }
        return EgxCalendar(days)
    }
}
