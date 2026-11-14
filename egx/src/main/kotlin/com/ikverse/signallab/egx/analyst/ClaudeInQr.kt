package com.ikverse.signallab.egx.analyst

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What ClaudeIn's "Pair a device…" window puts in its QR code (`qr_payload` in ClaudeIn's `windows.py`):
 * `{"claudein":1,"addresses":["192.168.1.20","100.101.102.103"],"port":8790,"code":"ABCDE-FGH23","fingerprint":"<SHA-256>"}`.
 * The fingerprint is the full SHA-256 of the PC's certificate, so a scan pins it at once, with nothing to compare by eye.
 */
data class ClaudeInQr(val addresses: List<String>, val port: Int, val code: String, val fingerprint: String) {
    /** The addresses to try, Tailscale's first: it works at home and away, so it is the one worth keeping. */
    fun ordered(): List<String> = addresses.sortedByDescending { isTailscale(it) }

    companion object {
        const val VERSION = 1

        /** Reads a scanned text; a [HostException] says in a sentence why it is not ClaudeIn's pairing code. */
        fun parse(text: String): ClaudeInQr {
            val notOurs = HostException("This QR code is not ClaudeIn's pairing code. Use the one in ClaudeIn's \"Pair a device…\" window.")
            val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: throw notOurs
            val version = o.optInt("claudein", 0)
            if (version == 0) throw notOurs
            if (version > VERSION) throw HostException("This QR code is from a newer ClaudeIn. Update Signal Lab EGX, or type the address and code.")
            val list = o.optJSONArray("addresses")
            val addresses = (0 until (list?.length() ?: 0)).map { list!!.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
            if (addresses.isEmpty()) throw HostException("The QR code has no address for the PC. Is the PC on a network?")
            val port = o.optInt("port", 0)
            if (port !in 1..65535) throw HostException("The QR code has no usable port.")
            if (addresses.any { HostClient.baseUrl(it.withPort(port)) == null }) throw HostException("The QR code has an address that is not one.")
            val code = o.optString("code").trim()
            if (code.isEmpty() || code.any { !it.isLetterOrDigit() && it != '-' && it != ' ' }) throw HostException("The QR code has no pairing code.")
            val fingerprint = o.optString("fingerprint").trim().uppercase()
            if (!Regex("[0-9A-F]{64}").matches(fingerprint)) throw HostException("The QR code has no certificate fingerprint.")
            return ClaudeInQr(addresses, port, code, fingerprint)
        }

        /** Tailscale gives every device an address in 100.64.0.0/10. */
        fun isTailscale(address: String): Boolean {
            val parts = address.substringBefore(':').split('.').map { it.toIntOrNull() ?: return false }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }

        /** "192.168.1.20" with the QR's port, unless the address already names one. */
        internal fun String.withPort(port: Int): String = if (contains(':')) this else "$this:$port"
    }
}

/**
 * Opens Google's QR scanning screen (Play Services, so this app needs no camera permission) and gives what it read, or
 * null when the owner backed out.
 */
suspend fun scanQr(context: Context): String? = suspendCancellableCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        .addOnFailureListener { if (cont.isActive) cont.resumeWithException(HostException("The QR scanner could not start (${it.message}). Type the address and code instead.")) }
}
