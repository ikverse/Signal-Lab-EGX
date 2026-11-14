package com.ikverse.signallab.egx.widget

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.egx.view.todayView
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import kotlin.test.assertEquals

class WidgetViewTest {
    private val en = Tr.ENGLISH
    private val thu = EgxTime.day(2026, 11, 12)
    private fun call(code: String, action: String) =
        CallRow(thu, EgxStrategies.calm.id, code, action, 100, 10_000.0, 100.0, null, null, null, null, null, "close", null, null, "monthly_list", false)

    @Test
    fun theWidgetSaysTomorrowsPlanAndTheAccountAgainstTheEgx30() {
        val prefs = EgxPrefs(money = 100_000.0, liveStart = EgxTime.day(2026, 2, 1))
        val days = prefs.on.map { PaperDayRow(it, Records.LEVELS, thu, 36_000.0, 0.5, 0.0) }
        val today = todayView(listOf(call("COMI", "buy"), call("SWDY", "buy"), call("HRHO", "sell")), days,
            listOf(YardstickRow(EgxStrategies.calm.id, thu, 1.16, 1.05, 1.0, 1.0, 1.0)), emptyList(), prefs, emptyList(), en,
            { it to it }, EgxTime.date(thu))
        val w = widgetView(today, en)
        assertEquals(today.sentence, w.sentence)
        assertEquals("Buy 2 · Sell 1", w.counts)
        assertEquals("You +8% · EGX30 +5%", w.against)
        assertEquals("Plan for Sunday 15 November", w.dateLine)
    }

    @Test
    fun beforeTheFirstPassItSaysSo() {
        val today = todayView(emptyList(), emptyList(), emptyList(), emptyList(), EgxPrefs(), emptyList(), en, { it to it }, EgxTime.date(thu))
        val w = widgetView(today, en)
        assertEquals("", w.counts)
        assertEquals(Tr.NOT_YET, w.against)
    }
}
