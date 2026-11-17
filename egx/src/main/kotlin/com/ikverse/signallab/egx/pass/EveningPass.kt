package com.ikverse.signallab.egx.pass

import com.ikverse.signallab.egx.EgxSettings
import com.ikverse.signallab.egx.data.ArabFinanceClient
import com.ikverse.signallab.egx.data.StockAnalysisClient
import com.ikverse.signallab.egx.data.EgxDividendRepository
import com.ikverse.signallab.egx.data.EgxStore
import com.ikverse.signallab.egx.data.EgxSync
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.ledger.EgxLedgerDao
import com.ikverse.signallab.egx.ledger.PassRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.egx.EgxStrategies
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** What an evening pass came to. */
sealed class PassOutcome {
    class Done(val result: EveningResult, val technical: TechnicalResult? = null) : PassOutcome()

    /** Today is a session, but its candle is not on Yahoo yet: try again later (item 23). */
    data object NotYet : PassOutcome()

    /** Not a session day (a Friday, a Saturday or a holiday): nothing to do. */
    data object NoSession : PassOutcome()

    class Failed(val message: String) : PassOutcome()
}

/**
 * One evening pass, Sunday to Thursday after the close: download the day's prices, the EGX30 and the dividend news, lay
 * them on the board, run every strategy, append the ledger, and write tomorrow's calls. Notifying is left to the caller.
 */
