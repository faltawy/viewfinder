package app.viewfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the camera's Wi-Fi and keeps Android on it. A camera's network has no internet, so without
 * an explicit request Android routes sockets over mobile data and may drop the Wi-Fi altogether.
 */
class CameraNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * With [ssid], asks Android to join that network (the system shows a confirmation sheet).
     * Without it, takes whichever Wi-Fi the phone is on. Returns null when there is none.
     */
    suspend fun acquire(ssid: String, passphrase: String): Network? {
        release()
        val byName = ssid.isNotBlank()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply {
                if (byName) {
                    setNetworkSpecifier(
                        WifiNetworkSpecifier.Builder()
                            .setSsid(ssid.trim())
                            .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                            .build(),
                    )
                }
            }
            .build()
        val network = withTimeoutOrNull(if (byName) 90_000L else 4_000L) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
            }
        }
        if (network == null) release()
        return network
    }

    /** The camera's address on its own network: the router of that network, which is the camera. */
    fun cameraAddress(network: Network): String? {
        val lp = cm.getLinkProperties(network) ?: return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress?.let { return it }
        if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress?.let { return it }
        return null
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
