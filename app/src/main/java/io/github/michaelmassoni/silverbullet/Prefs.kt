package io.github.michaelmassoni.silverbullet

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/** Small wrapper over SharedPreferences for the handful of settings the app has. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sb", Context.MODE_PRIVATE)

    /** Base URL of the SilverBullet server, or null before first-run setup. */
    var server: String?
        get() = sp.getString(KEY_SERVER, null)
        set(v) = sp.edit { putString(KEY_SERVER, v?.let(::normalizeServer)) }

    /**
     * Page that quick captures are appended to, without `.md`. `{date}` becomes today's date
     * (YYYY-MM-DD), so the default matches SilverBullet's usual `Journal/2026-01-31` daily pages.
     */
    var capturePage: String
        get() = sp.getString(KEY_CAPTURE_PAGE, DEFAULT_CAPTURE_PAGE)!!
        set(v) = sp.edit {
            putString(KEY_CAPTURE_PAGE, v.trim().trim('/').removeSuffix(".md").ifEmpty { DEFAULT_CAPTURE_PAGE })
        }

    /** Optional SB_AUTH_TOKEN for background capture sync; falls back to the WebView login cookie. */
    var token: String
        get() = sp.getString(KEY_TOKEN, "")!!
        set(v) = sp.edit { putString(KEY_TOKEN, v.trim()) }

    /** Last page the WebView showed, so a cold start reopens where you left off. */
    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(v) = sp.edit { putString(KEY_LAST_URL, v) }

    /** Unsent text from a capture dialog that was dismissed by tapping outside / back. */
    var draft: String
        get() = sp.getString(KEY_DRAFT, "")!!
        set(v) = sp.edit { putString(KEY_DRAFT, v) }

    var askedNotifications: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIF, false)
        set(v) = sp.edit { putBoolean(KEY_ASKED_NOTIF, v) }

    companion object {
        const val DEFAULT_CAPTURE_PAGE = "Journal/{date}"
        private const val KEY_SERVER = "server"
        private const val KEY_CAPTURE_PAGE = "capture_page"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_DRAFT = "draft"
        private const val KEY_ASKED_NOTIF = "asked_notif"

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }

        /** Resolve a capture page pattern for a given day, e.g. "Journal/{date}" → "Journal/2026-11-04". */
        fun resolvePage(pattern: String, day: LocalDate): String = pattern.replace("{date}", day.toString())

        /** True for origins where the browser allows service workers (and so offline mode). */
        fun isSecureOrigin(server: String): Boolean {
            val host = android.net.Uri.parse(server).host ?: return false
            return server.startsWith("https://") || host == "localhost" || host == "127.0.0.1"
        }
    }
}
