package com.ikverse.signallab.egx.data

import com.ikverse.signallab.egx.data.EgxFixtures.day
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YahooParserTest {
    @Test
    fun readsTheDailyFeedWithItsDividendsAndSplits() {
        val c = EgxFixtures.comiDaily
        assertEquals("COMI.CA", c.symbol)
        assertEquals(7200, c.offsetSec)
        assertEquals("1d", c.granularity)
        assertEquals(143, c.bars.size)
        val last = c.bars.last()
        assertEquals(day(2026, 11, 12), c.dayOf(last))
        assertEquals(128.75, last.close, 1e-6)
        assertEquals(1_515_863.0, last.volume)
        assertEquals(listOf(day(2024, 4, 22), day(2025, 4, 8), day(2026, 4, 7)), c.dividends.map { it.exDay })
        assertEquals(6.0, c.dividends.last().amount)
        assertEquals(1.1, c.splits.single().ratio, 1e-12)
        assertEquals(day(2025, 12, 17), c.splits.single().day)
    }

    @Test
    fun readsHourlyBarsAndKeepsMissingValuesAsNaN() {
        val h = EgxFixtures.comiHourly
        assertEquals("EGS60121C018.CA", h.symbol)
        assertEquals(30, h.bars.size)
        val json = """{"chart":{"result":[{"meta":{"symbol":"X.CA","gmtoffset":7200,"dataGranularity":"1h"},
            "timestamp":[1794470400],"indicators":{"quote":[{"open":[null],"high":[2.0],"low":[1.0],"close":[1.5],"volume":[null]}]}}],"error":null}}"""
        val bar = YahooParser.parse(json)!!.bars.single()
        assertTrue(bar.open.isNaN())
        assertTrue(bar.volume.isNaN())
        assertEquals(1.5, bar.close)
    }

    @Test
    fun anAnswerWithoutDataIsNullAndGarbageIsAnError() {
        assertNull(YahooParser.parse("""{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}"""))
        assertFailsWith<EgxDataException.BadResponse> { YahooParser.parse("<html>rate limited</html>") }
    }
}

class YahooClientTest {
    private lateinit var server: MockWebServer
    private val sleeps = ArrayList<Long>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client() = YahooClient(
        EgxHttp(sleep = { sleeps += it }, pauseMs = 0),
        host = server.url("/").toString().trimEnd('/'),
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun status(code: Int) = MockResponse.Builder().code(code).body("{}").build()

    @Test
    fun asksForDailyCandlesByTheLegacySymbol() = runTest {
        server.enqueue(ok(EgxFixtures.text("daily_COMI.json")))
        val chart = client().daily(EgxFixtures.COMI, fromSec = 1_790_000_000)!!
        assertEquals(143, chart.bars.size)
        val r = server.takeRequest()
        assertEquals("/v8/finance/chart/COMI.CA", r.url.encodedPath)
        assertEquals("1790000000", r.url.queryParameter("period1"))
        assertEquals("1d", r.url.queryParameter("interval"))
        assertEquals("div,splits", r.url.queryParameter("events"))
        assertTrue(r.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun asksForHourlyCandlesByTheIsinSymbolAndTheIndexByItsCaret() = runTest {
        server.enqueue(ok(EgxFixtures.text("hourly_COMI.json")))
        server.enqueue(ok(EgxFixtures.text("hourly_CASE30.json")))
        val c = client()
        assertEquals(30, c.hourly(EgxFixtures.COMI, "5d")!!.bars.size)
        assertEquals("^CASE30", c.hourly(Egx30.SYMBOL, "2y")!!.symbol)
        val first = server.takeRequest().url
        assertEquals("/v8/finance/chart/EGS60121C018.CA", first.encodedPath)
        assertEquals("1h", first.queryParameter("interval"))
        assertEquals("5d", first.queryParameter("range"))
        val second = server.takeRequest().url
        assertEquals("/v8/finance/chart/%5ECASE30", second.encodedPath)
        assertEquals("2y", second.queryParameter("range"))
    }

    @Test
    fun mergesBothFeedsPerSession() = runTest {
        server.enqueue(ok(EgxFixtures.text("daily_COMI.json")))
        server.enqueue(ok(EgxFixtures.text("hourly_COMI.json")))
        val merged = client().sessions(EgxFixtures.COMI)!!.merged()
        assertEquals(138, merged.size)
        val withOpen = merged.filter { it.open != null }.map { it.row.day }
        assertEquals(listOf(day(2026, 11, 5), day(2026, 11, 8), day(2026, 11, 9), day(2026, 11, 10), day(2026, 11, 11), day(2026, 11, 12)), withOpen)
    }

    @Test
    fun aSymbolYahooDoesNotKnowIsNull() = runTest {
        server.enqueue(status(404))
        assertNull(client().daily(EgxFixtures.COMI))
    }

    @Test
    fun retriesServerErrorsAndWaitsOutRateLimits() = runTest {
        server.enqueue(status(503))
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").body("").build())
        server.enqueue(ok(EgxFixtures.text("hourly_COMI.json")))
        assertEquals(30, client().hourly(EgxFixtures.COMI)!!.bars.size)
        assertEquals(3, server.requestCount)
        assertTrue(7_000L in sleeps)
    }

    @Test
    fun givesUpAfterFourFailuresAndFailsAtOnceOnARefusal() = runTest {
        repeat(4) { server.enqueue(status(500)) }
        assertFailsWith<EgxDataException.Http> { client().hourly(EgxFixtures.COMI) }
        assertEquals(4, server.requestCount)
        server.enqueue(status(403))
        val e = assertFailsWith<EgxDataException.Http> { client().hourly(EgxFixtures.COMI) }
        assertEquals(403, e.code)
        assertEquals(5, server.requestCount)
    }
}
