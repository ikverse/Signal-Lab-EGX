package com.ikverse.signallab.data.egx

import com.ikverse.signallab.data.egx.EgxFixtures.day
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArabFinanceParserTest {
    private val items = ArabFinanceParser.parse(EgxFixtures.text("arabfinance_dividends.html"))

    private fun first(code: String, kind: DividendNewsKind? = null) =
        items.first { it.code == code && (kind == null || it.kind == kind) }

    @Test
    fun readsEveryItemOnTheRecordedPage() {
        assertEquals(20, items.size)
        assertEquals(listOf("PRDC", "ADCI", "MPCI", "FERC", "ISMQ", "CSAG", "EDFM", "CEFM", "FERC", "EDFM", "EFIH", "ISPH",
            "MFSC", "MICH", "WCDF", "MHOT", "CICH", "WCDF", "MHOT", "ISPH"), items.map { it.code })
    }

    @Test
    fun aNoticeGivesTheAmountAndThePaymentDay() {
        val p = items.first()
        assertEquals(DividendNewsKind.NOTICE, p.kind)
        assertEquals(0.1, p.amount!!, 1e-12)
        assertEquals(day(2026, 11, 30), p.paymentDay)
        assertEquals(day(2026, 11, 12), p.published)
        assertTrue(p.headline.startsWith("بايونيرز"))
        assertTrue(p.url.startsWith("https://www.arabfinance.com/ar/news/newdetails/"))
        assertEquals(0.1300043571, first("ISPH", DividendNewsKind.NOTICE).amount!!, 1e-12)
        assertEquals(day(2026, 10, 14), first("ISPH", DividendNewsKind.NOTICE).paymentDay)
    }

    @Test
    fun theWordingVariesAndIsStillRead() {
        // "اعتبارًا من 19 نوفمبر 2026", a month by name.
        assertEquals(day(2026, 11, 19), first("ISMQ").paymentDay)
        assertEquals(0.35, first("ISMQ").amount!!, 1e-12)
        // "إعتباراً من02/11/2026", no space; the headline calls it a free distribution, the text a cash coupon.
        assertEquals(day(2026, 11, 2), first("CEFM").paymentDay)
        assertEquals(DividendNewsKind.NOTICE, first("CEFM").kind)
        // "(CICH.CA)" and "-(FERC)" are tickers too.
        assertEquals(day(2026, 9, 24), first("CICH").paymentDay)
    }

    @Test
    fun anAssemblyGivesItsDayAndSometimesTheAmount() {
        val csag = first("CSAG")
        assertEquals(DividendNewsKind.ASSEMBLY, csag.kind)
        assertEquals(day(2026, 10, 28), csag.assemblyDay)
        assertNull(csag.amount)
        assertEquals(day(2026, 10, 7), first("EFIH").assemblyDay)
        val mhot = first("MHOT", DividendNewsKind.ASSEMBLY)
        assertEquals(day(2026, 9, 16), mhot.assemblyDay)
        assertEquals(1.2, mhot.amount!!, 1e-12)
        assertEquals(day(2026, 9, 15), first("ISPH", DividendNewsKind.ASSEMBLY).assemblyDay)
    }

    @Test
    fun aDateCutShortIsNotGuessed() {
        // The page itself prints "12/11/202" in one of FERC's two items.
        val cut = items.filter { it.code == "FERC" }.single { it.paymentDay == null }
        assertEquals(DividendNewsKind.OTHER, cut.kind)
        assertEquals(1.25, cut.amount!!, 1e-12)
        // MICH pays in two parts and the summary stops before the date.
        assertEquals(3.25, first("MICH").amount!!, 1e-12)
        assertNull(first("MICH").paymentDay)
    }

    @Test
    fun anotherLayoutArabicDigitsAndPiastresStillWork() {
        val html = """
            <ul><li><a class="x" href='/ar/news/newdetails/abc'>شركة <b>ألفا</b> توزع كوبونا بقيمة ٣٥ قرشا للسهم</a>
            <div>أعلنت شركة ألفا (ALFA) توزيع الكوبون اعتبارًا من ٠٥/١٢/٢٠٢٦ للسهم</div>
            <small>آخر تحديث منذ 2026/11/13 10:00:00 ص</small></li>
            <li><a href="https://www.arabfinance.com/ar/news/newdetails/no-ticker">خبر بلا رمز</a><p>لا شيء</p></li></ul>
        """.trimIndent()
        val one = ArabFinanceParser.parse(html).single()
        assertEquals("ALFA", one.code)
        assertEquals(0.35, one.amount!!, 1e-12)
        assertEquals(day(2026, 12, 5), one.paymentDay)
        assertEquals(day(2026, 11, 13), one.published)
        assertEquals("شركة ألفا توزع كوبونا بقيمة ٣٥ قرشا للسهم", one.headline)
        assertTrue(ArabFinanceParser.parse("<html><body>maintenance</body></html>").isEmpty())
    }
}

class DividendBookTest {
    private val news = ArabFinanceParser.parse(EgxFixtures.text("arabfinance_dividends.html"))

