package app.viewfinder

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.SecureRandom

/** Small things the app remembers between launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("viewfinder", Context.MODE_PRIVATE)

    /** This phone's PTP/IP identity. Cameras remember accepted phones by it, so it never changes. */
    val guid: ByteArray
        get() {
            prefs.getString(KEY_GUID, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_GUID, Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
            return fresh
        }

    var wifiName: String
        get() = prefs.getString("wifi_name", "") ?: ""
        set(v) = prefs.edit().putString("wifi_name", v).apply()

    var wifiPassword: String
        get() = prefs.getString("wifi_password", "") ?: ""
        set(v) = prefs.edit().putString("wifi_password", v).apply()

    /** Empty means "find it from the Wi-Fi network". */
    var cameraAddress: String
        get() = prefs.getString("camera_address", "") ?: ""
        set(v) = prefs.edit().putString("camera_address", v).apply()

    /** Each camera network's password, so switching between cameras needs no retyping. */
    fun passwordFor(ssid: String): String? = prefs.getString("pw:$ssid", null)

    fun rememberPassword(ssid: String, password: String) = prefs.edit().putString("pw:$ssid", password).apply()

    /** The model of the last camera that connected, so the next launch can reconnect to it by itself. */
    var lastCamera: String?
        get() = prefs.getString("last_camera", null)
        set(v) = prefs.edit().putString("last_camera", v).apply()

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", false)
        set(v) = prefs.edit().putBoolean("auto_save", v).apply()

    /** The share panel's choices, kept as the defaults for the next one-tap share. */
    var shareOptions: ShareOptions
        get() {
            val d = ShareOptions()
            return ShareOptions(
                singleFormat = enumOr(prefs.getString("share_format_single", null), d.singleFormat),
                bulkFormat = enumOr(prefs.getString("share_format_bulk", null), d.bulkFormat),
                size = enumOr(prefs.getString("share_size", null), d.size),
                quality = enumOr(prefs.getString("share_quality", null), d.quality),
                crop = enumOr(prefs.getString("share_crop", null), d.crop),
                card = prefs.getBoolean("share_card", d.card),
                removeLocation = prefs.getBoolean("share_remove_location", d.removeLocation),
                removeDetails = prefs.getBoolean("share_remove_details", d.removeDetails),
            )
        }
        set(v) = prefs.edit()
            .putString("share_format_single", v.singleFormat.name)
            .putString("share_format_bulk", v.bulkFormat.name)
            .putString("share_size", v.size.name)
            .putString("share_quality", v.quality.name)
            .putString("share_crop", v.crop.name)
            .putBoolean("share_card", v.card)
            .putBoolean("share_remove_location", v.removeLocation)
            .putBoolean("share_remove_details", v.removeDetails)
            .apply()

    /** The app the last share went to (a flattened ComponentName), for the panel's "Last used" chip. */
    var lastShareApp: String?
        get() = prefs.getString("share_last_app", null)
        set(v) = prefs.edit().putString("share_last_app", v).apply()

    /** Lessons finished, by [app.viewfinder.learn.Lesson.id]. */
    var learnDone: Set<String>
        get() = prefs.getStringSet("learn_done", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("learn_done", v).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    /** True for files saved before Uris were kept (key only) and after (key → Uri). */
    fun isSaved(key: String): Boolean =
        prefs.contains(KEY_URI + key) || key in (prefs.getStringSet(KEY_SAVED, emptySet()) ?: emptySet())

    /** Where the saved copy lives; null for files saved before Uris were kept. */
    fun savedUri(key: String): Uri? = prefs.getString(KEY_URI + key, null)?.let(Uri::parse)

    fun markSaved(key: String, uri: Uri) {
        val saved = prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty() + key
        prefs.edit().putStringSet(KEY_SAVED, saved).putString(KEY_URI + key, uri.toString()).apply()
    }

    private companion object {
        const val KEY_GUID = "ptpip_guid"
        const val KEY_SAVED = "saved_files"
        const val KEY_URI = "saved_uri:"
    }
}
