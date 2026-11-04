package io.github.michaelmassoni.silverbullet

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Durable on-device queue of captures waiting to be appended to the server.
 * One file per capture: line 1 is the target page (resolved at capture time, so a note jotted at
 * 23:59 offline still lands on that day's page), line 2 is the date for new-page frontmatter (empty
 * if the page isn't date-based), the rest is the markdown to append. Files are named by creation
 * time so they flush in order.
 */
class CaptureQueue(context: Context) {
    private val dir = File(context.filesDir, "captures").apply { mkdirs() }

    data class Entry(val file: File, val page: String, val day: String?, val markdown: String)

    fun add(markdown: String, pattern: String, day: LocalDate = LocalDate.now()) {
        val page = Prefs.resolvePage(pattern, day)
        val dated = if (pattern.contains("{date}")) day.toString() else ""
        val name = "%013d-%s".format(System.currentTimeMillis(), UUID.randomUUID().toString().take(8))
        val tmp = File(dir, "$name.tmp")
        tmp.writeText("$page\n$dated\n$markdown")
        tmp.renameTo(File(dir, "$name.txt")) // atomic: the worker never sees a half-written file
    }

    fun entries(): List<Entry> =
        (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                val parts = text.split('\n', limit = 3)
                if (parts.size < 3 || parts[0].isEmpty()) { f.delete(); return@mapNotNull null }
                Entry(f, parts[0], parts[1].ifEmpty { null }, parts[2])
            }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    companion object {
        /** Each non-blank line becomes a `* ` bullet, matching how the journal is written. */
        fun toBullets(text: String): String =
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { line ->
                    // Keep lines that are already list items / tasks as they are.
                    if (line.startsWith("* ") || line.startsWith("- ")) "* " + line.substring(2) else "* $line"
                }

        /** Text shared from another app: "Title + URL" becomes a markdown link. */
        fun fromShare(subject: String?, text: String?): String {
            val t = text?.trim().orEmpty()
            val s = subject?.trim().orEmpty()
            val isUrl = Regex("^https?://\\S+$").matches(t)
            return when {
                isUrl && s.isNotEmpty() && s != t -> "[${s.replace("]", "\\]")}]($t)"
                s.isNotEmpty() && t.isNotEmpty() && !t.contains(s) -> "$s — $t"
                else -> t.ifEmpty { s }
            }
        }
    }
}
