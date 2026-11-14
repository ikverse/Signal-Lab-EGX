package com.ikverse.signallab.data.egx

/** What a dividend refresh did. */
data class DividendRefresh(val newsRead: Int, val added: Int, val updated: Int, val upcoming: List<EgxDividend>)

/**
 * The dividend list: read from Arab Finance's news, checked against each stock's Yahoo dividend table, and open to the
 * owner's own entries and corrections (no screen yet; the screens call these). Nothing here deletes: a dividend that
 * will not happen is marked cancelled.
 */
class EgxDividendRepository(
    private val store: EgxStore,
    private val news: ArabFinanceClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Reads the news page and re-checks every dividend. [calendar] places payment days in sessions. */
    suspend fun refresh(calendar: EgxCalendar): DividendRefresh {
        val items = news.news()
        return apply(items, calendar)
    }

    /** Re-checks every dividend against Yahoo's table without reading the news (after a hand change, or offline). */
    suspend fun recheck(calendar: EgxCalendar): DividendRefresh = apply(emptyList(), calendar)

    private suspend fun apply(items: List<DividendNews>, calendar: EgxCalendar): DividendRefresh {
        val now = clock()
        val today = EgxTime.today(now)
        val before = store.dividends()
        val merged = DividendBook.merge(before, items, store.yahooDividends(), calendar, today, now)
        val changed = merged.filter { it.id == 0L || it != before.firstOrNull { b -> b.id == it.id } }
        store.saveDividends(changed)
        val all = store.dividends()
        return DividendRefresh(items.size, changed.count { it.id == 0L }, changed.count { it.id != 0L },
            DividendBook.upcoming(all, today))
    }

    suspend fun all(): List<EgxDividend> = store.dividends()

    /** Dividends whose last cum-day is today or later, soonest first. */
    suspend fun upcoming(today: Long = EgxTime.today(clock())): List<EgxDividend> = DividendBook.upcoming(store.dividends(), today)

    /** Announced dividends whose date is not known yet. */
    suspend fun undated(today: Long = EgxTime.today(clock())): List<EgxDividend> = DividendBook.undated(store.dividends(), today)

    /**
     * Adds a dividend by hand. [lastCumDay] is the last day that carries it; when only [paymentDay] is known the last
     * cum-day is worked out from it at the next check. Returns its id.
     */
    suspend fun addByHand(code: String, amount: Double?, lastCumDay: Long? = null, paymentDay: Long? = null, note: String = ""): Long {
        require(lastCumDay != null || paymentDay != null) { "A dividend needs its last day or its payment day" }
        val d = EgxDividend(code = code.uppercase(), amount = amount, lastCumDay = lastCumDay, paymentDay = paymentDay,
            source = DividendSource.HAND, byHand = true, headline = note, updatedAt = clock())
        return store.saveDividends(listOf(d)).single()
    }

    /**
     * Corrects a dividend: each value given replaces the stored one, and the dividend is then the owner's (a refresh no
     * longer changes its values). Returns false when there is no such dividend.
     */
    suspend fun correct(id: Long, amount: Double? = null, lastCumDay: Long? = null, paymentDay: Long? = null): Boolean {
        val d = store.dividend(id) ?: return false
        store.saveDividends(listOf(d.copy(
            amount = amount ?: d.amount,
            // A new payment day alone moves the last day too: it is worked out again at the next check.
            lastCumDay = lastCumDay ?: if (paymentDay != null) null else d.lastCumDay,
            lastCumEstimated = if (lastCumDay != null) false else d.lastCumEstimated,
            paymentDay = paymentDay ?: d.paymentDay,
            byHand = true,
            updatedAt = clock(),
        )))
        return true
    }

    /** Marks a dividend as not happening (or, with [cancelled] false, restores it). It is kept either way. */
    suspend fun cancel(id: Long, cancelled: Boolean = true): Boolean {
        val d = store.dividend(id) ?: return false
        store.saveDividends(listOf(d.copy(cancelled = cancelled, byHand = true, updatedAt = clock())))
        return true
    }
}
