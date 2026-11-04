package io.github.michaelmassoni.silverbullet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Reached from the launcher icon's long-press menu. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        val page = findViewById<EditText>(R.id.capture_page).apply { setText(prefs.capturePage) }
        val token = findViewById<EditText>(R.id.token).apply { setText(prefs.token) }
        findViewById<Button>(R.id.change_server).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.sync).setOnClickListener {
            CaptureWorker.kick(this)
            Toast.makeText(this, "Sending when the server is reachable", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.capturePage = page.text.toString()
            prefs.token = token.text.toString()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.server).text = prefs.server ?: "Not set"
        val n = CaptureQueue(this).count()
        findViewById<TextView>(R.id.status).text =
            if (n == 0) "No captures waiting." else "$n capture${if (n == 1) "" else "s"} waiting to sync."
    }
}
