package io.github.michaelmassoni.silverbullet

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hosts the SilverBullet web client in a WebView. SilverBullet's own service worker does the
 * offline caching and sync; this shell adds a persistent (never browser-evicted) storage origin,
 * a warm process when you switch away, a native bottom bar, keyboard-aware layout (so
 * SilverBullet's formatting bar works), external links in the real browser, and file uploads.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bar: BottomBarView
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs
    private lateinit var barPrefs: BarPrefs
    private var loadedServer: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var keyboardVisible = false
    private var lastKeyboardCss = -1
    private var bridgeAtDocumentStart = false
    private val bridgeJs by lazy { assets.open("bridge.js").bufferedReader().use { it.readText() } }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        barPrefs = BarPrefs(this)
        val server = prefs.server
        if (server == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this)
        bar = BottomBarView(this, ::onBarClick)
        // WebView ignores its own padding, so inset a container around it instead.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(this@MainActivity).apply { addView(web) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        }
        setContentView(root)

        // The window is edge-to-edge and never resized for the keyboard. Pad for the system bars,
        // and instead of shrinking the WebView for the keyboard, tell SilverBullet how much of it
        // the keyboard covers (see bridge.js): it then lays out above the keyboard itself and shows
        // its own formatting bar, exactly as it does in Chrome.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateBarVisibility()
            pushKeyboardHeight(max(0, ime.bottom - bars.bottom))
            WindowInsetsCompat.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
            userAgentString = "$userAgentString SilverBulletAndroid/${BuildConfig.VERSION_NAME}"
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        installBridge(server)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == Uri.parse(prefs.server ?: "").host) return false
                openExternally(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                lastKeyboardCss = -1
                // Old WebViews without document-start scripts: inject as early as we can.
                if (!bridgeAtDocumentStart && isServerUrl(url)) view.evaluateJavascript(bridgeJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                // After a successful sign-in (or any normal load) push queued captures.
                if (!url.contains("/.auth")) CaptureWorker.kick(this@MainActivity)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only reached when the service worker hasn't cached the app yet (first run offline).
                if (request.isForMainFrame) showOffline(error.description?.toString())
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickFiles.launch(params.createIntent()); true }
                catch (e: ActivityNotFoundException) { fileCallback = null; false }
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> openExternally(Uri.parse(url)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let SilverBullet close an open picker/panel first, then page history, then leave.
                web.evaluateJavascript("window.__sbAndroid ? window.__sbAndroid.back() : false") { closed ->
                    if (closed == "true") return@evaluateJavascript
                    if (web.canGoBack()) web.goBack()
                    else moveTaskToBack(true) // keep the process (and editor) warm instead of finishing
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) loadStart()

        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
            prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Injects bridge.js and the theme channel, restricted to the server's own origin. */
    private fun installBridge(server: String) {
        val origin = originOf(server)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, bridgeJs, setOf(origin))
            bridgeAtDocumentStart = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "SBAndroidNative", setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onBridgeMessage(message.data ?: return@addWebMessageListener)
            }
        }
    }

    private fun onBridgeMessage(data: String) {
        val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (msg.optString("type") == "theme") {
            val barColor = parseCssColor(msg.optString("bar")) ?: return
            val fg = parseCssColor(msg.optString("fg")) ?: return
            applyTheme(barColor, fg, parseCssColor(msg.optString("page")) ?: barColor)
        }
    }

    /** Match the status bar strip, bottom bar and navigation bar area to SilverBullet's theme. */
    private fun applyTheme(barColor: Int, fg: Int, page: Int) {
        root.setBackgroundColor(barColor) // shows through behind the status and navigation bars
        bar.setColors(barColor, fg)
        window.decorView.setBackgroundColor(page)
        val light = ColorUtils.calculateLuminance(barColor) > 0.5
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun onBarClick(item: BarItem) {
        when (item) {
            BarItem.CAPTURE -> startActivity(Intent(this, CaptureActivity::class.java))
            BarItem.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> web.evaluateJavascript("window.__sbAndroid && window.__sbAndroid.run('${item.key}')") { r ->
                if (r != "true") Log.w(TAG, "bar action ${item.key} not handled (client not ready?)")
            }
        }
    }

    private fun updateBarVisibility() {
        // The bar steps aside while typing; SilverBullet's keyboard bar takes over then.
        bar.visibility = if (barPrefs.enabled && !keyboardVisible) View.VISIBLE else View.GONE
    }

    private fun pushKeyboardHeight(px: Int) {
        val css = (px / resources.displayMetrics.density).roundToInt()
        if (css == lastKeyboardCss) return
        lastKeyboardCss = css
        web.evaluateJavascript("window.__sbAndroid && window.__sbAndroid.setKeyboard($css)", null)
    }

    private fun isServerUrl(url: String) = prefs.server?.let { url.startsWith(it) } == true

    private fun loadStart() {
        val server = prefs.server ?: return
        loadedServer = server
        val last = prefs.lastUrl
        web.loadUrl(if (last != null && last.startsWith("$server/")) last else "$server/")
    }

    private fun showOffline(reason: String?) {
        val server = prefs.server ?: return
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;padding:32px;color:#888;background:transparent}
            a{display:inline-block;margin-top:16px;padding:10px 18px;border-radius:10px;background:#464cfc;color:#fff;text-decoration:none}</style>
            </head><body><h2>Can't reach SilverBullet</h2>
            <p>$server isn't reachable${if (reason != null) " ($reason)" else ""}. Are you on the right network or VPN?</p>
            <p>Once the app has loaded once, it works offline after that.</p>
            <a href="$server/">Retry</a></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun openExternally(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {}
    }

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return
        web.onResume()
        val server = prefs.server
        if (server == null) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        if (loadedServer != null && loadedServer != server) {
            // Scripts are bound to the old origin; simplest correct thing is a fresh activity.
            prefs.lastUrl = null
            recreate()
            return
        }
        bar.setItems(barPrefs.visibleItems()) // settings may have changed
        updateBarVisibility()
        CaptureWorker.kick(this)
    }

    override fun onPause() {
        if (!::web.isInitialized) { super.onPause(); return }
        val server = prefs.server
        web.url?.takeIf { server != null && it.startsWith(server) && !it.contains("/.auth") }?.let { prefs.lastUrl = it }
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"

        fun originOf(server: String): String {
            val u = Uri.parse(server)
            return "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
        }

        /** Parses `rgb(r, g, b)` / `rgba(r, g, b, a)` as returned by getComputedStyle. */
        fun parseCssColor(css: String): Int? {
            val m = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?\s*\)""").find(css) ?: return null
            val (r, g, b) = m.destructured
            val a = m.groupValues[4].toFloatOrNull() ?: 1f
            if (a < 0.05f) return null // transparent: keep the current colour
            return Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
    }
}
