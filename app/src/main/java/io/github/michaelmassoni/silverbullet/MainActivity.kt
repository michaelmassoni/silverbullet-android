package io.github.michaelmassoni.silverbullet

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hosts the SilverBullet web client in a WebView. SilverBullet's own service worker does the
 * offline caching and sync; this shell adds a persistent (never browser-evicted) storage origin,
 * a warm process when you switch away, external links in the real browser, and file uploads.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private var loadedServer: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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
        if (prefs.server == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        web = WebView(this)
        // WebView ignores its own padding, so inset a container around it instead.
        val root = FrameLayout(this).apply { addView(web) }
        setContentView(root)
        // Keep page content clear of the status bar, nav bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, web).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
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

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == Uri.parse(prefs.server ?: "").host) return false
                openExternally(url)
                return true
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
                if (web.canGoBack()) web.goBack()
                else moveTaskToBack(true) // keep the process (and editor) warm instead of finishing
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) loadStart()

        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
            prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
        if (prefs.server == null) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        if (loadedServer != null && loadedServer != prefs.server) { prefs.lastUrl = null; loadStart() }
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
}
