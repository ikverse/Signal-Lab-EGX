package com.ikverse.signallab.egx.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.DailyRow
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.CopyRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.engine.egx.EgxStrategies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val thu = EgxTime.day(2026, 11, 12)
private val calm = EgxStrategies.calm.id

/** The phone's data for the tools, held in memory. */
private class Data : EgxAnalystData {
    val calls = listOf(CallRow(thu, calm, "COMI", "buy", 100, 10_000.0, 100.0, 99.0, 100.0, 92.0, 110.0, 120.0, "close", null, null, "monthly_list", true))
    override suspend fun latestCalls() = calls
    override suspend fun callsSince(day: Long) = calls.filter { it.day >= day }
    override suspend fun days(record: String) = listOf(PaperDayRow(calm, record, thu - EgxTime.DAY_MS, 33_000.0, 0.0, 0.0), PaperDayRow(calm, record, thu, 34_650.0, 0.5, 0.0))
    override suspend fun yardsticks() = listOf(YardstickRow(calm, thu, 1.01, 1.03, 0.98, 1.0, 1.02))
    override suspend fun fills() = listOf(PaperFillRow(calm, Records.LEVELS, thu, "COMI", true, 100.0, 100.0, "", 2.5))
    override suspend fun copies() = emptyList<CopyRow>()
    override suspend fun daily(code: String) = if (code == "COMI") List(80) { DailyRow(thu - (79 - it) * EgxTime.DAY_MS, 1.0, 1.0, 90.0 + it * 0.1, 1.0) } else emptyList()
    override suspend fun dividends() = listOf(EgxDividend(code = "EAST", amount = 1.6, lastCumDay = thu + 20 * EgxTime.DAY_MS))
    override suspend fun egx30() = listOf(thu to 41_000.0)
    override fun prefs() = EgxPrefs(liveStart = thu - EgxTime.DAY_MS)
    override fun name(code: String) = if (code == "COMI") "Commercial International Bank" else null
    override fun today() = thu
}

/** Seals nothing: the tests have no Android Keystore. */
private object Plain : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

