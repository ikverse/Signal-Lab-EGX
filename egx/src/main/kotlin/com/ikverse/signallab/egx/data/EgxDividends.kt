package com.ikverse.signallab.egx.data

import kotlin.math.abs

/** How a dividend stands against Yahoo's dividend table. */
enum class DividendCheck {
    /** Its ex-day has not come yet, or Yahoo has not caught up. */
    WAITING,

    /** Yahoo recorded it on the expected day with the same amount (within 2%). */
    CONFIRMED,

    /** Yahoo recorded a dividend near the expected day, with a different amount. */
    AMOUNT_DIFFERS,

    /** Well past its ex-day, Yahoo shows no dividend near it: the date or the item may be wrong. */
    NOT_ON_YAHOO,
}

enum class DividendSource { NEWS, HAND }

/**
 * One cash dividend: from Arab Finance's news, or added by hand. Its trigger is [lastCumDay], the last session that
 * still carries it (the research's Dividend run sells on it).
 */
data class EgxDividend(
    val id: Long = 0,
    val code: String,
    /** Pounds per share, before the 5% tax. */
    val amount: Double?,
    val assemblyDay: Long? = null,
    val paymentDay: Long? = null,
    val lastCumDay: Long? = null,
    /** The day the first news item about it appeared. */
    val announcedDay: Long? = null,
    /** [lastCumDay] is three sessions before the payment day, not yet confirmed by Yahoo's ex-day. */
    val lastCumEstimated: Boolean = false,
    val source: DividendSource = DividendSource.NEWS,
    /** The owner set or corrected its values; a refresh then never changes them, only the check. */
    val byHand: Boolean = false,
    /** Marked as not happening. Kept, never deleted. */
    val cancelled: Boolean = false,
    val yahooExDay: Long? = null,
    val yahooAmount: Double? = null,
    val check: DividendCheck = DividendCheck.WAITING,
    val headline: String = "",
    val url: String = "",
    val updatedAt: Long = 0,
) {
    /** The day the dividend is placed by: the payment day, the last cum-day, the assembly, or the first news of it. */
    val anchor: Long? get() = paymentDay ?: lastCumDay ?: assemblyDay ?: announcedDay
}

/** The rules that turn news, Yahoo's table and hand entries into one list of dividends. Pure, so it is tested alone. */
object DividendBook {
    /**
     * On 9 real 2025-26 dividends (MHOT, ISPH, MFSC, CICH, WCDF, EDFM, FERC, CEFM, MICH) Yahoo's ex-day was exactly two
     * sessions before Arab Finance's payment day, so the last day that carries the dividend is three sessions before it.
     */
    const val EX_BEFORE_PAYMENT = 2
    const val LAST_CUM_BEFORE_PAYMENT = 3

    /** An assembly and a notice for the same stock this close together are the same dividend. */
    const val SAME_DIVIDEND_DAYS = 120

    /** Two notices whose payment days differ by at most this are the same dividend (a repeat or a correction). */
    const val SAME_PAYMENT_DAYS = 7

    /** How far from the expected ex-day a Yahoo dividend may sit and still be this one. */
    const val YAHOO_MATCH_DAYS = 7

    /** Sessions after the ex-day before a missing Yahoo record counts as a warning. */
    const val YAHOO_LAG_SESSIONS = 5

    const val AMOUNT_TOLERANCE = 0.02

    /**
     * [existing] updated by [news] (oldest first), then every dividend checked against [yahoo] (each stock's dividend
     * table) and given its last cum-day from [calendar]. Entries the owner set by hand keep their values.
     */
    fun merge(
        existing: List<EgxDividend>,
        news: List<DividendNews>,
        yahoo: Map<String, List<YahooDividend>>,
        calendar: EgxCalendar,
        today: Long,
        now: Long = 0,
    ): List<EgxDividend> {
        val out = existing.toMutableList()
        val ordered = news
            .filter { it.amount != null || it.paymentDay != null || it.kind == DividendNewsKind.ASSEMBLY }
            .sortedWith(compareBy<DividendNews>({ it.published ?: it.paymentDay ?: 0L }, { it.paymentDay ?: 0L }))
        for (n in ordered) {
            val at = matchIndex(out, n)
            if (at < 0) {
                out += EgxDividend(code = n.code, amount = n.amount, assemblyDay = n.assemblyDay, paymentDay = n.paymentDay,
                    announcedDay = n.published, headline = n.headline, url = n.url, updatedAt = now)
                continue
            }
            val d = out[at]
            if (d.byHand) continue
            val notice = n.kind == DividendNewsKind.NOTICE
            out[at] = d.copy(
                amount = if (n.amount != null && (notice || d.amount == null)) n.amount else d.amount,
                paymentDay = n.paymentDay ?: d.paymentDay,
                assemblyDay = n.assemblyDay ?: d.assemblyDay,
                announcedDay = d.announcedDay ?: n.published,
                headline = if (notice || d.headline.isEmpty()) n.headline else d.headline,
                url = if (notice || d.url.isEmpty()) n.url else d.url,
                updatedAt = now,
            )
        }
        return out.map { check(it, yahoo[it.code].orEmpty(), calendar, today) }
    }

