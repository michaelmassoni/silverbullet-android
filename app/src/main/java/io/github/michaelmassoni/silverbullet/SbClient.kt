package io.github.michaelmassoni.silverbullet

import android.net.Uri
import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AuthRequiredException : IOException("SilverBullet session expired; sign in in the app")

/**
 * Minimal client for SilverBullet's /.fs HTTP API, authenticated with the WebView's own session
 * cookie — so there is no separate password or token to configure, and nothing changes server-side.
 */
class SbClient(
    private val server: String,
    private val cookieHeader: String?,
    private val token: String? = null,
) {

    data class File(val body: String?, val etag: String?)

    /** Returns null body when the file does not exist yet. */
    fun read(path: String): File {
        val c = open(path, "GET")
        try {
            return when (val code = c.responseCode) {
                200 -> File(c.inputStream.bufferedReader().use { it.readText() }, c.getHeaderField("ETag"))
                404 -> File(null, null)
                else -> throw httpError(code, c)
            }
        } finally { c.disconnect() }
    }

    /**
     * Writes [body]. [ifMatch] = ETag of the version we read (or null with [mustNotExist] for a new
     * file). Returns false on 412, meaning someone else changed the file and we must re-read.
     */
    fun write(path: String, body: String, ifMatch: String?, mustNotExist: Boolean): Boolean {
        val c = open(path, "PUT")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/markdown; charset=utf-8")
            if (ifMatch != null) c.setRequestProperty("If-Match", ifMatch)
            else if (mustNotExist) c.setRequestProperty("If-None-Match", "*")
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return when (val code = c.responseCode) {
                in 200..299 -> true
                412 -> false
                else -> throw httpError(code, c)
            }
        } finally { c.disconnect() }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
        val c = URL("$server/.fs/$encoded").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false // a redirect means "go log in", not something to follow
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.useCaches = false
        c.setRequestProperty("X-Sync-Mode", "true") // marks us as an API client (401, not a login page)
        c.setRequestProperty("Cache-Control", "no-cache")
        if (!token.isNullOrEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        else if (!cookieHeader.isNullOrEmpty()) c.setRequestProperty("Cookie", cookieHeader)
        return c
    }

    private fun httpError(code: Int, c: HttpURLConnection): IOException =
        if (code == 401 || code == 403 || code in 300..399) AuthRequiredException()
        else IOException("HTTP $code from ${c.url}")

    companion object {
        fun forServer(server: String, token: String): SbClient =
            SbClient(server, CookieManager.getInstance().getCookie(server), token.ifEmpty { null })
    }
}
