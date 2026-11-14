package com.ikverse.signallab.data.egx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.data.egx.EgxFixtures.day
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val context: Context get() = ApplicationProvider.getApplicationContext()

private fun memoryStore() = EgxStore(context, name = null, io = Dispatchers.Unconfined)

@RunWith(RobolectricTestRunner::class)
class EgxStoreTest {
    @Test
    fun dailyRowsComeBackExactlyAndAFullDownloadReplacesThem() = runTest {
        val s = memoryStore()
        val rows = EgxCleaning.dailyRows(EgxFixtures.comiDaily)
        s.saveDaily("COMI", rows)
        assertEquals(rows, s.daily("COMI"))
        assertEquals(rows.filter { it.day >= day(2026, 11, 1) }, s.daily("COMI", day(2026, 11, 1)))
        s.saveDaily("COMI", rows.takeLast(2), replaceAll = true)
        assertEquals(2, s.daily("COMI").size)
        s.saveDaily("ABUK", rows.takeLast(1))
        assertEquals(mapOf("COMI" to rows[rows.size - 2].day, "ABUK" to rows.last().day), s.firstDays())
        val (counts, stocks) = s.tradedCounts()
        assertEquals(2, stocks)
        assertEquals(2, counts[rows.last().day])
        assertEquals(1, counts[rows[rows.size - 2].day])
    }

    @Test
    fun opensIndexAndYahooTables() = runTest {
        val s = memoryStore()
        val opens = EgxCleaning.sessionOpens(EgxFixtures.comiHourly)
        s.saveOpens("COMI", opens.values)
        assertEquals(opens, s.opens("COMI"))
        val index = Egx30.sessions(EgxFixtures.case30Hourly)
        s.saveEgx30(index)
        s.saveEgx30(index.takeLast(1))
        assertEquals(index, s.egx30())
        val c = EgxFixtures.comiDaily
        s.saveYahooEvents("COMI", c.dividends, c.splits)
        assertEquals(c.dividends, s.yahooDividends()["COMI"])
        assertEquals(c.splits, s.splits("COMI"))
        s.saveYahooEvents("COMI", c.dividends.takeLast(1), emptyList(), replaceAll = true)
        assertEquals(1, s.yahooDividends().getValue("COMI").size)
        assertTrue(s.splits("COMI").isEmpty())
    }

    @Test
    fun syncStateAndDividendsRoundTrip() = runTest {
        val s = memoryStore()
        assertNull(s.syncState("COMI"))
        val st = EgxSyncState("COMI", 5L, null, day(2025, 12, 17), 0)
        s.saveSyncState(st)
        assertEquals(st, s.syncState("COMI"))
        val d = EgxDividend(code = "MHOT", amount = 1.2, assemblyDay = day(2026, 9, 16), paymentDay = day(2026, 10, 15),
            lastCumDay = day(2026, 10, 12), announcedDay = day(2026, 9, 17), check = DividendCheck.CONFIRMED,
            yahooExDay = day(2026, 10, 13), yahooAmount = 1.2, headline = "مصر للفنادق", url = "u", updatedAt = 9)
        val (a, b) = s.saveDividends(listOf(d, d.copy(code = "ISPH", amount = null)))
        assertEquals(listOf(d.copy(id = a), d.copy(id = b, code = "ISPH", amount = null)), s.dividends())
        s.saveDividends(listOf(d.copy(id = a, cancelled = true)))
        assertTrue(s.dividend(a)!!.cancelled)
        assertEquals(2, s.dividends().size)
    }
}

/** A fake Yahoo and Arab Finance serving the recorded answers, and remembering what was asked. */
private class RecordedSources : Dispatcher() {
    val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        asked += url.encodedPath + "?" + (url.encodedQuery ?: "")
        val file = when (url.encodedPath) {
            "/v8/finance/chart/COMI.CA" -> "daily_COMI.json"
            "/v8/finance/chart/EGS60121C018.CA" -> "hourly_COMI.json"
            "/v8/finance/chart/%5ECASE30" -> "hourly_CASE30.json"
            "/ar/home/tagresult" -> "arabfinance_dividends.html"
            else -> null
        }
        return if (file == null) MockResponse.Builder().code(404).body("""{"chart":{"result":null}}""").build()
        else MockResponse.Builder().code(200).body(EgxFixtures.text(file)).build()
    }
}

@RunWith(RobolectricTestRunner::class)
class EgxSyncTest {
    private lateinit var server: MockWebServer
    private val sources = RecordedSources()

    /** Saturday 14 Nov 2026, noon in Cairo. */
    private val now = day(2026, 11, 14) + 10 * 3_600_000L

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = sources
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val http = EgxHttp(sleep = {}, pauseMs = 0)

    private fun sync(store: EgxStore) = EgxSync(
        store,
        YahooClient(http, host = server.url("/").toString().trimEnd('/')),
        EgxStocks(listOf(EgxFixtures.COMI, EgxFixtures.ZMID)),
        clock = { now },
    )

