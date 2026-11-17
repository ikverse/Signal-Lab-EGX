package com.ikverse.signallab.egx.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The company facts and company headlines read for the technical picks, from pages laid out as the sites lay theirs. */
class PicksSourcesTest {
    /** A statistics page as stockanalysis.com renders it: rows with the exact number in the value cell's title, and prose that repeats labels. */
    private fun statsPage(rows: List<Triple<String, String?, String>>) = buildString {
        append("<html><body><p>The company has a current ratio of 0.97, with a Debt / Equity ratio of 9.99.</p><table class=\"w-full\"><tbody>")
        for ((label, title, text) in rows) {
            append("<!--[0--><tr class=\"border-y\"><td class=\"px-[5px]\"><!--[1--><span><!---->$label<!----></span><!--]--></td>")
            append("<!--[-1--><td class=\"px-[5px] text-right\"${title?.let { " title=\"$it\"" } ?: ""}>$text</td><!--]--></tr><!--]-->")
        }
        append("</tbody></table></body></html>")
    }

    @Test
    fun factsAreReadFromTheTableRowsOnly() {
        val html = statsPage(listOf(
            Triple("PE Ratio", "2.871", "2.87"),
            Triple("Net Income", "1,028,174,764", "1.03B"),
            Triple("Earnings Per Share (EPS)", null, "0.55"),
            Triple("Total Debt", "855,336,105", "855.34M"),
            Triple("Debt / Equity", null, "0.14"),
        ))
        val f = StockAnalysisParser.parse("ELKA", html, 5L)
        assertEquals(2.871, f.peRatio)
        assertEquals(1_028_174_764.0, f.netIncome)
        assertEquals(0.55, f.eps)
        assertEquals(855_336_105.0, f.totalDebt)
        assertEquals(0.14, f.debtToEquity, "the prose's 9.99 is not a table row")
        assertEquals(false, f.lossMaking)
        assertEquals(5L, f.fetched)
    }

    @Test
    fun aLossAndMissingValuesAreKept() {
        val f = StockAnalysisParser.parse("NEDA", statsPage(listOf(
            Triple("PE Ratio", null, "n/a"), Triple("Net Income", null, "-4.59M"), Triple("Total Debt", null, "n/a"),
        )), 0L)
        assertNull(f.peRatio)
        assertEquals(-4_590_000.0, f.netIncome!!, 1e-6)
        assertEquals(true, f.lossMaking)
        assertNull(f.totalDebt)
        assertNull(f.eps)
        assertEquals(17.86, StockAnalysisParser.number("17.86%"))
        assertEquals(2.5e12, StockAnalysisParser.number("2.5T"))
        assertNull(StockAnalysisParser.number("n/a"))
        assertNull(StockAnalysisParser.number(""))
    }

    /** A company's news list as Arab Finance renders it: each item linked twice, its picture then its headline, with the update line after. */
    private fun newsPage(items: List<Triple<String, String, String>>) = buildString {
        append("<html><body>")
        for ((slug, head, date) in items) {
            append("<div class=\"ratio\"><a href=\"/ar/news/newdetails/$slug\"><img src=\"x.jpg\"></a></div>")
            append("<div class=\"d-flex\"><a href=\"/ar/news/newdetails/$slug\"><h5>$head</h5></a><span>أخر تحديث منذ $date 12:30:00 م</span></div>")
        }
        append("</body></html>")
    }

    @Test
    fun headlinesAreReadOnceEachWithTheirDateAndFlags() {
        val html = newsPage(listOf(
            Triple("a1", "التجاري الدولي يصعد بأرباحه بنهاية الربع الثالث", "2026/11/10"),
            Triple("a2", "الجمعية العامة توافق على زيادة رأس المال", "2026/11/05"),
            Triple("a3", "البنك يوقع بروتوكول تعاون", "2026/09/08"),
            Triple("a4", "إقرار توزيع كوبون نقدي", "2026/08/01"),
        ))
        val h = CompanyNewsParser.parse("COMI", html)
        assertEquals(4, h.size, "each item once, though linked twice")
        assertEquals("التجاري الدولي يصعد بأرباحه بنهاية الربع الثالث", h[0].headline)
        assertEquals(EgxTime.day(2026, 11, 10), h[0].published)
        assertEquals("https://www.arabfinance.com/ar/news/newdetails/a1", h[0].url)
        assertEquals(setOf(NewsFlag.RESULTS), h[0].flags)
        assertEquals(setOf(NewsFlag.ASSEMBLY, NewsFlag.CAPITAL_INCREASE), h[1].flags)
        assertTrue(h[2].flags.isEmpty())
        assertEquals(setOf(NewsFlag.DIVIDEND), h[3].flags)
        assertTrue(h.all { it.code == "COMI" })
    }

    @Test
    fun aPageWithoutNewsGivesNone() {
        assertTrue(CompanyNewsParser.parse("COMI", "<html><body><a href=\"/ar/home/about\">عن</a></body></html>").isEmpty())
        assertFalse(CompanyNewsParser.flags("البورصة تغلق مرتفعة").isNotEmpty())
    }
}
