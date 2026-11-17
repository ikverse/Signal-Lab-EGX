package com.ikverse.signallab.egx.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** What a dividend news item announces. */
enum class DividendNewsKind {
    /** The EGX notice: the coupon, its amount and the date it is paid from (the last day to buy is three sessions before). */
    NOTICE,

    /** The general assembly approved the dividend: from here the amount is final. */
    ASSEMBLY,

    /** Anything else about a dividend (a board proposal, a bonus issue). Kept, but it places nothing. */
    OTHER,
}

/** One item from Arab Finance's dividend news, read as far as it could be. */
data class DividendNews(
    val code: String,
    val kind: DividendNewsKind,
    /** Pounds per share; null when the item gives no cash amount. */
    val amount: Double?,
    /** The day the coupon is paid from ("اعتبارا من"); for a notice this places the dividend. */
    val paymentDay: Long?,
    /** The day the general assembly met. */
    val assemblyDay: Long?,
    /** The day Arab Finance last updated the item. */
    val published: Long?,
    val headline: String,
    val url: String,
)

/**
 * Reads Arab Finance's dividend news page (server-rendered HTML, in Arabic). Kept tolerant on purpose: it does not rely
 * on the page's classes or layout beyond "a link to a news item, then its summary", and reads the facts from the
 * wording, which is fixed: the ticker in brackets, "بواقع ... جنيه" for the amount, "اعتبارا من" for the payment date,
 * and "الجمعية العامة ... المنعقدة" for the assembly. An item it cannot place is returned with nulls, not dropped; an
 * item with no ticker is dropped.
 */
object ArabFinanceParser {
    private val ITEM_LINK = Regex("""<a\b[^>]*href\s*=\s*["']([^"']*/news/newdetails/[^"']*)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")
    private val TICKER = Regex("""\(\s*-?\s*([A-Z][A-Z0-9]{2,5})(?:\.CA)?\s*\)""")
    private val AMOUNT = Regex("""(?:بواقع|بقيمة|قدره|قيمته)\s*(\d+(?:[.,]\d+)?)\s*(جنيه|جنيها|جنيهات|قرش|قرشا|قروش)""")
    private const val DATE = """(\d{1,2})\s*/\s*(\d{1,2})\s*/\s*(\d{4})(?!\d)|(\d{4})\s*/\s*(\d{1,2})\s*/\s*(\d{1,2})(?!\d)|(\d{1,2})\s+(\S+)\s+(\d{4})(?!\d)"""
    private val PAYMENT = Regex("""اعتبارا\s*من\s*(?:يوم\s*)?(?:$DATE)""")
    private val ASSEMBLY_DAY = Regex("""(?:انعقدت|المنعقدة|المنعقده|انعقاد)\s*(?:\S+\s*){0,2}?(?:$DATE)""")
    private val UPDATED = Regex("""تحديث[^0-9]{0,20}(\d{4})/(\d{1,2})/(\d{1,2})""")

    private val MONTHS = mapOf(
        "يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5, "يونيو" to 6, "يونيه" to 6,
        "يوليو" to 7, "يوليه" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
    )

    fun parse(html: String, baseUrl: String = ArabFinanceClient.SITE): List<DividendNews> {
        val links = ITEM_LINK.findAll(html).toList()
        val out = ArrayList<DividendNews>()
        for ((i, m) in links.withIndex()) {
            val end = if (i + 1 < links.size) links[i + 1].range.first else minOf(html.length, m.range.last + 4000)
            val headline = text(m.groupValues[2])
            if (headline.isEmpty()) continue
            val tail = text(html.substring(m.range.last + 1, maxOf(m.range.last + 1, end)))
            item(baseUrl, m.groupValues[1], headline, tail)?.let { out += it }
        }
        return out.distinctBy { it.url }
    }

    /** One item from its headline and the text after it (summary, then the "last updated" line). */
    fun item(baseUrl: String, href: String, headline: String, tail: String): DividendNews? {
        val cut = tail.indexOf("تحديث").let { if (it < 0) tail.length else it }
        val summary = normalize(tail.substring(0, cut))
        val head = normalize(headline)
        val code = TICKER.find(summary)?.groupValues?.get(1) ?: TICKER.find(head)?.groupValues?.get(1) ?: return null
        val amount = amountIn(summary) ?: amountIn(head)
        val payment = PAYMENT.find(summary)?.let { dateOf(it.groups, 1) }
        val assembly = if ("الجمعية العامة" in summary) ASSEMBLY_DAY.find(summary)?.let { dateOf(it.groups, 1) } else null
        val published = UPDATED.find(normalize(tail))?.let { g ->
            safeDay(g.groupValues[1].toInt(), g.groupValues[2].toInt(), g.groupValues[3].toInt())
        }
        val kind = when {
            "الجمعية العامة" in summary || "الجمعية العامة" in head -> DividendNewsKind.ASSEMBLY
            payment != null && amount != null -> DividendNewsKind.NOTICE
            else -> DividendNewsKind.OTHER
        }
        val url = if (href.startsWith("http")) href else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
        return DividendNews(code, kind, amount, payment, assembly, published, headline.trim(), url)
    }

    private fun amountIn(s: String): Double? {
        val m = AMOUNT.find(s) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val pounds = if (m.groupValues[2].startsWith("قر")) value / 100 else value
        return pounds.takeIf { it > 0 }
    }

    /** The date in a match of [DATE] whose first group is number [g]: d/m/yyyy, yyyy/m/d or "19 نوفمبر 2026". */
    private fun dateOf(groups: MatchGroupCollection, g: Int): Long? {
        fun v(k: Int) = groups[g + k]?.value
        v(0)?.let { return safeDay(v(2)!!.toInt(), v(1)!!.toInt(), it.toInt()) }
        v(3)?.let { return safeDay(it.toInt(), v(4)!!.toInt(), v(5)!!.toInt()) }
        v(6)?.let { d -> val month = MONTHS[v(7)!!] ?: return null; return safeDay(v(8)!!.toInt(), month, d.toInt()) }
        return null
    }

    private fun safeDay(year: Int, month: Int, day: Int): Long? =
        if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) null
        else try {
            EgxTime.day(year, month, day)
        } catch (_: java.time.DateTimeException) {
            null
        }

    private fun text(html: String): String =
        SPACE.replace(decode(TAG.replace(html, " ")), " ").trim()

    private fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * Arabic spelled one way: Arabic-Indic digits to ASCII, the hamza forms of alef to a bare alef, no tatweel or short
     * vowels, and the Arabic comma to a space, so "إعتباراً من" and "اعتبارًا من" both read "اعتبارا من".
     */
    fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '٠'..'٩' -> b.append('0' + (ch - '٠'))
                in '۰'..'۹' -> b.append('0' + (ch - '۰'))
                'آ', 'أ', 'إ', 'ٱ' -> b.append('ا')
                'ـ', in 'ً'..'ْ' -> {}
                '،', '؛' -> b.append(' ')
                else -> b.append(ch)
            }
        }
        return SPACE.replace(b, " ")
    }
}

/** What a company headline is about, read from its wording. */
enum class NewsFlag { RESULTS, ASSEMBLY, CAPITAL_INCREASE, DIVIDEND }

/** One headline from a company's news list on Arab Finance. */
data class CompanyHeadline(val code: String, val headline: String, val url: String, val published: Long?, val flags: Set<NewsFlag>)

/**
 * Reads one company's news list on Arab Finance (`/ar/news/newscompany?key=CODE`): every link to a news item that has
 * a headline, with the "last updated" date after it. Each item appears twice on the page (picture and headline); it is
 * kept once. What a headline is about comes from fixed words: results (أرباح, نتائج, القوائم المالية), the general
 * assembly (الجمعية العامة), a capital increase (زيادة رأس المال), a dividend (توزيع, كوبون).
 */
object CompanyNewsParser {
    private val ITEM_LINK = Regex("""<a\b[^>]*href\s*=\s*["']([^"']*/news/newdetails/[^"']*)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")
    private val UPDATED = Regex("""تحديث[^0-9]{0,20}(\d{4})/(\d{1,2})/(\d{1,2})""")

