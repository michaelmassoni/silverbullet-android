package io.github.michaelmassoni.silverbullet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-run screen: asks for the server URL and checks it answers like SilverBullet before handing
 * over to MainActivity, where SilverBullet's own login page (if the server has one) takes over.
 * Credentials are deliberately not collected here, so servers behind any auth (SB_USER, an
 * auth proxy, SSO) work the same way they do in a browser.
 */
class SetupActivity : ComponentActivity() {
    private lateinit var server: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var anyway: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val root = findViewById<View>(R.id.setup_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        server = findViewById(R.id.server)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)
        anyway = findViewById(R.id.anyway)

        Prefs(this).server?.let { server.setText(it) }
        connect.setOnClickListener { check() }
        server.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { check(); true } else false
        }
        anyway.setOnClickListener { finishWith(Prefs.normalizeServer(server.text.toString())) }
    }

    private fun check() {
        val raw = server.text.toString()
        if (raw.isBlank()) { status.text = "Enter your server's address."; return }
        val url = Prefs.normalizeServer(raw)
        connect.isEnabled = false
        anyway.visibility = View.GONE
        status.text = "Checking $url …"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { probe(url) }
            connect.isEnabled = true
            when (result) {
                Probe.OK -> {
                    if (!Prefs.isSecureOrigin(url)) {
                        status.text = "Connected, but over plain HTTP. Offline mode needs HTTPS: " +
                            "browsers only run SilverBullet's offline cache on secure origins."
                        anyway.text = "Continue without offline mode"
                        anyway.visibility = View.VISIBLE
                    } else finishWith(url)
                }
                Probe.NOT_SB -> {
                    status.text = "$url answered, but it doesn't look like a SilverBullet server."
                    anyway.text = getString(R.string.use_anyway)
                    anyway.visibility = View.VISIBLE
                }
                is Probe.Failed -> {
                    status.text = "Couldn't reach $url (${result.reason}). If it's only reachable on " +
                        "a VPN or your home network, connect to it first."
                    anyway.text = getString(R.string.use_anyway)
                    anyway.visibility = View.VISIBLE
                }
            }
        }
    }

    private sealed class Probe {
        object OK : Probe()
        object NOT_SB : Probe()
        class Failed(val reason: String) : Probe()
    }

    /** `/.ping` is unauthenticated on SilverBullet servers, so this works before login. */
    private fun probe(url: String): Probe = try {
        val c = URL("$url/.ping").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode == 200) Probe.OK else Probe.NOT_SB
        } finally { c.disconnect() }
    } catch (e: Exception) {
        Probe.Failed(e.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "error" }
            .let { if (e.message != null) "${e.message}" else it })
    }

    private fun finishWith(url: String) {
        val prefs = Prefs(this)
        if (prefs.server != url) prefs.lastUrl = null
        prefs.server = url
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