    /** The dividend in [list] that [n] is about, or -1 for a new one. */
    private fun matchIndex(list: List<EgxDividend>, n: DividendNews): Int {
        val anchor = n.paymentDay ?: n.assemblyDay ?: n.published ?: return -1
        var best = -1
        var bestGap = Long.MAX_VALUE
        for ((i, d) in list.withIndex()) {
            if (d.code != n.code) continue
            if (d.paymentDay != null && n.paymentDay != null) {
                val gap = abs(d.paymentDay - n.paymentDay)
                if (gap <= SAME_PAYMENT_DAYS * EgxTime.DAY_MS && gap < bestGap) { best = i; bestGap = gap }
                continue
            }
            val theirs = d.anchor ?: continue
            val gap = abs(theirs - anchor)
            // An assembly comes before its payment; a notice never joins an assembly held after it is paid.
            val ordered = !(n.paymentDay != null && d.assemblyDay != null && d.assemblyDay > n.paymentDay) &&
                !(d.paymentDay != null && n.assemblyDay != null && n.assemblyDay > d.paymentDay)
            if (ordered && gap <= SAME_DIVIDEND_DAYS * EgxTime.DAY_MS && gap < bestGap) { best = i; bestGap = gap }
        }
        return best
    }

    /** [d] with its last cum-day and its Yahoo check worked out. */
    fun check(d: EgxDividend, yahoo: List<YahooDividend>, calendar: EgxCalendar, today: Long): EgxDividend {
        if (d.cancelled) return d
        var lastCum = d.lastCumDay
        var estimated = d.lastCumEstimated
        if (d.paymentDay != null && (!d.byHand || lastCum == null)) {
            lastCum = calendar.shift(d.paymentDay, -LAST_CUM_BEFORE_PAYMENT)
            estimated = true
        }
        // The ex-day is the session after the last cum-day: two sessions before the payment day.
        val expectedEx = lastCum?.let { calendar.shift(it, LAST_CUM_BEFORE_PAYMENT - EX_BEFORE_PAYMENT) }
            ?: return d.copy(lastCumDay = lastCum, lastCumEstimated = estimated, check = DividendCheck.WAITING)

        val seen = yahoo
            .filter { abs(it.exDay - expectedEx) <= YAHOO_MATCH_DAYS * EgxTime.DAY_MS }
            .minByOrNull { abs(it.exDay - expectedEx) }
        if (seen == null) {
            val late = calendar.shift(expectedEx, YAHOO_LAG_SESSIONS)?.let { it < today } ?: false
            return d.copy(lastCumDay = lastCum, lastCumEstimated = estimated, yahooExDay = null, yahooAmount = null,
                check = if (late) DividendCheck.NOT_ON_YAHOO else DividendCheck.WAITING)
        }
        var amount = d.amount
        if (!d.byHand) {
            lastCum = calendar.shift(seen.exDay, -1) ?: lastCum
            estimated = false
            if (amount == null) amount = seen.amount
        }
        val same = amount != null && abs(seen.amount / amount - 1) <= AMOUNT_TOLERANCE
        return d.copy(amount = amount, lastCumDay = lastCum, lastCumEstimated = estimated, yahooExDay = seen.exDay,
            yahooAmount = seen.amount, check = if (same) DividendCheck.CONFIRMED else DividendCheck.AMOUNT_DIFFERS)
    }

    /** Dividends whose last cum-day is [today] or later, soonest first. */
    fun upcoming(list: List<EgxDividend>, today: Long): List<EgxDividend> =
        list.filter { !it.cancelled && it.lastCumDay != null && it.lastCumDay >= today }
            .sortedWith(compareBy({ it.lastCumDay }, { it.code }))

    /** Dividends announced (an assembly, a proposal) whose date is not known yet, newest first. */
    fun undated(list: List<EgxDividend>, today: Long): List<EgxDividend> =
        list.filter {
            !it.cancelled && it.lastCumDay == null &&
                (it.anchor ?: EgxTime.today(it.updatedAt)) >= today - SAME_DIVIDEND_DAYS * EgxTime.DAY_MS
        }.sortedByDescending { it.anchor ?: 0L }
}
