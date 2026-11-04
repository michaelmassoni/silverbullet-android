package io.github.michaelmassoni.silverbullet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * The quick-capture dialog. Opened from the launcher long-press shortcut, the quick settings tile,
 * the home-screen widget, or Android's share sheet. Never waits on the network: it queues locally
 * and returns immediately.
 */
class CaptureActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var text: EditText
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        prefs = Prefs(this)
        if (prefs.server == null) {
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        text = findViewById(R.id.text)

        findViewById<TextView>(R.id.target).text =
            "→ ${Prefs.resolvePage(prefs.capturePage, LocalDate.now())}"
        val pending = CaptureQueue(this).count()
        findViewById<TextView>(R.id.pending).text =
            if (pending > 0) "$pending waiting to sync" else ""

        val shared = sharedText(intent)
        text.setText(shared ?: prefs.draft)
        text.setSelection(text.text.length)
        text.requestFocus()

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener {
            prefs.draft = ""
            saved = true
            finish()
        }
        // Ctrl/Shift+Enter on a hardware keyboard saves.
        text.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN &&
                (event.isCtrlPressed || event.isShiftPressed)) { save(); true } else false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::text.isInitialized) return
        sharedText(intent)?.let { s ->
            val cur = text.text.toString()
            text.setText(if (cur.isBlank()) s else "$cur\n$s")
            text.setSelection(text.text.length)
        }
    }

    private fun sharedText(i: Intent?): String? =
        if (i?.action == Intent.ACTION_SEND)
            CaptureQueue.fromShare(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
                .ifBlank { null }
        else null

    private fun save() {
        val md = CaptureQueue.toBullets(text.text.toString())
        if (md.isEmpty()) { finish(); return }
        CaptureQueue(this).add(md, prefs.capturePage)
        prefs.draft = ""
        saved = true
        CaptureWorker.kick(this)
        Toast.makeText(this, "Captured to ${Prefs.resolvePage(prefs.capturePage, LocalDate.now())}", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Dismissed by back / tapping outside / switching away: keep what was typed for next time.
        if (!saved && ::text.isInitialized) prefs.draft = text.text.toString()
    }
}
