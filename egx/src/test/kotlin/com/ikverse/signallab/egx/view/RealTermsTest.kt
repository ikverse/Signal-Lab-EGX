package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.data.Macro
import com.ikverse.signallab.egx.data.YahooBar
import com.ikverse.signallab.egx.data.YahooChart
import com.ikverse.signallab.egx.i18n.Tr
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealTermsTest {
    private val en = Tr.ENGLISH
    private val start = EgxTime.day(2026, 2, 1)
    private val end = EgxTime.day(2026, 11, 12)

    @Test
    fun aRiseThatIsOnlyThePoundFallingShowsAsNoneInDollars() {
        // The account made +25% in pounds while the dollar went from 40 to 50 pounds: nothing in dollars.
        val usd = listOf(start - EgxTime.DAY_MS to 40.0, end to 50.0)
        val gold = listOf(start to 2_000.0, end to 2_500.0)
        val v = realTerms(start, end, 100_000.0, 125_000.0, 0.15, 0.20, usd, gold, en)
        assertTrue(v.known)
        val dollars = v.bars.getValue(Measure.DOLLARS)
        assertEquals(0.0, dollars[0].value!!, 1e-12)
        assertEquals(1.15 * 40 / 50 - 1, dollars[1].value!!, 1e-12)
        assertEquals(0.0, dollars[3].value!!, 1e-12, "held dollars are flat in dollars")
        assertEquals(0.25, v.bars.getValue(Measure.POUNDS)[3].value!!, 1e-12, "held dollars made 25% in pounds")
        // In gold: the gram went from 40·2000/31.1 to 50·2500/31.1 pounds.
        assertEquals(1.25 * (40.0 * 2000) / (50.0 * 2500) - 1, v.bars.getValue(Measure.GOLD)[0].value!!, 1e-12)
        assertEquals(0.0, v.bars.getValue(Measure.GOLD)[4].value!!, 1e-12)
        assertEquals("In dollars" to "$2,500 → $2,500", v.became[1])
        assertEquals(3, v.became.size)
        assertFalse(v.note.contains("known up to"), "both series reach the ledger's last day")
    }

    @Test
    fun withNoDollarForTheFirstDayOnlyPoundsAreShown() {
        val v = realTerms(start, end, 100_000.0, 110_000.0, null, null, listOf(end to 50.0), emptyList(), en)
        assertFalse(v.known)
        assertNull(v.bars.getValue(Measure.DOLLARS)[0].value)
        assertEquals(1, v.became.size)
    }

    @Test
    fun aStaleSeriesIsNamed() {
        val usd = listOf(start to 40.0, end - 3 * EgxTime.DAY_MS to 48.0)
        val v = realTerms(start, end, 100_000.0, 110_000.0, null, null, usd, emptyList(), en)
        assertTrue(v.note.contains("The dollar is known up to 9 November 2026."), v.note)
    }

    @Test
    fun macroClosesKeepOneADayAndLookBackToTheLastKnown() {
        val chart = YahooChart("EGP=X", 0, "1d", listOf(
            YahooBar(start / 1000, 1.0, 1.0, 1.0, 47.0, 0.0),
            YahooBar(start / 1000 + 3600, 1.0, 1.0, 1.0, 47.5, 0.0),
            YahooBar(start / 1000 + 86_400, 1.0, 1.0, 1.0, Double.NaN, 0.0),
            YahooBar(start / 1000 + 3 * 86_400, 1.0, 1.0, 1.0, 48.0, 0.0),
        ))
        val s = Macro.closes(chart)
        assertEquals(listOf(start to 47.5, start + 3 * EgxTime.DAY_MS to 48.0), s)
        assertEquals(47.5, Macro.at(s, start + 2 * EgxTime.DAY_MS))
        assertNull(Macro.at(s, start - 1))
        assertEquals(48.0, Macro.at(s, end))
    }
}