class EveningPass(
    private val store: EgxStore,
    private val sync: EgxSync,
    private val dividends: EgxDividendRepository,
    private val ledger: EgxLedgerDao,
    private val settings: EgxSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the picks' company facts and headlines come from; null reads none (tests). */
    private val facts: StockAnalysisClient? = null,
    private val companyNews: ArabFinanceClient? = null,
) {
    suspend fun run(): PassOutcome {
        val now = clock()
        val today = EgxTime.today(now)
        return try {
            val report = sync.run()
            val calendar = report.calendar
            val last = calendar.last ?: return log(now, null, PassOutcome.Failed("no prices yet"))
            val prefs0 = settings.value
            val holidays = Holidays.known(calendar, prefs0.holidays, EgxTime.date(today).plusDays(60))
            // On a session day the pass waits for that session's candle; on a day off it works from the last session
            // (running twice is harmless: the ledger only appends what is new).
            if (last < today && isSession(EgxTime.date(today), holidays)) return log(now, last, PassOutcome.NotYet)
            runCatching { dividends.refresh(calendar) }
            val codes = store.firstDays().keys
            val raw = codes.associateWith { store.daily(it) }
            val series = codes.map { sync.series(it, calendar) }
            val news = dividends.all()
            val snapshot = BoardSource.build(calendar, series, raw, store.yahooDividends(), news, store.egx30(), holidays)
            if (settings.value.liveStart == null) settings.update { it.copy(liveStart = last) }
            val prefs = settings.value
            val newsDates = news.filter { !it.cancelled }.groupBy { it.code }.mapValues { (_, l) -> l.maxBy { it.anchor ?: 0 }.let { it.lastCumDay to it.paymentDay } }
            val result = Evening.run(snapshot, prefs, prefs.liveStart!!, newsDates)
            append(result)
            // The technical picks never stop the rest of the evening: a failure is logged and the calls stand.
            if (settings.value.picksStart == null) settings.update { it.copy(picksStart = last) }
            val technical = try {
                TechnicalEvening.run(snapshot, settings.value, settings.value.picksStart!!).also { appendPicks(it, snapshot, now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ledger.addPass(PassRow(at = now, session = last, ok = false, message = "technical picks: ${e.message ?: e.javaClass.simpleName}"))
                null
            }
            log(now, last, PassOutcome.Done(result, technical))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(now, null, PassOutcome.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Appends what is new. The ledger is append-only: rows for sessions already stored are left as they were, so a later
     * correction of old prices cannot rewrite what the ledger said at the time.
     */
    private suspend fun append(r: EveningResult) {
        for (s in EgxStrategies.all) for (record in Records.ALL) {
            val after = ledger.lastDay(s.id, record) ?: Long.MIN_VALUE
            ledger.addDays(r.days.filter { it.strategy == s.id && it.record == record && it.day > after })
            ledger.addFills(r.fills.filter { it.strategy == s.id && it.record == record && it.day > after })
        }
        ledger.putYardsticks(r.yardsticks)
        ledger.clearUnplacedCalls(r.session)
        ledger.putCalls(r.calls)
    }

    /**
     * Appends the technical picks' new rows: sessions after the last one stored for each list, as for the strategies.
     * Before a shown pick is written, its company's facts (read again when a week old) and latest headlines are read,
     * so the row keeps what was known that evening.
     */
    private suspend fun appendPicks(t: TechnicalResult, snapshot: Snapshot, now: Long) {
        val board = snapshot.board
        for (l in t.lists) {
            val after = ledger.lastDay(l.list.id, Records.PICKS) ?: Long.MIN_VALUE
            ledger.addDays(t.days.filter { it.strategy == l.list.id && it.day > after })
            ledger.addPickEvents(t.events.filter { it.list == l.list.id && it.day > after })
            val rows = ArrayList<com.ikverse.signallab.egx.ledger.PickRow>()
            for (p in l.run.picks) {
                val day = board.calendar[p.k]
                if (day <= after) continue
                var hadNews = false
                var flags = emptySet<String>()
                var loss: Boolean? = null
                if (l.shows && p.k == board.size - 1) {
                    val f = readFacts(p.code, now)
                    loss = f?.netIncome?.let { it < 0 }
                    val recent = readNews(p.code, now).filter { h -> h.published != null && h.published >= day - NEWS_DAYS * EgxTime.DAY_MS && h.published <= day + EgxTime.DAY_MS }
                    hadNews = recent.isNotEmpty()
                    flags = recent.flatMap { it.flags.split(',') }.filter { it.isNotEmpty() }.toSet()
                }
                rows.add(TechnicalEvening.row(p, l.list, snapshot, l.shows && p.k == board.size - 1, hadNews, flags, loss))
            }
            ledger.addPicks(rows)
        }
        ledger.putYardsticks(t.yardsticks)
        ledger.addPickChoices(t.choices)
        ledger.putPickRecords(t.records)
        ledger.addMarketDays(listOf(t.marketRow))
    }

    private suspend fun readFacts(code: String, now: Long): com.ikverse.signallab.egx.ledger.FactsRow? {
        val cached = ledger.facts(code)
        if (cached != null && now - cached.fetched < StockAnalysisClient.KEEP_MS) return cached
        val read = facts?.let { c -> runCatching { c.facts(code, now) }.getOrNull() } ?: return cached
        val row = com.ikverse.signallab.egx.ledger.FactsRow(code, read.fetched, read.peRatio, read.netIncome, read.eps, read.totalDebt, read.debtToEquity)
        ledger.putFacts(row)
        return row
    }

    private suspend fun readNews(code: String, now: Long): List<com.ikverse.signallab.egx.ledger.HeadlineRow> {
        val read = companyNews?.let { c -> runCatching { c.companyNews(code) }.getOrNull() }.orEmpty()
        ledger.addHeadlines(read.map { h ->
            com.ikverse.signallab.egx.ledger.HeadlineRow(h.url, code, h.headline, h.published, h.flags.map { it.name.lowercase() }.sorted().joinToString(","), now)
        })
        return ledger.headlines(code)
    }

    private suspend fun log(now: Long, session: Long?, outcome: PassOutcome): PassOutcome {
        val (ok, message) = when (outcome) {
            is PassOutcome.Done -> true to "${outcome.result.calls.size} calls"
            PassOutcome.NotYet -> false to "today's prices are not out yet"
            PassOutcome.NoSession -> true to "no session today"
            is PassOutcome.Failed -> false to outcome.message
        }
        ledger.addPass(PassRow(at = now, session = session, ok = ok, message = message))
        return outcome
    }

    companion object {
        /** A pick "had news" when a headline on the company came out in this many days before it. */
        const val NEWS_DAYS = 10

        /** True when [date] is a day the exchange trades, by the weekdays and the holidays known. */
        fun isSession(date: LocalDate, holidays: Set<LocalDate>): Boolean = com.ikverse.signallab.engine.egx.Sessions.isSession(date, holidays)
    }
}
