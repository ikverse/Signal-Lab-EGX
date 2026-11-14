package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DividendCalendarTest {
    private val en = Tr.ENGLISH
    private val dec = YearMonth.of(2026, 12)

    @Test
    fun aMonthShowsEachDividendsTwoDatesWithItsYield() {
        val divs = listOf(
            EgxDividend(code = "EAST", amount = 1.6, lastCumDay = EgxTime.day(2026, 12, 8), paymentDay = EgxTime.day(2026, 12, 13)),
            EgxDividend(code = "SWDY", amount = 2.0, lastCumDay = EgxTime.day(2026, 11, 30), paymentDay = EgxTime.day(2026, 12, 3)),
            EgxDividend(code = "COMI", amount = 5.0, lastCumDay = EgxTime.day(2026, 12, 10), cancelled = true),
            EgxDividend(code = "HRHO", amount = null, paymentDay = EgxTime.day(2027, 1, 5)),
        )
        val v = dividendMonth(divs, mapOf("EAST" to 32.0), setOf("EAST"), dec, en) { it.lowercase() }
        assertEquals("December 2026", v.title)
        assertEquals(listOf("SWDY" to true, "EAST" to false, "EAST" to true), v.entries.map { it.code to it.cash })
        val east = v.entries[1]
        assertEquals("Last day to hold", east.title)
        assertEquals("EGP 1.60 a share · yield 5.0% at the last close", east.line)
        assertTrue(east.runOpen)
        assertEquals("EAST east: last day to hold for the dividend", east.calendarTitle)
        assertEquals("EGP 2.00 a share", v.entries[0].line, "no close known, no yield")
        assertEquals(setOf(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 13)), v.marked)
    }

    @Test
    fun weeksRunSundayToSaturday() {
        val v = dividendMonth(emptyList(), emptyMap(), emptySet(), dec, en) { it }
        assertEquals("Sun", v.weekdays.first())
        // 1 December 2026 is a Tuesday: two empty cells first.
        assertNull(v.weeks[0][0]); assertNull(v.weeks[0][1])
        assertEquals(LocalDate.of(2026, 12, 1), v.weeks[0][2])
        assertTrue(v.weeks.all { it.size == 7 })
        assertEquals(31, v.weeks.flatten().count { it != null })
    }
}
