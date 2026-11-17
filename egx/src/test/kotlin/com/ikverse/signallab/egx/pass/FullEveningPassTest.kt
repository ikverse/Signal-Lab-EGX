package com.ikverse.signallab.egx.pass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.egx.EgxSettings
import com.ikverse.signallab.egx.data.ArabFinanceClient
import com.ikverse.signallab.egx.data.EgxDividendRepository
import com.ikverse.signallab.egx.data.EgxHttp
import com.ikverse.signallab.egx.data.EgxStocks
import com.ikverse.signallab.egx.data.EgxStore
import com.ikverse.signallab.egx.data.EgxSync
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.data.YahooClient
import com.ikverse.signallab.egx.ledger.EgxLedgerDatabase
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.engine.egx.EgxStrategies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Build item 43: a whole evening pass on a PC against recorded data. The mock server answers as Yahoo and Arab Finance
 * did on 14 November 2026, from the research's downloads (191 stocks, their whole daily history and two years of hourly
 * bars, `research/data/egx/`, gitignored) and the recorded dividend page. Nothing calls the network. Skipped where the
 * research data is not on the machine (CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullEveningPassTest {
    private val data = File("../research/data/egx")

    @Test
    fun aFullEveningPassOnRecordedData() = runBlocking {
        assumeTrue("research data not on this machine", File(data, "raw").isDirectory)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stocks = EgxStocks.fromAssets(context)
        val codeOfIsin = stocks.all.associate { it.hourlySymbol to it.code }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val file = when {
                    path == "/ar/home/tagresult" -> File("src/test/resources/egx/arabfinance_dividends.html")
                    path == "/v8/finance/chart/%5ECASE30" -> File(data, "case30_1h.json")
                    path.endsWith(".CA") -> {
                        val symbol = path.substringAfterLast('/')
                        val isin = codeOfIsin[symbol]
                        if (isin != null) File(data, "hourly/$isin.json") else File(data, "raw/${symbol.removeSuffix(".CA")}.json")
                    }
                    else -> null
                }
                return if (file == null || !file.exists()) MockResponse.Builder().code(404).body("""{"chart":{"result":null}}""").build()
                else MockResponse.Builder().code(200).body(file.readText(Charsets.UTF_8)).build()
            }
        }
        server.start()
        try {
            val host = server.url("/").toString().trimEnd('/')
            val http = EgxHttp(sleep = {}, pauseMs = 0)
            // Thursday 12 November 2026, 16:00 in Cairo: the evening after the recorded data's last session.
            val now = ZonedDateTime.of(java.time.LocalDate.parse("2026-11-12").atTime(LocalTime.of(16, 0)), EgxTime.CAIRO).toInstant().toEpochMilli()
            val store = EgxStore(context, name = null, io = Dispatchers.IO)
            val sync = EgxSync(store, YahooClient(http, host = host), stocks, clock = { now })
            val dividends = EgxDividendRepository(store, ArabFinanceClient(http, "$host/ar/home/tagresult?slug=724"), clock = { now })
            val db = EgxLedgerDatabase.open(context, name = null)
            val settings = EgxSettings(context.getSharedPreferences("full-pass-test", Context.MODE_PRIVATE))
            // The ledger as if it had started a year earlier, so every strategy has a year of record to show.
            settings.update { it.copy(liveStart = EgxTime.day(2025, 11, 12), picksStart = EgxTime.day(2025, 11, 12)) }
            val pass = EveningPass(store, sync, dividends, db.dao(), settings, clock = { now })

            val started = System.currentTimeMillis()
            val first = pass.run()
            println("The pass took ${(System.currentTimeMillis() - started) / 1000} s")
            assertTrue(first is PassOutcome.Done, "outcome: $first")
            val r = first.result
            assertEquals(EgxTime.day(2026, 11, 12), r.session)
            println("Full evening pass on ${r.days.map { it.day }.toSet().size} sessions, ${r.calls.size} calls, month end: ${r.monthEnd}")
            for (s in r.strategies) {
                val eq = s.runs.getValue(Records.LEVELS).days.last().equity
                println(String.format(java.util.Locale.US, "  %-26s share %9.0f -> %9.0f  rule alone %9.0f  cash %.3f  EGX30 %s  random middle %s  tier %s",
                    s.strategy.name, s.share, eq, s.runs.getValue(Records.RULE).days.last().equity, s.yardstick.cash.multiple,
                    s.yardstick.index?.multiple?.let { String.format(java.util.Locale.US, "%.3f", it) }, s.yardstick.randomMiddle?.let { String.format(java.util.Locale.US, "%.3f", it) }, s.tier.tier))
            }
            for (c in r.calls) println("  call ${c.action} ${c.code} ${c.shares} shares ${c.timing} (${c.strategy})")
            for (s in r.strategies) {
                assertTrue(s.runs.getValue(Records.LEVELS).days.size > 200, "${s.strategy.id} ran a year")
                assertTrue(s.yardstick.random.all { it in 0.3..5.0 }, "${s.strategy.id}'s random twins grew from 1.00: ${s.yardstick.random}")
            }
            assertTrue(r.strategies.first { it.strategy == EgxStrategies.calm }.runs.getValue(Records.LEVELS).fills.isNotEmpty(), "calm stocks traded in a year")
            assertTrue(r.calls.filter { it.action == "buy" }.all { it.shares > 0 && it.stop != null })
            // The technical picks: both lists ran a year, with their records, yardsticks and the month's choices stored.
            val t = assertNotNull(first.technical, "the technical picks ran")
            for (l in t.lists) {
                println(String.format(java.util.Locale.US, "  %-18s %s -> %.3f  cash %.3f  EGX30 %s  random middle %s  tier %s  picks %d, trades %d",
                    l.list.name, l.choices.last().setup.id, l.record.rule.multiple, l.record.cash.multiple, l.record.index?.multiple, l.record.randomMiddle, l.verdict.tier, l.run.picks.size, l.run.trades.size))
                println("    backtest: ${l.backtest?.let { "${it.trades} trades, won ${it.won}, T1 ${it.target1}, stopped ${it.stopped}, average ${it.average}" }}")
                for (p in l.today) println("    pick ${p.code}: ${p.levels.rounded().let { "${it.entryLow}-${it.entryHigh} stop ${it.stop} T1 ${it.target1} T2 ${it.target2}" }}")
                assertTrue(l.run.days.size > 200, "${l.list.id} ran a year")
                assertTrue(l.run.picks.isNotEmpty(), "${l.list.id} picked something in a year")
                assertEquals(l.run.days.size, db.dao().days(l.list.id, Records.PICKS).size)
            }
            println("  market: ${t.market.egx30} above 50 ${t.market.above50} above 200 ${t.market.above200}, ${t.market.uptrend} of ${t.market.counted} in an uptrend")
            val picks = db.dao().picks().first()
            assertEquals(t.lists.sumOf { it.run.picks.size }, picks.size)
            assertEquals(t.lists.sumOf { it.today.size }, picks.count { it.shown })

            val stored = db.dao().days(EgxStrategies.calm.id, Records.LEVELS)
            assertEquals(r.strategies.first { it.strategy == EgxStrategies.calm }.runs.getValue(Records.LEVELS).days.size, stored.size)

            // A second pass the same evening appends nothing: the ledger only ever adds new sessions.
            val again = pass.run()
            assertTrue(again is PassOutcome.Done)
            assertEquals(stored, db.dao().days(EgxStrategies.calm.id, Records.LEVELS))
            assertEquals(picks, db.dao().picks().first())
        } finally {
            server.close()
        }
    }
}
