package com.ikverse.signallab.data.egx

import com.ikverse.signallab.data.egx.EgxFixtures.day
import com.ikverse.signallab.engine.Timeframe
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EgxCleaningTest {
    private val rows = EgxCleaning.dailyRows(EgxFixtures.comiDaily)
    private val opens = EgxCleaning.sessionOpens(EgxFixtures.comiHourly)
    private val comi = EgxCleaning.series("COMI", rows, opens)

    @Test
    fun rowsWithoutVolumeAreDaysTheStockDidNotTrade() {
        assertEquals(143, EgxFixtures.comiDaily.bars.size)
        assertEquals(138, rows.size)
        val days = rows.map { it.day }.toSet()
        for (d in listOf(day(2026, 8, 9), day(2026, 8, 10), day(2026, 8, 16))) assertFalse(d in days)
        assertTrue(rows.all { it.volume > 0 && it.low <= it.close && it.close <= it.high })
    }

    @Test
    fun aFridayOrSaturdayRowAndARowWithoutAPriceAreDropped() {
        val friday = day(2026, 11, 13) / 1000 + 8 * 3600
        val chart = YahooChart("X.CA", 7200, "1d", listOf(
            YahooBar(friday, 1.0, 1.1, 0.9, 1.0, 100.0),
            YahooBar(friday - 86_400, 1.0, 1.1, 0.9, Double.NaN, 100.0),
            YahooBar(friday - 2 * 86_400, 1.0, 1.1, 0.9, 1.2, 100.0),
        ))
        val kept = EgxCleaning.dailyRows(chart).single()
        assertEquals(day(2026, 11, 11), kept.day)
        assertEquals(1.2, kept.high, "the high is widened to hold the close")
    }

    @Test
    fun yahoosOpenIsNeverUsed() {
        // On 12 Nov Yahoo's "open" was 130.34, Wednesday's close, which is above the day's high of 130.32.
        val i = comi.indexOf(day(2026, 11, 12))
        assertTrue(comi.open[i] <= comi.high[i])
        assertEquals(130.30, comi.open[i], 1e-4)
        // 4 Nov has no hourly bars: the open is the previous close (123.48) held inside the day's range (low 124.02).
        val j = comi.indexOf(day(2026, 11, 4))
        assertFalse(comi.realOpen[j])
        assertEquals(124.02, comi.open[j], 1e-4)
    }

    @Test
    fun theRealOpenIsTheFirstHourlyBarOfTheSession() {
        // Yahoo gives the 10:00 bar no volume even when it traded; its open is still the session's first price.
        assertEquals(127.02, opens.getValue(day(2026, 11, 5)).open, 1e-4)
        assertEquals(128.0, opens.getValue(day(2026, 11, 5)).lastClose, 1e-4)
        val i = comi.indexOf(day(2026, 11, 8))
        assertTrue(comi.realOpen[i])
        assertEquals(127.65, comi.open[i], 1e-4)
        assertEquals(6, comi.realOpen.count { it })
    }

    @Test
    fun hourlyPricesAreCarriedOntoTheDailyScale() {
        // Hourly bars are not adjusted for splits; daily ones are. A 2:1 split shows as a factor of one half.
        val r = DailyRow(day(2026, 11, 12), 52.0, 49.0, 50.0, 1000.0)
        val s = EgxCleaning.series("X", listOf(r), mapOf(r.day to SessionOpen(r.day, 102.0, 100.0)))
        assertEquals(51.0, s.open[0], 1e-9)
        // A factor that makes no sense (a bad hourly row) is ignored.
        val bad = EgxCleaning.series("X", listOf(r), mapOf(r.day to SessionOpen(r.day, 1.0, 1000.0)))
        assertFalse(bad.realOpen[0])
    }

    @Test
    fun aMoveBeyondTheDailyLimitIsFlattened() {
        // ZMID closed at 7.31 on 9 Dec 2024 and 3.81 on 10 Dec: an unadjusted bonus issue, not a trade.
        val z = EgxCleaning.series("ZMID", EgxCleaning.dailyRows(EgxFixtures.zmidDaily))
        assertEquals(listOf(day(2024, 12, 10)), z.flattened)
        val i = z.indexOf(day(2024, 12, 9))
        assertEquals(z.close[i + 1], z.close[i], 1e-9, "the jump day reads flat")
        assertEquals(3.81216, z.close[i], 1e-5)
        val step = 3.812160015106201 / 7.309999942779541
        assertEquals(6.900000095367432 * step, z.low[0], 1e-9, "every earlier price is scaled by the jump")
        // Volume and turnover stay as traded.
        assertEquals(1_904_996.0, z.volume[0])
        assertEquals(7.03000020980835 * 1_904_996, z.turnover[0], 1e-3)
        for (k in 1 until z.size) assertTrue(kotlin.math.abs(z.close[k] / z.close[k - 1] - 1) <= EgxCleaning.MAX_MOVE)
    }

    @Test
    fun aMoveWithinTheLimitIsLeftAlone() {
        val rows = listOf(DailyRow(day(2026, 11, 10), 10.0, 10.0, 10.0, 1.0), DailyRow(day(2026, 11, 11), 12.0, 12.0, 12.0, 1.0))
        val s = EgxCleaning.series("X", rows)
        assertEquals(listOf(10.0, 12.0), s.close.toList())
        assertTrue(s.flattened.isEmpty())
    }

    @Test
    fun onTheCalendarAMissedSessionIsAFlatCandleWithNoVolume() {
        val cal = EgxFixtures.weekdays(day(2026, 11, 8), day(2026, 11, 12))
        val rows = listOf(DailyRow(day(2026, 11, 8), 11.0, 9.0, 10.0, 5.0), DailyRow(day(2026, 11, 10), 13.0, 10.5, 12.0, 5.0))
        val s = EgxCleaning.series("X", rows, calendar = cal)
        assertEquals(listOf(day(2026, 11, 8), day(2026, 11, 9), day(2026, 11, 10)), s.day.toList())
        assertFalse(s.traded[1])
        assertEquals(listOf(10.0, 10.0, 10.0, 0.0, 0.0), listOf(s.open[1], s.high[1], s.low[1], s.volume[1], s.turnover[1]))
        assertEquals(10.5, s.open[2], "the previous close, held inside the day's range")
    }

    @Test
    fun theEngineGetsDailyCandlesKeyedByTheSession() {
        val c = comi.toCandles()
        assertEquals(Timeframe.D1, c.tf)
        assertEquals("COMI", c.symbol)
        assertEquals(comi.size, c.size)
        assertEquals(day(2026, 11, 12), c.t.last())
        assertEquals(day(2026, 11, 13) - 1, c.closeTime.last())
        assertEquals(128.75, c.close.last(), 1e-6)
    }
}