    @Test
    fun theFirstEveningDownloadsEverythingAndBuildsTheMarket() = runTest {
        val store = memoryStore()
        val r = sync(store).run()
        assertEquals(1, r.fullDownloads)
        assertEquals(listOf("ZMID"), r.missing)
        assertTrue(r.failed.isEmpty(), r.failed.toString())
        assertEquals(138, r.calendar.size)
        assertEquals(listOf("COMI"), r.universe.map { it.code })
        assertEquals(1, r.hourlyStocks)
        assertEquals(6, r.egx30Sessions)
        assertEquals(6, store.opens("COMI").size)
        assertEquals(6, store.egx30().size)
        assertEquals(3, store.yahooDividends().getValue("COMI").size)
        assertTrue(sources.asked.any { it.startsWith("/v8/finance/chart/COMI.CA?period1=0&") })
        assertTrue(sources.asked.any { it == "/v8/finance/chart/EGS60121C018.CA?range=1mo&interval=1h" })
        assertTrue(sources.asked.any { it == "/v8/finance/chart/%5ECASE30?range=2y&interval=1h" })

        val series = sync(store).series("COMI", r.calendar)
        assertEquals(138, series.size)
        assertEquals(6, series.realOpen.count { it })
    }

    @Test
    fun laterEveningsOnlyAskForTheLastWeeks() = runTest {
        val store = memoryStore()
        sync(store).run()
        sources.asked.clear()
        val r = sync(store).run()
        assertEquals(0, r.fullDownloads)
        assertEquals(1, r.updates)
        assertTrue(r.missing.isEmpty(), "a stock Yahoo lacks is asked again only after a week")
        val from = (day(2026, 11, 12) - 40 * EgxTime.DAY_MS) / 1000
        assertTrue(sources.asked.any { it.startsWith("/v8/finance/chart/COMI.CA?period1=$from&") }, sources.asked.toString())
        assertFalse(sources.asked.any { "ZMID" in it })
        assertTrue(sources.asked.any { it == "/v8/finance/chart/EGS60121C018.CA?range=5d&interval=1h" })
        assertTrue(sources.asked.any { it == "/v8/finance/chart/%5ECASE30?range=5d&interval=1h" })
        assertEquals(138, store.daily("COMI").size)
    }

    @Test
    fun aSplitYahooRecordedSinceTheLastFullDownloadFetchesTheWholeHistoryAgain() = runTest {
        val store = memoryStore()
        sync(store).run()
        // Pretend the last full download predates the 17 Dec 2025 bonus issue.
        store.saveSyncState(store.syncState("COMI")!!.copy(lastSplitDay = day(2025, 1, 1)))
        sources.asked.clear()
        val r = sync(store).run()
        assertEquals(1, r.fullDownloads)
        assertEquals(2, sources.asked.count { it.startsWith("/v8/finance/chart/COMI.CA?") })
        assertEquals(day(2025, 12, 17), store.syncState("COMI")!!.lastSplitDay)
    }

    @Test
    fun aSessionNotYetClosedIsNotStored() = runTest {
        val store = memoryStore()
        val during = day(2026, 11, 12) + 9 * 3_600_000L
        EgxSync(store, YahooClient(http, host = server.url("/").toString().trimEnd('/')), EgxStocks(listOf(EgxFixtures.COMI)),
            clock = { during }).run()
        assertEquals(day(2026, 11, 11), store.daily("COMI").last().day)
        assertEquals(day(2026, 11, 11), store.egx30().last().day)
    }

    @Test
    fun dividendsAreReadCheckedAndOpenToTheOwner() = runTest {
        val store = memoryStore()
        val c = EgxFixtures.comiDaily
        store.saveYahooEvents("MHOT", listOf(YahooDividend(day(2026, 10, 13), 1.2)), emptyList())
        store.saveYahooEvents("COMI", c.dividends, c.splits)
        val cal = EgxFixtures.weekdays(day(2026, 8, 2), day(2026, 11, 12), holidays = setOf(day(2026, 10, 8)))
        val repo = EgxDividendRepository(store, ArabFinanceClient(http, server.url("/ar/home/tagresult?slug=724").toString()),
            clock = { now })

        val first = repo.refresh(cal)
        assertEquals(20, first.newsRead)
        assertEquals(15, first.added)
        assertEquals(listOf("ISMQ", "ADCI", "MPCI", "PRDC"), first.upcoming.map { it.code })
        assertEquals(DividendCheck.CONFIRMED, repo.all().single { it.code == "MHOT" }.check)
        assertEquals(0, repo.refresh(cal).added, "reading the same page again adds nothing")

        val mine = repo.addByHand("swdy", 2.5, lastCumDay = day(2026, 11, 26), note = "board proposal")
        assertTrue(repo.correct(repo.all().single { it.code == "PRDC" }.id, amount = 0.12))
        val adci = repo.all().single { it.code == "ADCI" }.id
        assertTrue(repo.cancel(adci))
        repo.refresh(cal)

        val all = repo.all()
        assertEquals(16, all.size, "nothing is deleted")
        assertEquals(0.12, all.single { it.code == "PRDC" }.amount)
        assertTrue(all.single { it.code == "PRDC" }.byHand)
        assertEquals(DividendSource.HAND, all.single { it.id == mine }.source)
        assertEquals(listOf("ISMQ", "MPCI", "PRDC", "SWDY"), repo.upcoming().map { it.code })
        assertFalse(repo.correct(9999, amount = 1.0))
    }
}
