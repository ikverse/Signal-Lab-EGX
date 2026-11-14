package com.ikverse.signallab.data.egx

import kotlinx.coroutines.CancellationException

/** What one evening download did. */
data class EgxSyncReport(
    val stocks: Int,
    val fullDownloads: Int,
    val updates: Int,
    /** Stocks Yahoo has no legacy daily feed for. */
    val missing: List<String>,
    /** Stocks whose download failed, with the reason. */
    val failed: Map<String, String>,
    val hourlyStocks: Int,
    val egx30Sessions: Int,
    val calendar: EgxCalendar,
    val universe: List<UniverseEntry>,
)

/**
 * The evening download of Egyptian data, in the order the plan needs it: every stock's daily rows (the whole history the
 * first time, then the last weeks), the calendar from them, the universe from both, the hourly opens of the universe's
 * stocks, and the EGX30. Only sessions that are over are stored. One stock's failure never stops the rest.
 */
class EgxSync(
    private val store: EgxStore,
    private val yahoo: YahooClient,
    private val stocks: EgxStocks,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): EgxSyncReport {
        val now = clock()
        var full = 0
        var updates = 0
        val missing = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (stock in stocks.all) {
            try {
                when (daily(stock, now)) {
                    Outcome.FULL -> full++
                    Outcome.UPDATE -> updates++
                    Outcome.MISSING -> missing += stock.code
                    Outcome.SKIPPED -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[stock.code] = e.message ?: e.javaClass.simpleName
            }
        }

        val calendar = calendar()
        val universe = universe(calendar)

        var hourly = 0
        for (entry in universe) {
            val stock = stocks[entry.code] ?: continue
            try {
                val range = if (store.opens(stock.code).isEmpty()) FIRST_HOURLY_RANGE else UPDATE_HOURLY_RANGE
                val chart = yahoo.hourly(stock, range) ?: continue
                val opens = EgxCleaning.sessionOpens(chart).values.filter { EgxTime.sessionClosed(it.day, now) }
                store.saveOpens(stock.code, opens)
                hourly++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed["${stock.code} hourly"] = e.message ?: e.javaClass.simpleName
            }
        }

        var index = 0
        try {
            val range = if (store.egx30().isEmpty()) Egx30.FIRST_RANGE else Egx30.UPDATE_RANGE
            yahoo.hourly(Egx30.SYMBOL, range)?.let { chart ->
                val days = Egx30.sessions(chart, now)
                store.saveEgx30(days)
                index = days.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed["EGX30"] = e.message ?: e.javaClass.simpleName
        }
        return EgxSyncReport(stocks.size, full, updates, missing, failed, hourly, index, calendar, universe)
    }

    /** The calendar from every stored stock's traded days. */
    suspend fun calendar(): EgxCalendar {
        val (counts, stockCount) = store.tradedCounts()
        val min = EgxCalendar.defaultMinStocks(stockCount)
        return EgxCalendar(counts.filterValues { it >= min }.keys)
    }

    /** The universe at the last session, from the stored rows. */
    suspend fun universe(calendar: EgxCalendar): List<UniverseEntry> {
        val asOf = calendar.last ?: return emptyList()
        val from = asOf - UNIVERSE_LOOKBACK_DAYS * EgxTime.DAY_MS
        val firstDays = store.firstDays()
        val series = firstDays.keys.map { code -> EgxCleaning.series(code, store.daily(code, from), calendar = calendar) }
        return EgxUniverse.pick(series, calendar, asOf, listedSince = firstDays)
    }

    /** A stock's whole stored history as a cleaned series on [calendar], with its hourly opens. */
    suspend fun series(code: String, calendar: EgxCalendar): EgxSeries =
        EgxCleaning.series(code, store.daily(code), store.opens(code), calendar)

    private enum class Outcome { FULL, UPDATE, MISSING, SKIPPED }

    private suspend fun daily(stock: EgxStock, now: Long): Outcome {
        val state = store.syncState(stock.code)
        if (state != null && state.missingAt > 0 && now - state.missingAt < MISSING_RETRY_MS) return Outcome.SKIPPED
        val lastDay = state?.lastDay
        val incrementalFrom = if (state != null && state.fullAt > 0 && lastDay != null) lastDay - UPDATE_OVERLAP_DAYS * EgxTime.DAY_MS else null
        val incremental = incrementalFrom != null
        val fromSec = if (incrementalFrom != null) incrementalFrom / 1000 else 0L
        var chart = yahoo.daily(stock, fromSec)
        if (chart == null) {
            store.saveSyncState(EgxSyncState(stock.code, state?.fullAt ?: 0, lastDay, state?.lastSplitDay, missingAt = now))
            return Outcome.MISSING
        }
        var isFull = !incremental
        // A split Yahoo had not recorded at the last full download means it re-scaled the whole history: fetch it again.
        val newestSplit = chart.splits.maxOfOrNull { it.day }
        if (incremental && newestSplit != null && newestSplit > (state?.lastSplitDay ?: Long.MIN_VALUE)) {
            chart = yahoo.daily(stock, 0) ?: return Outcome.MISSING
            isFull = true
        }
        val rows = EgxCleaning.dailyRows(chart).filter { EgxTime.sessionClosed(it.day, now) }
        store.saveDaily(stock.code, rows, replaceAll = isFull)
        store.saveYahooEvents(stock.code, chart.dividends, chart.splits, replaceAll = isFull)
        store.saveSyncState(EgxSyncState(
            code = stock.code,
            fullAt = if (isFull) now else state?.fullAt ?: now,
            lastDay = rows.lastOrNull()?.day ?: lastDay,
            lastSplitDay = if (isFull) chart.splits.maxOfOrNull { it.day } else state?.lastSplitDay,
        ))
        return if (isFull) Outcome.FULL else Outcome.UPDATE
    }

    companion object {
        /** Later downloads re-read this many days before the last stored session, so a late correction by Yahoo lands. */
        const val UPDATE_OVERLAP_DAYS = 40L

        /** A stock Yahoo had no feed for is asked again after a week. */
        const val MISSING_RETRY_MS = 7 * EgxTime.DAY_MS

        /** Enough calendar days for 60 sessions with holidays (Eid can close the exchange for a week). */
        const val UNIVERSE_LOOKBACK_DAYS = 120L

        const val FIRST_HOURLY_RANGE = "1mo"
        const val UPDATE_HOURLY_RANGE = "5d"
    }
}
