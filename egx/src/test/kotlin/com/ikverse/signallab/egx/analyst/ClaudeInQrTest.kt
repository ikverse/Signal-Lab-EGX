package com.ikverse.signallab.egx.analyst

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GOOD = "AB0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCD"
private const val CODE = "ABCDE-FGH23"

private fun qrText(addresses: List<String>, port: Int = 8790, code: String = CODE, fingerprint: String = GOOD, version: Int = 1) =
    JSONObject().put("claudein", version).put("addresses", JSONArray(addresses)).put("port", port).put("code", code).put("fingerprint", fingerprint).toString()

private object Reversed : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

/** ClaudeIn, as far as pairing goes: `/hello` for anyone, `/check` with the one code it made. */
private class FakeClaudeIn : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "/hello" -> json(JSONObject().put("app", HostClient.HOST_APP).put("protocol", 1).put("claude", "2.1.0").put("service", "ClaudeIn"))
        "/check" -> if (request.headers["Authorization"] == "Bearer $CODE") json(JSONObject().put("ok", true))
            else MockResponse.Builder().code(401).body("{\"error\":\"Wrong pairing code.\"}").build()
        else -> MockResponse.Builder().code(404).build()
    }

    private fun json(o: JSONObject) = MockResponse.Builder().code(200).body(o.toString()).build()
}

@RunWith(RobolectricTestRunner::class)
class ClaudeInQrTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = FakeClaudeIn()
        server.start()
    }

    @After
    fun stop() = server.close()

    /** Plain HTTP stands in for TLS: a client pinned to [GOOD] connects, any other pin fails as a wrong certificate does. */
    private fun pairing(name: String) = HostPairing(
        context.getSharedPreferences("claudein-qr-$name", Context.MODE_PRIVATE), File(File(context.filesDir, name).apply { mkdirs() }, "code.bin"), Reversed,
        clientFor = { pin, _ ->
            if (pin == GOOD) OkHttpClient()
            else OkHttpClient.Builder().addInterceptor { throw IOException("handshake failed", CertificateException("This is not the PC this phone paired with.")) }.build()
        },
        scheme = "http",
    )

    private val address get() = "${server.hostName}:${server.port}"

    @Test
    fun readsClaudeInsPairingCode() {
        val qr = ClaudeInQr.parse(qrText(listOf("192.168.1.20", "100.101.102.103")))
        assertEquals(listOf("192.168.1.20", "100.101.102.103"), qr.addresses)
        assertEquals(8790, qr.port)
        assertEquals(CODE, qr.code)
        assertEquals(GOOD, qr.fingerprint)
        assertEquals(listOf("100.101.102.103", "192.168.1.20"), qr.ordered(), "Tailscale's address is tried first")
        assertEquals(GOOD, ClaudeInQr.parse(qrText(listOf("192.168.1.20"), fingerprint = GOOD.lowercase())).fingerprint)
    }

    @Test
    fun refusesWhatIsNotClaudeInsPairingCode() {
        for (text in listOf("https://example.com", "{}", JSONObject().put("claudein", 0).toString())) {
            assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(text) }.message!!.contains("not ClaudeIn's pairing code"), text)
        }
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(listOf("192.168.1.20"), version = 2)) }.message!!.contains("newer ClaudeIn"))
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(listOf("192.168.1.20"), code = "")) }.message!!.contains("no pairing code"))
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(emptyList())) }.message!!.contains("no address"))
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(listOf("my pc/x"))) }.message!!.contains("not one"))
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(listOf("192.168.1.20"), port = 0)) }.message!!.contains("port"))
        assertTrue(assertFailsWith<HostException> { ClaudeInQr.parse(qrText(listOf("192.168.1.20"), fingerprint = "ABC")) }.message!!.contains("fingerprint"))
    }

    @Test
    fun tellsTailscaleAddressesApart() {
        assertTrue(ClaudeInQr.isTailscale("100.64.0.1"))
        assertTrue(ClaudeInQr.isTailscale("100.127.255.254:8790"))
        assertTrue(!ClaudeInQr.isTailscale("100.128.0.1"))
        assertTrue(!ClaudeInQr.isTailscale("192.168.1.20"))
        assertTrue(!ClaudeInQr.isTailscale("my-pc.local"))
    }

    @Test
    fun aScanPinsTheQrsCertificateAndPairs() = runBlocking {
        val p = pairing("good")
        val probe = p.probeScanned(ClaudeInQr.parse(qrText(listOf(address))))
        assertEquals(address, probe.address)
        assertEquals("2.1.0", probe.claude)
        assertNull(p.paired(), "nothing is saved before confirm")
        p.confirm(probe)
        assertEquals(PairedHost(address, GOOD, CODE), p.paired())
    }

    @Test
    fun aPcWithAnotherCertificateIsRefused() = runBlocking {
        val p = pairing("other")
        val other = "CD" + GOOD.drop(2)
        val e = assertFailsWith<HostException> { p.probeScanned(ClaudeInQr.parse(qrText(listOf(address), fingerprint = other))) }
        assertTrue(e.message!!.contains("certificate is different"), e.message)
        assertNull(p.paired())
    }

    @Test
    fun theNextAddressIsTriedWhenTheFirstDoesNotAnswer() = runBlocking {
        val p = pairing("next")
        // Port 1 on this machine has nothing listening, so the first address fails at once.
        val probe = p.probeScanned(ClaudeInQr.parse(qrText(listOf("127.0.0.1:1", address))))
        assertEquals(address, probe.address)
    }

    @Test
    fun aWrongOrExpiredCodeSaysSo() = runBlocking {
        val p = pairing("code")
        val e = assertFailsWith<HostException> { p.probeScanned(ClaudeInQr.parse(qrText(listOf(address), code = "ZZZZZ-ZZZZZ"))) }
        assertTrue(e.message!!.contains("refused the code"), e.message)
    }

    @Test
    fun noAnswerAnywhereNamesTheAddressesTried() = runBlocking {
        val p = pairing("none")
        val e = assertFailsWith<HostException> { p.probeScanned(ClaudeInQr.parse(qrText(listOf("127.0.0.1"), port = 1))) }
        assertTrue(e.message!!.contains("127.0.0.1:1"), e.message)
    }
}