/** The PC host, played by a mock server: one run that calls the phone's "stock" tool, then answers. */
private class FakeHost : Dispatcher() {
    val toolAnswers: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    var spec: JSONObject? = null
    private val answered = java.util.concurrent.CountDownLatch(1)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path == "/hello") return json(JSONObject().put("app", HostClient.HOST_APP).put("protocol", 1).put("claude", "2.1.0"))
        if (request.headers["Authorization"] != "Bearer 123-456") return MockResponse.Builder().code(401).body("{\"error\":\"Wrong pairing code.\"}").build()
        return when {
            path == "/check" -> json(JSONObject().put("ok", true))
            path == "/runs" -> {
                spec = JSONObject(request.body!!.utf8())
                // The answer to the tool call arrives on another request while this stream waits for it.
                val events = StringBuilder()
                events.append("data: ").append(JSONObject().put("type", "run").put("id", "r1")).append("\n\n")
                events.append("data: ").append(JSONObject().put("type", "tool").put("id", "c1").put("name", "stock").put("input", JSONObject().put("code", "comi").put("sessions", 5))).append("\n\n")
                MockResponse.Builder().code(200).body(events.toString() + afterTool()).build()
            }
            path == "/runs/r1/tools/c1" -> {
                toolAnswers += JSONObject(request.body!!.utf8())
                answered.countDown()
                json(JSONObject().put("ok", true))
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    // MockWebServer sends a body at once, so the rest of the stream is written up front; the phone answers the tool as it reads.
    private fun afterTool(): String =
        "data: " + JSONObject().put("type", "text").put("delta", "CIB closed at 97.90.") + "\n\n" +
            "data: " + JSONObject().put("type", "done").put("ending", "done").put("answer", "CIB closed at 97.90.") + "\n\n"

    fun waitForTool() = answered.await(5, TimeUnit.SECONDS)

    private fun json(o: JSONObject) = MockResponse.Builder().code(200).body(o.toString()).build()
}

@RunWith(RobolectricTestRunner::class)
class EgxAnalystTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val host = FakeHost()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = host
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun pairing(dir: File) = HostPairing(
        context.getSharedPreferences("egx-analyst-test-${dir.name}", Context.MODE_PRIVATE), File(dir, "code.bin"), Plain,
        clientFor = { _, _ -> OkHttpClient() }, scheme = "http",
    )

    private val address get() = "${server.hostName}:${server.port}"

    @Test
    fun pairingChecksTheCodeAndKeepsItSealed() = runBlocking {
        val dir = File(context.filesDir, "a").apply { mkdirs() }
        val p = pairing(dir)
        assertNull(p.paired())
        assertFailsWith<HostException> { p.probe(address, "999") }
        val probe = p.probe(address, " 123-456 ")
        assertEquals("2.1.0", probe.claude)
        assertNull(p.paired(), "nothing is saved before the owner confirms")
        p.confirm(probe)
        assertEquals(PairedHost(address, "PLAIN-HTTP-TEST", "123-456"), p.paired())
        assertTrue("123-456" !in File(dir, "code.bin").readText(), "the code is not stored in the clear")
        p.forget()
        assertNull(p.paired())
    }

    @Test
    fun aQuestionRunsOnThePcWithTheToolsAnsweredByThePhone() = runBlocking {
        val dir = File(context.filesDir, "b").apply { mkdirs() }
        val p = pairing(dir)
        p.confirm(p.probe(address, "123-456"))
        val saved = ArrayList<Triple<String, String, String>>()
        val analyst = EgxAnalyst(p, EgxAnalystTools(Data()), CoroutineScope(SupervisorJob() + Dispatchers.IO)) { q, a, e -> saved += Triple(q, a, e) }
        analyst.ask("How is CIB doing?", arabic = true)
        withTimeout(10_000) { analyst.state.first { it is AskState.Done || it is AskState.Failed } }
        val done = assertIs<AskState.Done>(analyst.state.value)
        assertEquals("CIB closed at 97.90.", done.answer)
        assertTrue(host.waitForTool())
        val tool = JSONObject(host.toolAnswers.single().getString("text"))
        assertEquals("COMI", tool.getString("code"), "the ticker is read in capitals")
        assertEquals(5, tool.getJSONArray("closes").length())
        assertEquals(1, tool.getJSONArray("paper_trades").length())

        val spec = host.spec!!
        assertEquals(EgxAnalystPrompt.MODEL, spec.getString("model"))
        assertTrue(spec.getString("system").contains("Egyptian Arabic"))
        assertEquals(listOf("ledger", "calls", "stock", "dividends", "research"), (0 until spec.getJSONArray("tools").length()).map { spec.getJSONArray("tools").getJSONObject(it).getString("name") })
        assertEquals(listOf(Triple("How is CIB doing?", "CIB closed at 97.90.", "done")), saved)
        assertEquals(2, analyst.conversation.size)
        analyst.newConversation()
        assertEquals(0, analyst.conversation.size)
    }

    @Test
    fun withNoPairedPcTheAnalystSaysSo() = runBlocking {
        val analyst = EgxAnalyst(pairing(File(context.filesDir, "c").apply { mkdirs() }), EgxAnalystTools(Data()), CoroutineScope(SupervisorJob() + Dispatchers.IO)) { _, _, _ -> }
        analyst.ask("Anything?", arabic = false)
        withTimeout(5_000) { analyst.state.first { it is AskState.Failed } }
        assertEquals("Pair with the analyst host on your PC first.", (analyst.state.value as AskState.Failed).message)
    }

    @Test
    fun everyToolAnswersInJsonAndAnUnknownOneIsAnError() = runBlocking {
        val tools = EgxAnalystTools(Data())
        val ledger = JSONObject(tools.run("ledger", JSONObject()).text)
        val s = (0 until ledger.getJSONArray("strategies").length()).map { ledger.getJSONArray("strategies").getJSONObject(it) }.single { it.getString("id") == calm }
        assertEquals(0.05, s.getDouble("growth"), 1e-9)
        assertEquals(0.03, s.getDouble("egx30_growth"), 1e-9)
        assertEquals("2026-11-12", org.json.JSONArray(tools.run("calls", JSONObject()).text).getJSONObject(0).getString("made_after_session"))
        assertEquals("EAST", JSONObject(tools.run("dividends", JSONObject()).text).getJSONArray("upcoming").getJSONObject(0).getString("code"))
        assertEquals(EgxStrategies.triedCount, JSONObject(tools.run("research", JSONObject()).text).getInt("versions_tried"))
        assertTrue(tools.run("stock", JSONObject().put("code", "NOPE")).isError)
        assertTrue(tools.run("place_order", JSONObject()).isError)
    }
}
