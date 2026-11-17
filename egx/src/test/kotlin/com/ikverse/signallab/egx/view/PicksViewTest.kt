package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.Language
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.FactsRow
import com.ikverse.signallab.egx.ledger.HeadlineRow
import com.ikverse.signallab.egx.ledger.MarketDayRow
import com.ikverse.signallab.egx.ledger.PickEventRow
import com.ikverse.signallab.egx.ledger.PickRecordRow
import com.ikverse.signallab.egx.ledger.PickRow
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PicksViewTest {
    private val arabic = Tr.parse(File("src/main/assets/egx/strings_ar.tsv").readText(Charsets.UTF_8))
    private val mon = EgxTime.day(2026, 11, 16)
    private val tue = EgxTime.day(2026, 11, 17)
    private val short = EgxStrategies.shortTerm.id
    private val mid = EgxStrategies.midTerm.id

    private fun shortPick(code: String = "COMI", day: Long = tue, shown: Boolean = true) = PickRow(
        day, short, EgxStrategies.shortTerm.registered.id, code, 1, 100.0, 2.0, 99.0, 100.0, 96.0, 108.0, 112.0, 20, null, 98.5, 0.97, 1.42, 0.0, shown,
        hadNews = true, flags = "assembly,results", lossMaking = true,
    )

    private fun midPick(day: Long = tue) = PickRow(
        day, mid, EgxStrategies.midTerm.registered.id, "EFID", 1, 50.0, 1.0, 49.5, 50.0, 47.0, null, null, 120, 50, null, 0.92, 1.0, 0.041, true,
    )

    private val records = listOf(
        PickRecordRow(short, "backtest", tue, 410, 0.51, 0.13, 0.03, 0.21, 0.027, 21),
        PickRecordRow(mid, "live", tue, 3, 0.33, 0.0, 0.0, 0.33, -0.01, 6),
    )
    private val prefs = EgxPrefs(pickAmount = 10_000.0)

    @Test
    fun aShortTermCardShowsItsFourLevelsSharesRecordFactsAndFlags() {
        val facts = FactsRow("COMI", tue, 7.21, -4.59e6, null, 3e9, 0.13)
        val heads = listOf(HeadlineRow("u1", "COMI", "التجاري الدولي يصعد بأرباحه", mon, "results", tue))
        val v = pickCard(shortPick(), emptyList(), records, facts, heads, null, prefs, Tr.ENGLISH, "CIB")
        assertEquals(listOf("Entry range", "Target 1", "Target 2", "Stop loss"), v.levels.map { it.name })
        assertEquals("99.00 – 100.00", v.levels[0].price)
        assertEquals("−1.0%", v.levels[0].fromEntry)
        assertEquals("+8.0%", v.levels[1].fromEntry)
        assertEquals("+12.0%", v.levels[2].fromEntry)
        assertEquals("−4.0%", v.levels[3].fromEntry)
        assertEquals("100 shares for EGP 10,000", v.shares)
        assertTrue(v.why.contains("(98.50)") && v.why.contains("1.4 times") && v.why.contains("97%"), v.why)
        assertTrue(v.exitNote.startsWith("Sell half at target 1"))
        assertEquals("Backtest since 2010: 410 finished trades · won 51% · reached target 1 13% · stopped 21% · average +2.7% · 21 sessions held", v.record[0])
        assertEquals("Live: no finished trades yet.", v.record[1])
        assertEquals("Price to earnings" to "7.21", v.facts[0])
        assertEquals("Loss, last 12 months" to "EGP −4.6 m", v.facts[1])
        assertEquals(listOf("News in the last 10 days", "General assembly", "Results", "Made a loss"), v.flags)
        assertEquals(1, v.headlines.size)
        assertEquals("Waiting to be bought, three sessions at most", v.status)
        assertTrue(v.open)
        assertTrue(v.shareText.startsWith("COMI · CIB\nShort-term picks, made after the close of 17 November\nEntry range: 99.00 – 100.00"), v.shareText)
        assertTrue(v.shareText.endsWith("not financial advice."))
    }

    @Test
    fun aMidTermCardHasNoTargetsAndSaysHowItLeaves() {
        val v = pickCard(midPick(), emptyList(), records, null, emptyList(), null, prefs, Tr.ENGLISH, "Edita")
        assertEquals(listOf("Entry range", "Stop loss"), v.levels.map { it.name })
        assertEquals("No targets: hold up to 120 sessions; sell at the next open after a close under the stop loss or under the 50-day average.", v.exitNote)
        assertTrue(v.why.contains("4.1%"))
        assertEquals("Live: 3 finished trades · won 33% · stopped 33% · average −1.0% · 6 sessions held", v.record.single())
        assertEquals(listOf("–", "–", "–"), v.facts.map { it.second })
    }

    @Test
    fun theStatusFollowsTheEvents() {
        fun st(vararg e: Pair<String, Double>, close: Double? = null) =
            pickCard(shortPick(day = mon), e.mapIndexed { i, (k, p) -> PickEventRow(tue + i, short, "COMI", mon, k, p) }, records, null, emptyList(), close, prefs, Tr.ENGLISH, "CIB")
        assertEquals("Bought at 99.50 · +2.5% since", st("bought" to 99.5, close = 101.99).status)
        assertEquals("Half sold at target 1; the rest held from 99.50", st("bought" to 99.5, "target1" to 108.0).status)
        val ended = st("bought" to 100.0, "target1" to 108.0, "target2" to 112.0)
        assertEquals("Ended at target 2 (112.00) · +12.0%", ended.status)
        assertTrue(!ended.open)
        assertEquals("Ended at the stop loss: sold at 95.00 · −5.0%", st("bought" to 100.0, "stop" to 95.0).status)
        assertEquals("Not bought: the price never came back into the entry range", st("expired" to 0.0).status)
    }

    @Test
    fun todayShowsTheLatestPicksOfTheListsThatAreOnAndKeepsTheOpenOnes() {
        val picks = listOf(shortPick(), midPick(), shortPick("HRHO", mon))
        val events = listOf(PickEventRow(tue, short, "HRHO", mon, "bought", 20.0))
        val market = MarketDayRow(tue, 41_000.0, true, true, 46, 80)
        val v = picksView(picks, events, records, market, emptyMap(), emptyList(), mapOf("HRHO" to 21.0), prefs, Tr.ENGLISH) { it }
        assertEquals("EGX30 above its 50 and 200-day averages · 46 of 80 busiest stocks in an uptrend", v.market)
        assertEquals(tue, v.day)
        assertEquals(listOf("COMI"), v.lists[0].cards.map { it.pick.code })
        assertEquals(listOf("EFID"), v.lists[1].cards.map { it.pick.code })
        assertEquals(listOf("HRHO"), v.following.map { it.pick.code })
        assertEquals("Testing · on paper", v.lists[0].state)
        val off = picksView(picks, events, records, market, emptyMap(), emptyList(), emptyMap(), prefs.copy(picksOn = setOf(short)), Tr.ENGLISH) { it }
        assertEquals("Off in Settings", off.lists[1].state)
        // A list that is on but whose latest picks were not shown was switched off by its record.
        val hidden = picksView(listOf(shortPick(shown = false), midPick()), emptyList(), records, null, emptyMap(), emptyList(), emptyMap(), prefs, Tr.ENGLISH) { it }
        assertEquals("Switched off by its record: picks are recorded, not shown", hidden.lists[0].state)
        assertTrue(hidden.lists[0].cards.isEmpty())
    }

    @Test
    fun theLearnPageExplainsEachList() {
        val ev = pickEvidence(emptyList(), emptyList(), records, emptyList(), Tr.ENGLISH)
        assertEquals(2, ev.size)
        assertEquals("A new 20-day high on at least 1.0 times its average volume, held up to 20 sessions", ev[0].version)
        assertEquals("Trend template, tightest fifth, held up to 120 sessions", ev[1].version)
        assertEquals(3, ev[0].how.size)
        assertEquals(5.59, ev[0].research!!.old)
        assertEquals(3.93, ev[1].research!!.recent)
    }

    @Test
    fun everyTextOfThePicksReadsInArabic() {
        val tr = Tr(arabic, Language.ARABIC)
        val v = pickCard(shortPick(), listOf(PickEventRow(tue, short, "COMI", tue, "bought", 99.0)), records, FactsRow("COMI", tue, 7.0, 6e10, 1.0, 1.0, 0.1),
            emptyList(), 100.0, prefs, tr, "CIB")
        pickCard(midPick(), emptyList(), records, null, emptyList(), null, prefs, tr, "EFID")
        marketLine(MarketDayRow(tue, 1.0, false, true, 1, 2), tr)
        picksView(listOf(shortPick()), emptyList(), records, null, emptyMap(), emptyList(), emptyMap(), prefs, tr) { it }
        pickEvidence(emptyList(), emptyList(), records, emptyList(), tr)
        assertEquals(emptySet(), tr.missing)
        assertTrue(v.levels.first().name.any { it in '؀'..'ۿ' })
    }
}