    /** Sessions as COMI traded them up to 12 Nov 2026 (8 Oct was a holiday); after that, every weekday. */
    private val cal = EgxFixtures.weekdays(day(2026, 8, 2), day(2026, 11, 12), holidays = setOf(day(2026, 10, 8)))
    private val today = day(2026, 11, 14)

    private val yahoo = mapOf(
        "MHOT" to listOf(YahooDividend(day(2025, 9, 29), 0.75), YahooDividend(day(2026, 10, 13), 1.2)),
        "ISPH" to listOf(YahooDividend(day(2026, 10, 12), 0.130004)),
        "FERC" to listOf(YahooDividend(day(2026, 11, 10), 1.25)),
        "EDFM" to listOf(YahooDividend(day(2026, 11, 1), 23.0)),
    )

    private val book = DividendBook.merge(emptyList(), news, yahoo, cal, today)

    private fun of(code: String) = book.single { it.code == code }

    @Test
    fun eachDividendAppearsOnceWhateverTheNumberOfItems() {
        assertEquals(15, book.size)
        val mhot = of("MHOT")
        assertEquals(day(2026, 9, 16), mhot.assemblyDay)
        assertEquals(day(2026, 10, 15), mhot.paymentDay)
        assertEquals(1.2, mhot.amount!!, 1e-12)
        // FERC: an item with a date cut short, then the notice; they are one dividend.
        assertEquals(day(2026, 11, 12), of("FERC").paymentDay)
        assertEquals(day(2026, 10, 25), of("FERC").announcedDay)
    }

    @Test
    fun theLastDayToBuyIsThreeSessionsBeforePayment() {
        // ISMQ pays from Thu 19 Nov: Yahoo has nothing yet, so the last day is an estimate, Mon 16 Nov.
        val ismq = of("ISMQ")
        assertEquals(day(2026, 11, 16), ismq.lastCumDay)
        assertTrue(ismq.lastCumEstimated)
        assertEquals(DividendCheck.WAITING, ismq.check)
    }

    @Test
    fun yahoosDividendTableConfirmsOrQuestionsEachOne() {
        val mhot = of("MHOT")
        assertEquals(DividendCheck.CONFIRMED, mhot.check)
        assertEquals(day(2026, 10, 13), mhot.yahooExDay)
        assertEquals(day(2026, 10, 12), mhot.lastCumDay)
        assertEquals(false, mhot.lastCumEstimated)
        assertEquals(DividendCheck.CONFIRMED, of("ISPH").check, "0.130004 against 0.1300043571")
        assertEquals(day(2026, 11, 9), of("FERC").lastCumDay)
        assertEquals(DividendCheck.AMOUNT_DIFFERS, of("EDFM").check)
        assertEquals(23.0, of("EDFM").yahooAmount)
        // WCDF was paid from 15 Oct; a month later this test's Yahoo table still has nothing.
        assertEquals(DividendCheck.NOT_ON_YAHOO, of("WCDF").check)
    }

    @Test
    fun upcomingAndUndated() {
        val up = DividendBook.upcoming(book, today)
        assertEquals(listOf("ISMQ", "ADCI", "MPCI", "PRDC"), up.map { it.code })
        assertEquals(listOf(day(2026, 11, 16), day(2026, 11, 24), day(2026, 11, 25), day(2026, 11, 25)), up.map { it.lastCumDay })
        assertEquals(setOf("CSAG", "EFIH", "MICH"), DividendBook.undated(book, today).map { it.code }.toSet())
    }

    @Test
    fun mergingTheSameNewsAgainChangesNothing() {
        val again = DividendBook.merge(book.mapIndexed { i, d -> d.copy(id = i + 1L) }, news, yahoo, cal, today)
        assertEquals(book.mapIndexed { i, d -> d.copy(id = i + 1L) }, again)
    }

    @Test
    fun aHandEntryKeepsItsValues() {
        val mine = EgxDividend(id = 7, code = "ADCI", amount = 9.0, lastCumDay = day(2026, 11, 20), source = DividendSource.HAND, byHand = true)
        val out = DividendBook.merge(listOf(mine), news.filter { it.code == "ADCI" }, emptyMap(), cal, today)
        val adci = out.single()
        assertEquals(9.0, adci.amount)
        assertEquals(day(2026, 11, 20), adci.lastCumDay)
        assertNull(adci.paymentDay)
    }

    @Test
    fun aCorrectedPaymentDayReplacesTheOldOne() {
        val first = DividendNews("ABCD", DividendNewsKind.NOTICE, 1.0, day(2026, 11, 22), null, day(2026, 11, 10), "a", "u1")
        val fixed = first.copy(paymentDay = day(2026, 11, 24), published = day(2026, 11, 11), url = "u2")
        val out = DividendBook.merge(emptyList(), listOf(fixed, first), emptyMap(), cal, today).single()
        assertEquals(day(2026, 11, 24), out.paymentDay)
        assertEquals("u2", out.url)
        // Next year's assembly is a new dividend.
        val nextYear = DividendNews("ABCD", DividendNewsKind.ASSEMBLY, null, null, day(2027, 9, 15), day(2027, 9, 16), "b", "u3")
        assertEquals(2, DividendBook.merge(listOf(out), listOf(nextYear), emptyMap(), cal, today).size)
    }
}