    fun parse(code: String, html: String, baseUrl: String = ArabFinanceClient.SITE): List<CompanyHeadline> {
        val links = ITEM_LINK.findAll(html).toList()
        val out = LinkedHashMap<String, CompanyHeadline>()
        for ((i, m) in links.withIndex()) {
            val headline = SPACE.replace(TAG.replace(m.groupValues[2], " "), " ").trim()
            if (headline.isEmpty()) continue
            val href = m.groupValues[1]
            val url = if (href.startsWith("http")) href else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
            if (url in out) continue
            val end = if (i + 1 < links.size) links[i + 1].range.first else minOf(html.length, m.range.last + 1500)
            val tail = ArabFinanceParser.normalize(TAG.replace(html.substring(m.range.last + 1, maxOf(m.range.last + 1, end)), " "))
            val published = UPDATED.find(tail)?.let { g ->
                runCatching { EgxTime.day(g.groupValues[1].toInt(), g.groupValues[2].toInt(), g.groupValues[3].toInt()) }.getOrNull()
            }
            out[url] = CompanyHeadline(code, decode(headline), url, published, flags(headline))
        }
        return out.values.toList()
    }

    fun flags(headline: String): Set<NewsFlag> {
        val h = ArabFinanceParser.normalize(headline)
        val out = HashSet<NewsFlag>()
        if (listOf("ارباح", "نتائج", "القوائم المالية", "خسائر", "خسارة").any { it in h }) out.add(NewsFlag.RESULTS)
        if ("الجمعية العامة" in h || "عمومية" in h) out.add(NewsFlag.ASSEMBLY)
        if ("زيادة راس المال" in h || "زيادة رأس المال" in h || "اكتتاب" in h) out.add(NewsFlag.CAPITAL_INCREASE)
        if ("توزيع" in h || "كوبون" in h) out.add(NewsFlag.DIVIDEND)
        return out
    }

    private fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")
}

/** Arab Finance's public dividend news page (the "توزيعات" tag) and companies' news lists. No account is needed. */
class ArabFinanceClient(
    private val http: EgxHttp = EgxHttp(),
    private val url: String = NEWS_URL,
    private val site: String = SITE,
) {
    /** The latest dividend news, newest first (the page shows about two months). */
    suspend fun news(): List<DividendNews> {
        val html = http.get(url.toHttpUrl(), accept = "text/html") ?: return emptyList()
        return kotlinx.coroutines.withContext(http.io) { ArabFinanceParser.parse(html) }
    }

    /** One company's latest headlines, newest first. */
    suspend fun companyNews(code: String): List<CompanyHeadline> {
        val u = "$site/ar/news/newscompany".toHttpUrl().newBuilder().addQueryParameter("key", code).addQueryParameter("page", "1").build()
        val html = http.get(u, accept = "text/html") ?: return emptyList()
        return kotlinx.coroutines.withContext(http.io) { CompanyNewsParser.parse(code, html, site) }
    }

    companion object {
        const val SITE = "https://www.arabfinance.com"
        const val NEWS_URL = "$SITE/ar/home/tagresult?slug=724"
    }
}
