package com.ikverse.signallab.egx.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A published release that can be installed over this app. */
data class Release(val version: AppVersion, val notes: String, val apkUrl: String, val apkBytes: Long)

sealed interface CheckResult {
    /** The newest release is this version or older. */
    data object UpToDate : CheckResult
    data class Newer(val release: Release) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** Where the app asks whether a newer version exists. Behind an interface so the rest can be tried without a network. */
interface UpdateSource {
    suspend fun check(installed: AppVersion): CheckResult
}

/**
 * Reads the latest release of the app's own GitHub repository. Draft and pre-release builds are not offered
 * (GitHub's "latest" never returns them), and a download is only ever followed to GitHub's own hosts.
 *
 * GitHub's API answers only 60 unsigned requests an hour from one internet address, which a shared home or mobile address
 * can use up without this app. When it refuses, the release page at [pageUrl] (not counted against that limit) is asked
 * instead: it points at the newest release's tag, and the app file's name follows from the tag the way the release
 * workflow names it. That way has no release notes.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val latestUrl: String = LATEST_URL,
    private val pageUrl: String = PAGE_URL,
    private val trustedHost: (HttpUrl) -> Boolean = ::isGitHubHost,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateSource {

    override suspend fun check(installed: AppVersion): CheckResult = withContext(io) {
        try {
            val request = Request.Builder().url(latestUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> CheckResult.Failed("No release of Signal Lab EGX has been published yet.")
                    response.code == 403 || response.code == 429 ->
                        fromReleasePage(installed) ?: CheckResult.Failed(limited(response.header("X-RateLimit-Reset")))
                    !response.isSuccessful -> CheckResult.Failed("GitHub answered with error ${response.code}. Try again later.")
                    else -> read(response.body.string(), installed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            CheckResult.Failed("Could not reach GitHub. Check the connection and try again.")
        }
    }

    private fun read(body: String, installed: AppVersion): CheckResult {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return CheckResult.Failed("GitHub's answer could not be read.")
        }
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return CheckResult.UpToDate
        val tag = json.optString("tag_name")
        val version = AppVersion.parse(tag) ?: return CheckResult.Failed("The newest release is named \"$tag\", which is not a version number, so it was skipped.")
        if (version <= installed) return CheckResult.UpToDate

        val assets = json.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.optString("name").startsWith("signal-lab", ignoreCase = true) } ?: apks.firstOrNull()
            ?: return CheckResult.Failed("Version $version is published but has no app file attached yet. Try again later.")

        val url = parseUrl(apk.optString("browser_download_url"))
        if (url == null || !trustedHost(url)) return CheckResult.Failed("The download for version $version points outside GitHub, so it was refused.")
        val size = apk.optLong("size", -1L)
        if (size <= 0L) return CheckResult.Failed("Version $version has an app file of unknown size, so it was refused.")
        return CheckResult.Newer(Release(version, json.optString("body").trim(), url.toString(), size))
    }

    /** The newest release as the release page points at it; null when that did not work either. */
    private fun fromReleasePage(installed: AppVersion): CheckResult? = try {
        val page = pageUrl.toHttpUrl()
        val tag = http.newBuilder().followRedirects(false).build()
            .newCall(Request.Builder().url(page).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (!response.isRedirect) return null
                val target = response.header("Location")?.let(page::resolve) ?: return null
                val segments = target.pathSegments
                val at = segments.indexOf("tag")
                if (at < 1 || segments[at - 1] != "releases" || at != segments.size - 2) return null
                segments.last()
            }
        val version = AppVersion.parse(tag)
        when {
            version == null || tag != "v$version" -> null
            version <= installed -> CheckResult.UpToDate
            else -> {
                val apk = page.newBuilder().removePathSegment(page.pathSize - 1)
                    .addPathSegment("download").addPathSegment(tag).addPathSegment("signal-lab-egx-$version.apk").build()
                if (!trustedHost(apk)) {
                    CheckResult.Failed("The download for version $version points outside GitHub, so it was refused.")
                } else {
                    val size = http.newCall(Request.Builder().url(apk).head().header("User-Agent", USER_AGENT).build()).execute().use { response ->
                        if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                    }
                    if (size <= 0L) null else CheckResult.Newer(Release(version, "", apk.toString(), size))
                }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** GitHub says when the limit lifts, as seconds since 1970; said as a local time when it does. */
    private fun limited(reset: String?): String {
        val seconds = reset?.trim()?.toLongOrNull()
        val time = seconds?.let { DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(it).atZone(zone())) }
        return if (time == null) "GitHub is limiting requests from this network. Try again in an hour."
        else "GitHub is limiting requests from this network. Try again after $time."
    }

    private fun parseUrl(text: String): HttpUrl? = try {
        text.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/ikverse/Signal-Lab-EGX/releases/latest"

        /** The same release as a web page, which redirects to `.../releases/tag/<tag>`. */
        const val PAGE_URL = "https://github.com/ikverse/Signal-Lab-EGX/releases/latest"

        private const val USER_AGENT = "SignalLab-Android"

        /** HTTPS, and a host GitHub serves release files from. */
        fun isGitHubHost(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "github.com" || url.host.endsWith(".github.com") || url.host.endsWith(".githubusercontent.com"))
    }
}
