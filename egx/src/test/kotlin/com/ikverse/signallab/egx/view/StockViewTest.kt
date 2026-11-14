package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.data.DailyRow
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockViewTest {
    private val en = Tr.ENGLISH
    private val d0 = EgxTime.day(2026, 1, 4)
    private fun day(i: Int) = d0 + i * EgxTime.DAY_MS
    private val rows = List(300) { DailyRow(day(it), 11.0 + it * 0.01, 9.0 + it * 0.01, 10.0 + it * 0.01, 1000.0) }
    private val calm = EgxStrategies.calm.id

    @Test
    fun theChartShowsTheChosenRangeAndItsChange() {
        val v = stockView("COMI", "CIB", rows, emptyList(), emptyList(), emptyList(), null, ChartRange.MONTHS_3, en)
        assertEquals(63, v.points.size)
        assertEquals(rows.last().close, v.points.last().second, 1e-12)
        assertTrue(v.rangeLine.endsWith("over 3 months"), v.rangeLine)
        assertEquals(300, stockView("COMI", "CIB", rows, emptyList(), emptyList(), emptyList(), null, ChartRange.ALL, en).points.size)
        assertTrue(v.levels.isEmpty(), "no open call, no levels")
    }

    @Test
    fun anOpenCallsLevelsAreDrawn() {
        val call = CallRow(day(299), calm, "COMI", "buy", 100, 1_000.0, 12.99, 12.6, 12.99, 10.2, 16.5, 20.0, "close", null, null, "monthly_list", true)
        val v = stockView("COMI", "CIB", rows, emptyList(), listOf(call), emptyList(), call, ChartRange.YEAR, en)
        assertEquals(listOf(ChartLevel.Kind.TARGET, ChartLevel.Kind.TARGET, ChartLevel.Kind.ENTRY, ChartLevel.Kind.ENTRY, ChartLevel.Kind.STOP), v.levels.map { it.kind })
        assertEquals("1 calls on this stock so far.", v.callsLine)
    }

    @Test
    fun tradesPairBuysWithSellsAfterCosts() {
        val fills = listOf(
            PaperFillRow(calm, Records.LEVELS, day(1), "COMI", true, 100.0, 10.0, "", 2.0),
            PaperFillRow(calm, Records.LEVELS, day(5), "COMI", false, 50.0, 11.0, "target1", 1.0),
            PaperFillRow(calm, Records.LEVELS, day(9), "COMI", false, 50.0, 12.0, "rule", 1.0),
            PaperFillRow(calm, Records.LEVELS, day(20), "COMI", true, 10.0, 12.0, "", 0.5),
            PaperFillRow(calm, Records.RULE, day(1), "COMI", true, 100.0, 10.0, "", 2.0),
            PaperFillRow(calm, Records.LEVELS, day(1), "HRHO", true, 100.0, 10.0, "", 2.0),
        )
        val t = trades("COMI", fills, en)
        assertEquals(2, t.size)
        assertNull(t[0].result, "the newest trade is still held")
        assertTrue(t[0].line.endsWith("still held"))
        assertEquals((550.0 - 1 + 600 - 1) / 1002.0 - 1, t[1].result!!, 1e-12)
    }

    @Test
    fun dividendsListNewestFirstAndSkipCancelled() {
        val divs = listOf(
            EgxDividend(code = "COMI", amount = 1.5, lastCumDay = day(10)),
            EgxDividend(code = "COMI", amount = 2.0, lastCumDay = day(200)),
            EgxDividend(code = "COMI", amount = 9.0, lastCumDay = day(250), cancelled = true),
            EgxDividend(code = "HRHO", amount = 1.0, lastCumDay = day(100)),
        )
        val v = stockView("COMI", "CIB", rows, divs, emptyList(), emptyList(), null, ChartRange.YEAR, en)
        assertEquals(listOf("EGP 2.00 a share", "EGP 1.50 a share"), v.dividends.map { it.first })
        assertTrue(v.dividends[0].second.startsWith("last day to hold"))
    }
}
