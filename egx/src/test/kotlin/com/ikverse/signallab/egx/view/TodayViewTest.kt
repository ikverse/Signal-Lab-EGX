package com.ikverse.signallab.egx.view

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.Language
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.egx.pass.NotificationTexts
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayViewTest {
    private val arabic = Tr.parse(File("src/main/assets/egx/strings_ar.tsv").readText(Charsets.UTF_8))
    private val thu = EgxTime.day(2026, 11, 12)

    private fun call(code: String, action: String, strategy: String, reason: String, timing: String, cum: Long? = null) =
        CallRow(thu, strategy, code, action, 390, 12_500.0, 32.0, 31.5, 32.0, 29.0, 37.0, 42.0, timing, cum, null, reason, false)

    private val calls = listOf(
        call("EAST", "buy", EgxStrategies.dividendRun.id, "dividend_announced", "close", EgxTime.day(2026, 12, 8)),
        call("EFID", "buy", EgxStrategies.calm.id, "monthly_list", "close"),
        call("HRHO", "sell", EgxStrategies.calm.id, "monthly_list", "open"),
        call("COMI", "keep", EgxStrategies.calm.id, "monthly_list", "none"),
    )
    private val prefs = EgxPrefs(money = 100_000.0)
    private val days = EgxPrefs.DEFAULT_ON.map { PaperDayRow(it, Records.LEVELS, thu, 43_800.0, 0.5, 0.0) }
    private val yard = listOf(YardstickRow(EgxStrategies.calm.id, thu, 1.16, 1.23, 1.0, 1.1, 1.2))
    private val fills = (1..14).map { PaperFillRow(EgxStrategies.calm.id, Records.LEVELS, EgxTime.day(2026, 11, it.coerceAtMost(12)), "S$it", true, 1.0, 1.0, "", 0.0) }

    private fun view(tr: Tr) = todayView(calls, days, yard, fills, prefs, listOf(thu - 86_400_000 to 52_000.0, thu to 52_500.0), tr,
        { it to it }, LocalDate.parse("2026-11-14"))

    @Test
    fun todayReadsLikeTheAcceptedMockup() {
        val v = view(Tr.ENGLISH)
        assertEquals("Plan for Sunday 15 November", v.dateLine)
        assertEquals("Market up · 52,500", v.market)
        assertEquals("Tomorrow: buy two, sell one.", v.sentence)
        assertEquals(listOf("EAST", "EFID", "HRHO"), v.cards.map { it.call.code }, "buys first, keeps not shown as cards")
        assertEquals("EAST · Before the dividend · until 8 December", v.cards[0].line)
        assertEquals("EFID · Calm stocks · Joined the list", v.cards[1].line)
        assertEquals(listOf("390 shares", "EGP 12,500", "Near the close"), v.cards[0].chips)
        assertEquals("At the open", v.cards[2].chips.last())
        assertEquals("EGP 131,400", v.account)
        assertEquals(Triple("You +31%", "EGX30 +23%", "Cash +16%"), v.barsText)
        assertEquals(14, v.ordersUsed)
        assertEquals("36 left this month", v.ordersLeft)
    }

    @Test
    fun everyTextOfTodayAndTheNotificationsHasAnArabicRow() {
        val tr = Tr(arabic, Language.ARABIC)
        val v = view(tr)
        assertEquals("غدًا: اشترِ سهمين وبِع سهمًا.", v.sentence)
        assertEquals("اشترِ", v.cards[0].action)
        for (p in PAGES) tr.t(p)
        for (t in listOf("Paper account", "Free orders", "Placed the order")) tr.t(t)
        NotificationTexts.sentence(emptyList(), tr)
        NotificationTexts.sentence(calls.take(1), tr)
        NotificationTexts.sentence(calls.filter { it.action == "keep" }, tr)
        assertEquals(emptySet(), tr.missing, "texts with no Arabic row")
    }
}
