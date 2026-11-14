package com.ikverse.signallab.data.egx

import com.ikverse.signallab.data.egx.EgxFixtures.day
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EgxCalendarTest {
    private val comiRows = EgxCleaning.dailyRows(EgxFixtures.comiDaily)
    private val cal = EgxCalendar.fromRows(listOf(comiRows))

    @Test
    fun sessionsAreTheDaysTheStocksTraded() {
        assertEquals(138, cal.size)
        assertEquals(day(2026, 4, 14), cal.first)
        assertEquals(day(2026, 11, 12), cal.last)
        assertTrue(cal.sessions.all(EgxTime::isWeekday))
    }

    @Test
    fun holidaysAreWeekdaysWithoutASession() {
        // Eid al-Adha (26 to 28 May 2026), 23 July, the days COMI did not trade, and the rest.
        val h = cal.holidays()
        for (d in listOf(day(2026, 5, 26), day(2026, 5, 27), day(2026, 5, 28), day(2026, 7, 23), day(2026, 10, 8))) assertTrue(d in h)
        assertEquals(15, h.size)
        assertFalse(day(2026, 11, 6) in h, "a Friday is a weekend, not a holiday")
    }

    @Test
    fun aDayCountsWhenEnoughStocksTraded() {
        val a = listOf(day(2026, 11, 8), day(2026, 11, 9))
        val b = listOf(day(2026, 11, 9))
        assertEquals(listOf(day(2026, 11, 9)), EgxCalendar.fromTradedDays(listOf(a, b), minStocks = 2).sessions.toList())
        assertEquals(20, EgxCalendar.defaultMinStocks(220))
        assertEquals(1, EgxCalendar.defaultMinStocks(2))
    }

    @Test
    fun shiftingCountsSessionsAcrossWeekendsAndHolidays() {
        // Thu 15 Oct 2026 minus three sessions is Mon 12 Oct.
        assertEquals(day(2026, 10, 12), cal.shift(day(2026, 10, 15), -3))
        assertEquals(day(2026, 10, 7), cal.shift(day(2026, 10, 11), -1), "over the weekend and the 8 Oct holiday")
        // After the last known session every Sunday to Thursday counts: Thu 12 Nov plus one is Sun 15 Nov.
        assertEquals(day(2026, 11, 15), cal.shift(day(2026, 11, 12), 1))
        assertEquals(listOf(day(2026, 11, 15), day(2026, 11, 16)), cal.sessionsAfter(day(2026, 11, 12), 2))
        assertNull(cal.shift(day(2026, 4, 14), -1), "before the first known session")
    }

    @Test
    fun betweenAndOnOrBefore() {
        assertEquals(listOf(day(2026, 11, 8), day(2026, 11, 9)), cal.between(day(2026, 11, 6), day(2026, 11, 9)))
        assertEquals(day(2026, 11, 5), cal.onOrBefore(day(2026, 11, 7)))
    }
}

class EgxUniverseTest {
    private val cal = EgxFixtures.weekdays(day(2026, 1, 4), day(2026, 11, 12))

    private fun stock(code: String, turnover: Double, listed: Long = cal.first!!, skip: Int = 0): EgxSeries {
        val days = cal.sessions.filter { it >= listed }
        val missed = days.takeLast(60).take(skip).toSet()
        val rows = days.filter { it !in missed }.map { DailyRow(it, 10.0, 10.0, 10.0, turnover / 10) }
        return EgxCleaning.series(code, rows, calendar = cal)
    }

    @Test
    fun theBusiestStocksByThirtySessionTurnover() {
        val u = EgxUniverse.pick(listOf(stock("AAA", 1e6), stock("BBB", 3e6), stock("CCC", 2e6)), cal, cal.last!!, size = 2)
        assertEquals(listOf("BBB", "CCC"), u.map { it.code })
        assertEquals(listOf(1, 2), u.map { it.rank })
        assertEquals(30 * 3e6, u[0].turnover30, 1e-3)
        assertEquals(60, u[0].tradedOf60)
    }

    @Test
    fun aStockListedUnder180DaysIsLeftOut() {
        val young = stock("NEW", 9e9, listed = cal.last!! - 170 * EgxTime.DAY_MS)
        val old = stock("OLD", 1e6, listed = cal.last!! - 185 * EgxTime.DAY_MS)
        assertEquals(listOf("OLD"), EgxUniverse.pick(listOf(young, old), cal, cal.last!!).map { it.code })
        // With its full listing date known, a series of recent rows alone still counts.
        assertEquals(listOf("NEW", "OLD"), EgxUniverse.pick(listOf(young, old), cal, cal.last!!,
            listedSince = mapOf("NEW" to cal.first!!)).map { it.code })
    }

    @Test
    fun aStockMustHaveTradedOn57OfTheLast60Sessions() {
        val u = EgxUniverse.pick(listOf(stock("THREE", 1e6, skip = 3), stock("FOUR", 2e6, skip = 4)), cal, cal.last!!)
        assertEquals(listOf("THREE"), u.map { it.code })
        assertEquals(57, u.single().tradedOf60)
    }

