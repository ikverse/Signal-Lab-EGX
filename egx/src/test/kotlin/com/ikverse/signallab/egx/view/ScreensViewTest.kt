package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreensViewTest {
    private val thu = EgxTime.day(2026, 11, 12)
    private val en = Tr.ENGLISH
    private val buy = CallRow(thu, EgxStrategies.dividendRun.id, "EAST", "buy", 390, 12_500.0, 32.05, 31.70, 32.05, 29.25, 35.55, 39.05, "close",
        EgxTime.day(2026, 12, 8), EgxTime.day(2026, 12, 20), "dividend_announced", false, roundTrip = 0.005, atr = 0.70)

    @Test
    fun theCallPageReadsLikeTheAcceptedMockup() {
        val v = callView(buy, "Eastern Company", EgxPrefs(money = 100_000.0), en)
        assertEquals("Buy", v.action)
        assertEquals("Dividend run", v.strategy)
        assertEquals("last close 32.05", v.closeLine)
        assertEquals(listOf("Target 2", "Target 1", "Entry range", "Stop loss"), v.levels.map { it.second })
        assertEquals("Sell half · 195 shares", v.levels[1].third)
        assertEquals("31.70 to 32.05 · above it, wait", v.levels[2].third)
        assertTrue(v.whenText.startsWith("Tomorrow, 15 November, near the close"))
        assertEquals("about 62.50 · at double 125.00", v.amount[2].second)
        assertEquals("Last day to hold" to "8 December", v.dividend[0])
        assertEquals("Cash date" to "20 December", v.dividend[1])
        assertTrue(v.dividendNote!!.contains("before the dividend"), "the run does not collect the dividend")
        assertEquals("390", v.copyText)
        assertEquals(0.05 / 32.05, copyGap(buy, 32.10), 1e-12)
    }

    @Test
    fun theLedgerAddsTheStrategiesThatAreOnAndKeepsBothRecords() {
        val on = EgxPrefs.DEFAULT_ON
        val days = mapOf(
            Records.LEVELS to on.map { PaperDayRow(it, Records.LEVELS, thu, 43_800.0, 0.5, 100.0) },
            Records.RULE to on.map { PaperDayRow(it, Records.RULE, thu, 46_300.0, 0.5, 0.0) },
            Records.LEVELS_DOUBLE to on.map { PaperDayRow(it, Records.LEVELS_DOUBLE, thu, 43_000.0, 0.5, 0.0) },
        )
        val fills = listOf(PaperFillRow(EgxStrategies.calm.id, Records.LEVELS, thu, "COMI", true, 10.0, 100.0, "", 2.5))
        val v = ledgerView(days, listOf(YardstickRow(EgxStrategies.calm.id, thu, 1.16, 1.23, 1.0, 1.1, 1.2)), mapOf(Records.LEVELS to fills),
            emptyList(), 14, EgxPrefs(money = 100_000.0, liveStart = EgxTime.day(2026, 2, 1)), en)
        assertEquals(131_400.0, v.withTargets, 1e-9)
        assertEquals(138_900.0, v.byRule, 1e-9)
        assertEquals("Selling at the targets has cost EGP 7,500 so far.", v.targetsCost)
        assertEquals("The paper account made +31% since 1 February. The EGX30 made +23% and cash +16%.", v.lead)
        assertEquals(3, v.byStrategy.size)
        assertEquals(3, v.watched.size, "slow breakout, low-risk formula and the control")
        assertEquals(129_000.0, v.atDouble, 1e-9)
        assertEquals(300.0, v.dividends, 1e-9)
        assertEquals(2.5, v.costsPaid, 1e-9)
    }

    @Test
    fun theMonthEndListSplitsSellsBuysAndKeeps() {
        fun c(code: String, a: String) = buy.copy(code = code, action = a, strategy = EgxStrategies.calm.id, monthEnd = true, reason = "monthly_list")
        val v = monthEndView(listOf(c("HRHO", "sell"), c("ORWE", "sell"), c("SWDY", "buy"), c("EFID", "buy"), c("ABUK", "buy"), c("COMI", "keep")), 31, en)
        assertEquals("Month-end list: sell 2, buy 3, keep 1", v.title)
        assertEquals(5, v.listOrders)
        assertEquals(2 * 12_500.0, v.bring, 1e-9)
        assertEquals(3 * 12_500.0, v.need, 1e-9)
    }
}
