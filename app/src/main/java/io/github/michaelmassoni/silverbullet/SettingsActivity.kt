package io.github.michaelmassoni.silverbullet

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate

/** Full-screen app settings. Reached from the bottom bar or the launcher icon's long-press menu. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // wallpaper colours on Android 12+
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_bar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var prefs: Prefs
        private lateinit var barPrefs: BarPrefs
        private lateinit var serverPref: Preference
        private lateinit var pagePref: EditTextPreference
        private lateinit var tokenPref: EditTextPreference
        private lateinit var queuePref: Preference

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Same store the rest of the app reads (see Prefs / BarPrefs).
            preferenceManager.sharedPreferencesName = "sb"
            val ctx = preferenceManager.context
            prefs = Prefs(ctx)
            barPrefs = BarPrefs(ctx)
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            // --- Server -------------------------------------------------------------------------
            screen.category("Server") {
                serverPref = add(Preference(ctx).apply {
                    key = "server_row"
                    title = "SilverBullet server"
                    icon = tinted(R.drawable.ic_pref_server)
                    setOnPreferenceClickListener {
                        startActivity(Intent(ctx, SetupActivity::class.java)); true
                    }
                })
            }

            // --- Quick capture ------------------------------------------------------------------
            screen.category("Quick capture") {
                pagePref = add(EditTextPreference(ctx).apply {
                    key = "capture_page"
                    title = "Capture page"
                    dialogTitle = "Capture page"
                    dialogMessage = "Page that captures are appended to. {date} is replaced by today's date, " +
                        "e.g. Journal/{date} for daily notes, or a fixed page like Inbox."
                    icon = tinted(R.drawable.ic_capture)
                    setDefaultValue(Prefs.DEFAULT_CAPTURE_PAGE)
                    setOnBindEditTextListener { it.setSingleLine(); it.setSelection(it.text.length) }
                    setOnPreferenceChangeListener { _, value ->
                        prefs.capturePage = value as String // normalises (trims, strips .md)
                        text = prefs.capturePage
                        refresh()
                        false // already stored
                    }
                })
                tokenPref = add(EditTextPreference(ctx).apply {
                    key = "token"
                    title = "API token"
                    dialogTitle = "API token"
                    dialogMessage = "Optional. Set SB_AUTH_TOKEN on your server and enter the same value here, " +
                        "so captures keep syncing after your login expires. Leave empty to use your login."
                    icon = tinted(R.drawable.ic_pref_key)
                    setOnBindEditTextListener {
                        it.setSingleLine()
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    setOnPreferenceChangeListener { _, value ->
                        prefs.token = value as String
                        text = prefs.token
                        refresh()
                        false
                    }
                })
                queuePref = add(Preference(ctx).apply {
                    key = "queue_row"
                    title = "Waiting to sync"
                    icon = tinted(R.drawable.ic_pref_sync)
                    setOnPreferenceClickListener {
                        if (CaptureQueue(ctx).count() > 0) {
                            CaptureWorker.kick(ctx)
                            Toast.makeText(ctx, "Sending when the server is reachable", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                })
            }

            // --- Bottom bar ---------------------------------------------------------------------
            screen.category("Bottom bar") {
                add(SwitchPreferenceCompat(ctx).apply {
                    key = "bar_enabled"
                    title = "Show bottom bar"
                    icon = tinted(R.drawable.ic_pref_bar)
                    summary = "Hides while you type; SilverBullet's formatting bar takes its place"
                    setDefaultValue(true)
                })
                add(SwitchPreferenceCompat(ctx).apply {
                    key = "hide_top_actions"
                    title = "Hide SilverBullet's top buttons"
                    icon = tinted(R.drawable.ic_pref_hide)
                    summary = "The bottom bar replaces them. They come back if the bar is off."
                    setDefaultValue(true)
                })
            }
            screen.category("Bottom bar buttons") {
                for (item in BarItem.entries) {
                    add(SwitchPreferenceCompat(ctx).apply {
                        key = "bar_${item.key}"
                        title = item.label
                        icon = tinted(item.icon)
                        setDefaultValue(item.defaultOn)
                    })
                }
            }
            // Dependencies resolve once the keys are attached to the screen.
            screen.findPreference<Preference>("hide_top_actions")?.dependency = "bar_enabled"
            BarItem.entries.forEach { screen.findPreference<Preference>("bar_${it.key}")?.dependency = "bar_enabled" }

            // --- About --------------------------------------------------------------------------
            screen.category("About") {
                add(Preference(ctx).apply {
                    title = "Version"
                    icon = tinted(R.drawable.ic_pref_info)
                    summary = BuildConfig.VERSION_NAME
                    isSelectable = false
                })
                add(Preference(ctx).apply {
                    title = "Source code and issues"
                    icon = tinted(R.drawable.ic_pref_code)
                    summary = "github.com/michaelmassoni/silverbullet-android"
                    setOnPreferenceClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))); true
                    }
                })
                add(Preference(ctx).apply {
                    title = "About this app"
                    summary = "An unofficial community app for SilverBullet (silverbullet.md). " +
                        "Not affiliated with the SilverBullet project. MIT licensed."
                    isSelectable = false
                })
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setDivider(null) // Material 3 settings: section titles, no rules
            // Let the list scroll under the navigation bar instead of stopping above it.
            listView.clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
                insets
            }
        }

        /** Material 3 dialogs for the text settings (Preference's own dialogs use the old style). */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference !is EditTextPreference) return super.onDisplayPreferenceDialog(preference)
            val ctx = requireContext()
            val secret = preference.key == "token"
            val field = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = preference.title
                if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val input = TextInputEditText(field.context).apply {
                setSingleLine()
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                if (secret) typeface = android.graphics.Typeface.DEFAULT // password fields default to monospace
                setText(preference.text.orEmpty())
                setSelection(text?.length ?: 0)
            }
            field.addView(input)
            val pad = (24 * resources.displayMetrics.density).toInt()
            val box = FrameLayout(ctx).apply { setPadding(pad, pad / 3, pad, 0); addView(field) }
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(preference.dialogTitle)
                .setMessage(preference.dialogMessage)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val value = input.text?.toString().orEmpty()
                    if (preference.callChangeListener(value)) preference.text = value
                }
                .create()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dialog.show()
            input.requestFocus()
        }

        override fun onResume() {
            super.onResume()
            refresh() // server may have changed in SetupActivity; queue may have drained
        }

        private fun refresh() {
            serverPref.summary = prefs.server?.removePrefix("https://") ?: "Not set"
            pagePref.text = prefs.capturePage
            pagePref.summary = if (prefs.capturePage.contains("{date}"))
                "${prefs.capturePage}  ·  today: ${Prefs.resolvePage(prefs.capturePage, LocalDate.now())}"
            else prefs.capturePage
            tokenPref.summary = if (prefs.token.isEmpty()) "Not set · captures use your login (lasts 7 days)"
                else "Set · used for background capture sync"
            val n = CaptureQueue(requireContext()).count()
            queuePref.summary = if (n == 0) "Nothing waiting" else "$n capture${if (n == 1) "" else "s"} waiting · tap to send now"
        }

        private fun tinted(res: Int) = ContextCompat.getDrawable(requireContext(), res)!!.mutate().also {
            DrawableCompat.setTint(it, MaterialColors.getColor(requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        }

        /** Every switch on this screen uses the Material 3 switch. */
        private fun <T : Preference> styled(p: T): T {
            if (p is SwitchPreferenceCompat) p.widgetLayoutResource = R.layout.pref_material_switch
            return p
        }

        private inline fun PreferenceScreen.category(title: String, build: PreferenceCategory.() -> Unit) {
            val cat = PreferenceCategory(context).apply { this.title = title; isIconSpaceReserved = true }
            addPreference(cat)
            cat.build()
        }

        private fun <T : Preference> PreferenceCategory.add(p: T): T {
            addPreference(styled(p))
            return p
        }
    }

    companion object {
        const val REPO_URL = "https://github.com/michaelmassoni/silverbullet-android"
    }
}
