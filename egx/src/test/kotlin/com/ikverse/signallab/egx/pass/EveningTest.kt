package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.Language
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.EgxSynth
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EveningTest {
    private fun snapshot(seed: Long = 1): Snapshot {
        val board = EgxSynth.board(stocks = 30, sessions = 700, seed = seed)
        val ix = DoubleArray(board.size) { 1000.0 * (1 + it * 0.0005) }
        return Snapshot(board, EgxSynth.dividends(board, seed), ix, emptyMap())
    }

    @Test
    fun everyStrategyRunsFourRecordsAtItsFixedShare() {
        val snap = snapshot()
        val prefs = EgxPrefs(money = 90_000.0)
        val r = Evening.run(snap, prefs, snap.board.calendar[250])
        assertEquals(EgxStrategies.all.size, r.strategies.size)
        for (s in r.strategies) {
            assertEquals(30_000.0, s.share, 1e-9, "split equally among the three that are on")
            assertEquals(Records.ALL.toSet(), s.runs.keys)
            assertEquals(30_000.0 * (1 + com.ikverse.signallab.engine.Markets.EGX.sessionRate(0.19)), s.runs.getValue(Records.RULE).days.first().equity, 30.0)
        }
        val calm = r.strategies.first { it.strategy == EgxStrategies.calm }
        assertTrue(calm.runs.getValue(Records.LEVELS_DOUBLE).days.last().equity <= calm.runs.getValue(Records.LEVELS).days.last().equity + 1e-6 ||
            calm.runs.getValue(Records.LEVELS).fills.isEmpty(), "double costs never do better on the same trades")
        assertTrue(r.days.all { it.day >= snap.board.calendar[250] })
        assertEquals(20, calm.yardstick.random.size)
    }

    @Test
    fun callsComeOnlyFromStrategiesThatAreOnWithWholeSharesAndLevels() {
        for (seed in 1L..4L) {
            val snap = snapshot(seed)
            val prefs = EgxPrefs(money = 100_000.0, on = setOf(EgxStrategies.calm.id, EgxStrategies.slowBreakout.id))
            // Run to every month end of the board, so the monthly list has calls to make.
            val ends = (300 until snap.board.size).filter { snap.board.monthEnding(it) != null }
            for (end in ends.take(3)) {
                val cut = Snapshot(snap.board.cut(end + 1), snap.dividends, snap.egx30.copyOf(end + 1), emptyMap())
                val r = Evening.run(cut, prefs, snap.board.calendar[250])
                assertTrue(r.monthEnd)
                assertTrue(r.calls.all { it.strategy == EgxStrategies.calm.id }, "slow breakout is on Watch: no calls")
                for (c in r.calls.filter { it.action == "buy" }) {
                    assertEquals(floor(c.pounds / c.close).toLong(), c.shares)
                    val stop = c.stop!!; val lo = c.entryLow!!; val hi = c.entryHigh!!; val t1 = c.target1!!; val t2 = c.target2!!
                    assertTrue(stop < lo && lo <= hi && hi < t1 && t1 < t2, c.toString())
                    assertTrue(c.timing == "close" || c.timing == "wait")
                }
                assertTrue(r.calls.filter { it.action == "sell" }.all { it.timing == "open" })
            }
        }
    }

    @Test
    fun theSentenceCountsBuysAndSells() {
        fun call(a: String) = CallRow(0, "s", "X$a${a.length}", a, 1, 1.0, 1.0, null, null, null, null, null, "close", null, null, "r", false)
        val en = Tr.ENGLISH
        assertEquals("Tomorrow: nothing to do.", NotificationTexts.sentence(emptyList(), en))
        assertEquals("Tomorrow: buy two, sell one.", NotificationTexts.sentence(listOf(call("buy"), call("buy").copy(code = "Y"), call("sell")), en))
        assertEquals("Tomorrow: buy one.", NotificationTexts.sentence(listOf(call("buy")), en))
        assertEquals("Tomorrow: keep what you hold.", NotificationTexts.sentence(listOf(call("keep")), en))
        val ar = Tr(mapOf("Tomorrow: nothing to do." to "غدًا: مفيش حاجة تتعمل."), Language.ARABIC)
        assertEquals("غدًا: مفيش حاجة تتعمل.", NotificationTexts.sentence(emptyList(), ar))
    }

    @Test
    fun theTablesFallBackToEnglishAndCanUseArabicDigits() {
        val tr = Tr(mapOf("%1\$d shares" to "%1\$d سهم"), Language.ARABIC, arabicDigits = true)
        assertEquals("١٢ سهم", tr.t("%1\$d shares", 12))
        assertEquals("Unknown", tr.t("Unknown"))
        assertEquals(setOf("Unknown"), tr.missing)
        assertEquals("12 shares", Tr(emptyMap(), Language.ENGLISH).t("%1\$d shares", 12))
        assertEquals(mapOf("a" to "ا"), Tr.parse("english\tarabic\tnote\na\tا\tx\nterm\tمصطلح\tglossary\n"))
    }

    @Test
    fun theEveningSlotIsAfterTheCloseSundayToThursday() {
        val cairo = ZoneId.of("Africa/Cairo")
        fun at(d: String, h: Int) = ZonedDateTime.of(LocalDate.parse(d).atTime(h, 0), cairo).toInstant().toEpochMilli()
        fun slot(d: String) = ZonedDateTime.of(LocalDate.parse(d).atTime(15, 45), cairo).toInstant().toEpochMilli()
        // 12 November 2026 is a Thursday.
        assertEquals(slot("2026-11-12"), Schedule.next(at("2026-11-12", 12)), "Thursday noon: this evening")
        assertEquals(slot("2026-11-15"), Schedule.next(at("2026-11-12", 16)), "Thursday after the slot: Sunday")
        assertEquals(slot("2026-11-15"), Schedule.next(at("2026-11-13", 12)), "Friday: Sunday")
        assertEquals(slot("2026-11-16"), Schedule.next(at("2026-11-15", 18)), "Sunday evening: Monday")
    }

    @Test
    fun knownHolidaysAddTheFixedOnesAhead() {
        val cal = com.ikverse.signallab.egx.data.EgxCalendar(EgxSynth.calendar(40, LocalDate.parse("2026-11-01"), emptySet()).toList())
        val h = Holidays.known(cal, setOf(LocalDate.parse("2027-03-20")), LocalDate.parse("2027-02-01"))
        assertTrue(LocalDate.parse("2027-01-07") in h && LocalDate.parse("2027-01-25") in h)
        assertTrue(LocalDate.parse("2027-03-20") in h)
        assertTrue(LocalDate.parse("2027-04-25") !in h, "beyond the horizon asked for")
    }
}
