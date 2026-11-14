package com.ikverse.signallab.egx.analyst

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// The phone's side of the analyst host (analyst-host/ in this repository), copied from the crypto app's
// `analyst/HostClient.kt` and `analyst/HostPairing.kt`: Signal Lab EGX depends on the engine only, never on the crypto app.

/** The analyst host on the owner's PC could not be reached or refused, in a sentence (English; the screen translates). */
class HostException(message: String) : IOException(message)

/**
 * Claude Code on the owner's PC, signed in with the owner's own account. The host speaks HTTPS with a certificate it made
 * itself; the phone trusts exactly the one it pinned when it paired, and sends the pairing code with every request.
 */
class HostClient(private val base: String, private val code: String, private val http: OkHttpClient) {
    private fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $code")

    private suspend fun call(r: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val text = resp.body.string()
            if (resp.code == 401) throw HostException("The PC refused the pairing code. Pair again.")
            if (!resp.isSuccessful) throw HostException(runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "The PC answered ${resp.code}.")
            JSONObject(text)
        }
    }

    suspend fun hello(): JSONObject = call(Request.Builder().url("$base/hello").build())

    /** Whether the host answers and takes the code within four seconds; a failure says why. */
    suspend fun verify() {
        val quick = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        val answer = try {
            withContext(Dispatchers.IO) { quick.newCall(request("/check").build()).execute().use { it.code to it.body.string() } }
        } catch (e: InterruptedIOException) {
            throw HostException("The PC did not answer within four seconds.")
        } catch (e: IOException) {
            val certificate = generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
            throw HostException(if (certificate) "This is not the PC this phone paired with." else "The PC could not be reached.")
        }
        val (status, body) = answer
        if (status == 401) throw HostException("The PC refused the pairing code. Check it in the PC's window.")
        if (status !in 200..299) throw HostException("The PC answered $status.")
        if (!runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)) throw HostException("The PC did not accept the check.")
    }

    /** Asks one question ([spec]) and hands each streamed event to [onEvent] until the run is done; cancelling stops it on the PC. */
    suspend fun run(spec: JSONObject, onEvent: suspend (JSONObject) -> Unit) {
        val r = request("/runs").post(spec.toString().toRequestBody(JSON)).build()
        val call: Call = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(r)
        var runId: String? = null
        try {
            val resp = withContext(Dispatchers.IO) { call.execute() }
            resp.use {
                if (resp.code == 401) throw HostException("The PC refused the pairing code. Pair again.")
                if (resp.code == 409) throw HostException("The PC is answering another question.")
                if (!resp.isSuccessful) throw HostException("The PC answered ${resp.code}.")
                val source = resp.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    if (!line.startsWith("data: ")) continue
                    val e = JSONObject(line.removePrefix("data: "))
                    if (e.optString("type") == "run") runId = e.optString("id")
                    onEvent(e)
                    if (e.optString("type") == "done") return
                }
            }
            throw HostException("The PC stopped answering.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            runId?.let { id -> withContext(NonCancellable) { runCatching { stop(id) } } }
            throw e
        }
    }

    suspend fun answer(runId: String, callId: String, text: String, isError: Boolean) {
        call(request("/runs/$runId/tools/$callId").post(JSONObject().put("text", text).put("is_error", isError).toString().toRequestBody(JSON)).build())
    }

    suspend fun stop(runId: String) {
        call(request("/runs/$runId/stop").post("{}".toRequestBody(JSON)).build())
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val PORT = 8765
        const val HOST_APP = "Signal Lab analyst host"

        /** "192.168.1.20", "192.168.1.20:8765" or "my-pc.local" as the host's address, or null when it is not one. */
        fun baseUrl(address: String, scheme: String = "https"): String? {
            val a = address.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (a.isEmpty() || a.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            val withPort = if (a.contains(':')) a else "$a:$PORT"
            val port = withPort.substringAfterLast(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return "$scheme://$withPort"
        }

        fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

        /** Trusts exactly the certificate whose SHA-256 is [pin]; with no pin (while pairing) any, telling [seen] which. */
        fun pinned(pin: String?, seen: (String) -> Unit = {}): OkHttpClient {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a client.")
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val fp = fingerprint(chain.firstOrNull() ?: throw CertificateException("No certificate."))
                    seen(fp)
                    if (pin != null && fp != pin) throw CertificateException("This is not the PC this phone paired with.")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            return OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, trust)
                // The pinned certificate is the identity; the address can change with the network.
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Seals small secrets; on the phone with a key in Android's Keystore that cannot be exported. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-GCM with a Keystore key of the EGX app's own; the output is the 12-byte nonce, then the ciphertext. */
class KeystoreCipher(private val alias: String = "signal_lab_egx_analyst_host") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** The PC this phone is paired with. */
data class PairedHost(val address: String, val pin: String, val code: String)

/** What the first contact with a PC found, for the owner to confirm against the PC's window. */
data class HostProbe(val address: String, val code: String, val fingerprint: String, val claude: String?) {
    val shortFingerprint: String get() = fingerprint.takeLast(6)
}

/**
 * Pairing with the analyst host. The address and pinned certificate are kept in the app's own preferences (the app makes
 * no backups); the pairing code is a secret, sealed in the no-backup folder.
 */
class HostPairing(
    private val prefs: SharedPreferences,
    private val codeFile: File,
    private val cipher: SecretCipher,
    private val clientFor: (pin: String?, seen: (String) -> Unit) -> OkHttpClient = HostClient::pinned,
    private val scheme: String = "https",
) {
    fun paired(): PairedHost? {
        val address = prefs.getString(ADDRESS, null) ?: return null
        val pin = prefs.getString(PIN, null) ?: return null
        val code = if (codeFile.exists()) runCatching { String(cipher.decrypt(codeFile.readBytes()), Charsets.UTF_8) }.getOrNull() else null
        return PairedHost(address, pin, code ?: return null)
    }

    fun client(host: PairedHost): HostClient? = HostClient.baseUrl(host.address, scheme)?.let { HostClient(it, host.code, clientFor(host.pin) {}) }

    /** First contact: reads the PC's certificate and checks the code. Nothing is saved until [confirm]. */
    suspend fun probe(address: String, code: String): HostProbe {
        val base = HostClient.baseUrl(address, scheme) ?: throw HostException("That is not an address. Type it as the PC shows it, such as 192.168.1.20.")
        var seen: String? = null
        val hello = try {
            HostClient(base, code.trim(), clientFor(null) { seen = it }).hello()
        } catch (e: HostException) {
            throw e
        } catch (e: Exception) {
            throw HostException("No analyst host answered there. Is it running on the PC, and is the phone on the same network?")
        }
        if (hello.optString("app") != HostClient.HOST_APP) throw HostException("Something else answered at that address.")
        val fp = seen ?: if (scheme == "http") "PLAIN-HTTP-TEST" else throw HostException("The PC sent no certificate.")
        HostClient(base, code.trim(), clientFor(fp) {}).verify()
        return HostProbe(address.trim(), code.trim(), fp, hello.optString("claude").ifBlank { null })
    }

    fun confirm(p: HostProbe) {
        codeFile.parentFile?.mkdirs()
        codeFile.writeBytes(cipher.encrypt(p.code.toByteArray(Charsets.UTF_8)))
        prefs.edit().putString(ADDRESS, p.address).putString(PIN, p.fingerprint).apply()
    }

    fun forget() {
        codeFile.delete()
        prefs.edit().remove(ADDRESS).remove(PIN).apply()
    }

    companion object {
        const val ADDRESS = "host_address"
        const val PIN = "host_pin"
        const val FILE_NAME = "egx_analyst_host_code.bin"
    }
}
