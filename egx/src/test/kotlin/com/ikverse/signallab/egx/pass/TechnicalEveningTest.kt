package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.EgxSynth
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.egx.PickAccount
import com.ikverse.signallab.engine.strategy.Tier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TechnicalEveningTest {
    private fun snapshot(seed: Long, sessions: Int = 900): Snapshot {
        val board = EgxSynth.board(stocks = 30, sessions = sessions, seed = seed)
        val ix = DoubleArray(board.size) { 1000.0 * (1 + it * 0.0005) }
        return Snapshot(board, EgxSynth.dividends(board, seed), ix, emptyMap())
    }

    @Test
    fun bothListsRunFromTheirStartWithTheirLimitsAndYardsticks() {
        var picked = 0
        for (seed in 1L..3L) {
            val snap = snapshot(seed)
            val b = snap.board
            val start = b.calendar[600]
            val r = TechnicalEvening.run(snap, EgxPrefs(pickAmount = 5_000.0), start)
            assertEquals(EgxStrategies.technical.map { it.id }, r.lists.map { it.list.id })
            assertEquals(b.calendar.last(), r.session)
            for (l in r.lists) {
                assertEquals(Tier.TESTING, l.verdict.tier, "too early for a live record")
                assertTrue(l.shows)
                assertTrue(l.today.size <= l.list.versions.first().maxNew)
                assertTrue(l.today.all { it.k == b.size - 1 })
                assertTrue(l.run.days.first().k == 600)
                assertEquals(20, l.record.random.size)
                assertEquals(5_000.0 * PickAccount.SLOTS * (1 + com.ikverse.signallab.engine.Markets.EGX.sessionRate(0.19)), l.run.days.first().equity, 5_000.0 * PickAccount.SLOTS * 0.01)
                picked += l.run.picks.size
                for (p in l.run.picks) {
                    assertTrue(p.levels.entryLow <= p.levels.entryHigh && p.levels.stop < p.levels.entryHigh)
                    if (l.list.family == com.ikverse.signallab.engine.egx.PickFamily.MID) assertEquals(null, p.levels.target1, "mid-term picks have no targets")
                    else assertTrue(p.levels.target1!! > p.levels.entryHigh)
                }
                // One choice a month from the list's start, from its own versions.
                assertTrue(l.choices.isNotEmpty() && l.choices.all { c -> c.setup in l.list.versions })
            }
            assertTrue(r.days.all { it.record == Records.PICKS && it.day >= start })
            assertEquals(2, r.yardsticks.size)
            assertTrue(r.events.all { e -> e.day >= start })
            assertTrue(r.choices.map { it.list }.toSet() == EgxStrategies.technical.map { it.id }.toSet())
            assertTrue(r.marketRow.counted in 0..30)
        }
        assertTrue(picked > 0, "the synthetic boards gave no picks at all")
    }

    @Test
    fun aListTheOwnerTurnedOffIsRecordedButNotShown() {
        val snap = snapshot(2)
        val r = TechnicalEvening.run(snap, EgxPrefs(picksOn = setOf(EgxStrategies.shortTerm.id)), snap.board.calendar[600])
        assertTrue(r.lists.first { it.list == EgxStrategies.shortTerm }.shows)
        val mid = r.lists.first { it.list == EgxStrategies.midTerm }
        assertTrue(!mid.shows)
        assertTrue(r.shownToday.none { it.setup.family == com.ikverse.signallab.engine.egx.PickFamily.MID })
        assertTrue(r.reached.none { it.list == EgxStrategies.midTerm.id })
    }

    @Test
    fun runningTwiceGivesTheSameRows() {
        val snap = snapshot(3)
        val a = TechnicalEvening.run(snap, EgxPrefs(), snap.board.calendar[650])
        val b = TechnicalEvening.run(snap, EgxPrefs(), snap.board.calendar[650])
        assertEquals(a.events, b.events)
        assertEquals(a.days, b.days)
        assertEquals(a.choices, b.choices)
        assertEquals(a.lists.map { l -> l.run.picks.map { it.code to it.k } }, b.lists.map { l -> l.run.picks.map { it.code to it.k } })
    }

    @Test
    fun theNotificationNamesTheEveningsPicks() {
        val tr = Tr.ENGLISH
        for (seed in 1L..6L) {
            val snap = snapshot(seed)
            val r = TechnicalEvening.run(snap, EgxPrefs(), snap.board.calendar[600])
            val title = NotificationTexts.picksTitle(r, tr)
            val lines = NotificationTexts.pickLines(r, { it }, tr)
            if (r.shownToday.isEmpty()) assertEquals(null, title)
            else {
                assertTrue(title!!.startsWith("Technical picks: "))
                assertEquals(r.shownToday.size, lines.size)
                assertTrue(lines.all { it.contains(": buy ") && it.contains(", stop ") })
            }
        }
    }
}
