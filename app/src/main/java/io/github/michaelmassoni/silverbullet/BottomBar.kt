package io.github.michaelmassoni.silverbullet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.edit

/**
 * Entries the native bottom bar can show, in display order. `action` is either a native action
 * (capture, settings) or a name understood by `__sbAndroid.run()` in assets/bridge.js.
 */
enum class BarItem(val key: String, val label: String, val icon: Int, val defaultOn: Boolean) {
    SIDEBAR("sidebar", "Sidebar", R.drawable.ic_bar_sidebar, true),
    BACK("back", "Back", R.drawable.ic_bar_back, false),
    HOME("home", "Home page", R.drawable.ic_bar_home, false),
    SEARCH("search", "Search", R.drawable.ic_bar_search, true),
    JOURNAL("journal", "Today's journal", R.drawable.ic_bar_journal, true),
    CAPTURE("capture", "Quick capture", R.drawable.ic_capture, true),
    COMMANDS("commands", "Command palette", R.drawable.ic_bar_commands, true),
    SETTINGS("settings", "App settings", R.drawable.ic_settings, false);
}

/** Bottom bar preferences, kept beside the other app settings. */
class BarPrefs(context: Context) {
    private val sp = context.getSharedPreferences("sb", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("bar_enabled", true)
        set(v) = sp.edit { putBoolean("bar_enabled", v) }

    fun isOn(item: BarItem) = sp.getBoolean("bar_${item.key}", item.defaultOn)
    fun set(item: BarItem, on: Boolean) = sp.edit { putBoolean("bar_${item.key}", on) }

    fun visibleItems(): List<BarItem> = BarItem.entries.filter { isOn(it) }
}

/** The bar itself: a row of equally spaced icon buttons, recoloured to match SilverBullet's theme. */
class BottomBarView(context: Context, private val onClick: (BarItem) -> Unit) : LinearLayout(context) {
    private var fg = Color.GRAY
    private var accent = context.getColor(R.color.accent)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(56))
    }

    fun setItems(items: List<BarItem>) {
        removeAllViews()
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        for (item in items) {
            addView(ImageButton(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setImageResource(item.icon)
                setBackgroundResource(ripple)
                contentDescription = item.label
                tooltipText = item.label
                tag = item
                setOnClickListener { onClick(item) }
            })
        }
        applyTint()
    }

    fun setColors(background: Int, foreground: Int) {
        setBackgroundColor(background)
        fg = foreground
        applyTint()
    }

    private fun applyTint() {
        for (i in 0 until childCount) {
            val b = getChildAt(i) as ImageButton
            b.imageTintList = ColorStateList.valueOf(if (b.tag == BarItem.CAPTURE) accent else fg)
        }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(if (childCount == 0) View.GONE else visibility)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
