package com.ikverse.signallab.data.egx

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Why a download of Egyptian data failed. */
sealed class EgxDataException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Network(cause: Throwable?) : EgxDataException("No connection: ${cause?.message ?: "unknown"}", cause)
    class Http(val code: Int, body: String) : EgxDataException("HTTP $code: $body")
    class RateLimited(val waitMs: Long) : EgxDataException("Rate limited; asked to wait ${waitMs / 1000}s")
    class BadResponse(snippet: String, cause: Throwable? = null) : EgxDataException("Unreadable answer: $snippet", cause)
}

/**
 * Plain GET requests for the Egyptian sources (Yahoo's chart API, Arab Finance's news pages), paced so the evening pass
 * stays one polite stream of calls, and retried where a retry can help. Neither source needs a key or an account.
 */
class EgxHttp(
    private val http: OkHttpClient = defaultHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Where answers are read: never a screen's thread. */
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** The research paused 0.4 s between Yahoo calls and was never refused. */
    private val pauseMs: Long = 400,
    private val maxAttempts: Int = 4,
) {
    private val paceLock = Mutex()
    private var lastCallAt = 0L

    /** The body of the first 200 answer, or null when the source says 404 (Yahoo: no such symbol). */
    suspend fun get(url: HttpUrl, accept: String = "application/json"): String? = withContext(io) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .build()
        var attempt = 0
        var lastFailure: EgxDataException = EgxDataException.Network(null)
        while (attempt < maxAttempts) {
            pace()
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                lastFailure = EgxDataException.Network(e)
                attempt++
                sleep(backoffMs(attempt))
                continue
            }
            response.use { r ->
                val body = r.body.string()
                when {
                    r.code == 200 -> return@withContext body
                    r.code == 404 -> return@withContext null
                    r.code == 429 -> {
                        val wait = (r.header("Retry-After")?.toLongOrNull() ?: 30L) * 1000
                        lastFailure = EgxDataException.RateLimited(wait)
                        attempt++
                        sleep(wait)
                    }
                    r.code >= 500 -> {
                        lastFailure = EgxDataException.Http(r.code, body.take(200))
                        attempt++
                        sleep(backoffMs(attempt))
                    }
                    else -> throw EgxDataException.Http(r.code, body.take(200))
                }
            }
        }
        throw lastFailure
    }

    private fun backoffMs(attempt: Int): Long = 1000L shl (attempt - 1).coerceAtMost(5)

    private suspend fun pace() = paceLock.withLock {
        val wait = lastCallAt + pauseMs - clock()
        if (wait > 0) sleep(wait)
        lastCallAt = clock()
    }

    companion object {
        /** Yahoo turns away clients that do not look like a browser; the research used this one. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
    cont.invokeOnCancellation { cancel() }
}
