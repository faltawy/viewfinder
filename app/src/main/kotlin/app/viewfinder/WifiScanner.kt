package app.viewfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class NearbyWifi(
    val ssid: String,
    /** Signal in dBm; closer to 0 is stronger. */
    val level: Int,
    val secured: Boolean,
    val isCamera: Boolean,
) {
    /** 0..3 bars for the signal icon. */
    val bars: Int get() = when {
        level >= -55 -> 3
        level >= -67 -> 2
        level >= -78 -> 1
        else -> 0
    }
}

/**
 * Lists nearby Wi-Fi networks so the user can pick the camera's instead of typing its name.
 * Needs NEARBY_WIFI_DEVICES (Android 13+) or location permission (Android 10–12).
 */
class WifiScanner(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * Asks for a fresh scan and returns the results, cameras first, then by signal.
     * Android throttles scans (four per two minutes); when it refuses, the last results are returned.
     */
    suspend fun scan(): List<NearbyWifi> {
        // On timeout this falls through to whatever results Android has cached.
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                @Suppress("DEPRECATION") // still the only way to request a scan
                val started = runCatching { wifi.startScan() }.getOrDefault(false)
                if (!started) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        return results()
    }

    private fun results(): List<NearbyWifi> {
        val raw = runCatching { wifi.scanResults }.getOrDefault(emptyList())
        return raw
            .mapNotNull { r -> ssidOf(r)?.let { ssid -> NearbyWifi(ssid, r.level, isSecured(r), looksLikeCamera(ssid)) } }
            .groupBy { it.ssid }
            .map { (_, same) -> same.maxBy { it.level } }
            .sortedWith(compareByDescending<NearbyWifi> { it.isCamera }.thenByDescending { it.level })
    }

    @Suppress("DEPRECATION") // ScanResult.SSID is plain text; wifiSsid needs API 33 and quotes its value
    private fun ssidOf(r: ScanResult): String? = r.SSID?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    private fun isSecured(r: ScanResult): Boolean =
        listOf("WPA", "WEP", "SAE", "PSK", "EAP").any { r.capabilities.orEmpty().contains(it) }

    companion object {
        private const val SCAN_TIMEOUT_MS = 8_000L

        /**
         * Camera access points name themselves after the model: Nikon "D5600_1234567", "Z50_…",
         * "Zf_…", "COOLPIX…"; Canon "EOS…", Sony "DIRECT-…", Fujifilm "FUJIFILM-…".
         */
        private val cameraPatterns = listOf(
            Regex("""^D\d{3,4}[A-Z]?_\w+""", RegexOption.IGNORE_CASE),
            Regex("""^Z_?[0-9a-z]{1,4}(II|III)?_\w+""", RegexOption.IGNORE_CASE),
            Regex("""^(COOLPIX|KeyMission|NIKON)""", RegexOption.IGNORE_CASE),
            Regex("""^(EOS|Canon)""", RegexOption.IGNORE_CASE),
            Regex("""^DIRECT-\w+:?(ILCE|DSC|ZV)""", RegexOption.IGNORE_CASE),
            Regex("""^FUJIFILM""", RegexOption.IGNORE_CASE),
            Regex("""^(OM-|OLYMPUS|GR_|PENTAX|LUMIX)""", RegexOption.IGNORE_CASE),
        )

        fun looksLikeCamera(ssid: String): Boolean = cameraPatterns.any { it.containsMatchIn(ssid) }
    }
}