    @Test
    fun theAnswerDoesNotDependOnInputOrderAndKeepsAtMost80() {
        val many = (1..100).map { stock("S%03d".format(it), if (it <= 10) 5e6 else it * 1e3) }
        val a = EgxUniverse.pick(many, cal, cal.last!!)
        val b = EgxUniverse.pick(many.reversed(), cal, cal.last!!)
        assertEquals(80, a.size)
        assertEquals(a, b)
        assertEquals((1..10).map { "S%03d".format(it) }, a.take(10).map { it.code }, "ties go to the earlier code")
    }

    @Test
    fun aDayOffTheCalendarHasNoUniverse() {
        assertTrue(EgxUniverse.pick(listOf(stock("AAA", 1e6)), cal, day(2026, 11, 13)).isEmpty())
    }
}

class Egx30Test {
    @Test
    fun sessionsAreBuiltFromTheIndexsHourlyBars() {
        val days = Egx30.sessions(EgxFixtures.case30Hourly)
        assertEquals(listOf(day(2026, 11, 5), day(2026, 11, 8), day(2026, 11, 9), day(2026, 11, 10), day(2026, 11, 11), day(2026, 11, 12)),
            days.map { it.day })
        val last = days.last()
        assertEquals(52671.5, last.open, 1e-3)
        assertEquals(52691.30078125, last.high, 1e-3)
        assertEquals(51904.3984375, last.low, 1e-3)
        assertEquals(52023.3984375, last.close, 1e-3)
        for (d in days) assertTrue(d.low <= minOf(d.open, d.close) && d.high >= maxOf(d.open, d.close))
    }

    @Test
    fun aSessionStillTradingIsNotStored() {
        // 12 Nov 2026 at 13:00 Cairo time (11:00 UTC): that day's candle can still change.
        val now = day(2026, 11, 12) + 11 * 3_600_000L
        assertEquals(day(2026, 11, 11), Egx30.sessions(EgxFixtures.case30Hourly, now).last().day)
        val after = day(2026, 11, 12) + 13 * 3_600_000L
        assertEquals(day(2026, 11, 12), Egx30.sessions(EgxFixtures.case30Hourly, after).last().day)
    }

    @Test
    fun theEngineGetsTheIndexAsDailyCandles() {
        val c = Egx30.toCandles(Egx30.sessions(EgxFixtures.case30Hourly).reversed())
        assertEquals(6, c.size)
        assertEquals(day(2026, 11, 12), c.t.last())
        assertEquals(52023.3984375, c.close.last(), 1e-3)
    }
}

class EgxStocksTest {
    private val json = File("src/main/assets/egx/yahoo_symbols.json").readText(Charsets.UTF_8)

    @Test
    fun theTableIsSeededFromEgxAnalyzersSymbols() {
        val t = EgxStocks.parse(json)
        assertEquals(220, t.size)
        val comi = t["COMI"]!!
        assertEquals("COMI.CA", comi.dailySymbol)
        assertEquals("EGS60121C018.CA", comi.hourlySymbol)
        assertEquals("EGS60121C018", comi.isin)
        assertNull(comi.nameAr)
        assertEquals(t.all.map { it.code }.sorted(), t.all.map { it.code })
    }

    @Test
    fun arabicNamesAreAddedWhenTheFileIsThere() {
        val tsv = "﻿egx\tarabic\r\nCOMI\tالبنك التجاري الدولي\r\n# a note\n\nABUK\t أبوقير للأسمدة \nbad line\n"
        val t = EgxStocks.parse(json, tsv)
        assertEquals("البنك التجاري الدولي", t["COMI"]!!.nameAr)
        assertEquals("أبوقير للأسمدة", t["ABUK"]!!.nameAr)
        assertNull(t["AALR"]!!.nameAr)
        assertEquals(2, EgxStocks.parseArabicNames(tsv).size)
    }

    @Test
    fun aRepeatedOrEmptyRowIsSkipped() {
        val t = EgxStocks.parse("""[{"egx":"aaa","yahoo":"EGS1.CA","isin":"EGS1","company":"A"},
            {"egx":"AAA","yahoo":"EGS2.CA","company":"A again"},{"egx":"","yahoo":"EGS3.CA"}]""")
        assertEquals(1, t.size)
        assertEquals("EGS1.CA", t["AAA"]!!.hourlySymbol)
    }
}

class EgxTimeTest {
    @Test
    fun yahooTimestampsFallOnTheCairoDate() {
        // 08:00 UTC is 10:00 in Cairo; 23:30 UTC on the 11th is already the 12th in Cairo.
        assertEquals(day(2026, 11, 12), EgxTime.dayOf(1_794_470_400, 7200))
        assertEquals(day(2026, 11, 12), EgxTime.dayOf(day(2026, 11, 11) / 1000 + 23 * 3600 + 1800, 7200))
    }

    @Test
    fun aSessionIsClosedFromThreeInTheAfternoonCairoTime() {
        val d = day(2026, 11, 12)
        assertFalse(EgxTime.sessionClosed(d, d + 12 * 3_600_000L + 59 * 60_000L))
        assertTrue(EgxTime.sessionClosed(d, d + 13 * 3_600_000L))
        // Cairo keeps summer time: on 1 July 15:00 Cairo is 12:00 UTC.
        val summer = day(2026, 7, 1)
        assertTrue(EgxTime.sessionClosed(summer, summer + 12 * 3_600_000L))
        assertFalse(EgxTime.sessionClosed(summer, summer + 11 * 3_600_000L + 59 * 60_000L))
    }
}
