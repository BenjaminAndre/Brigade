package com.brigade.journal

import com.brigade.content.ContentPath
import java.time.Instant
import java.time.format.DateTimeParseException

/** One line of `.brigade/journal.tsv`: something appeared, or the screen stopped counting. */
sealed interface JournalEntry {

    val atMillis: Long

    /** [panel] went up on the players' screen, and stays until the next entry. */
    data class Shown(override val atMillis: Long, val panel: Panel) : JournalEntry

    /** Nothing is on the players' screen from here: the display went, or Brigade did. */
    data class Ended(override val atMillis: Long) : JournalEntry
}

/**
 * The journal's file format: one entry per line, tab-separated.
 *
 * ```
 * 2026-09-24T18:12:03Z	note	PNJs/Yue Fei.md
 * 2026-09-24T18:24:51Z	image	Lieux/Lin'an.jpg
 * 2026-09-24T18:31:07Z	info
 * 2026-09-24T19:02:40Z	end
 * ```
 *
 * Text rather than JSON so that it can only ever be appended to, and so that a Git diff of a
 * campaign shows one line per thing the players saw. Times are UTC instants: a campaign
 * folder travels between devices, and a local time without its zone is ambiguous twice a
 * year. Seconds, because nothing at a table is decided in milliseconds.
 *
 * Decoding is total. A line it does not understand — hand-edited, or from a later version —
 * is skipped rather than failing the whole recap.
 */
object JournalCodec {

    const val FILE_HEADER = "# Brigade journal v1: what the players saw, and when. Appended to; do not edit."

    private const val SEPARATOR = '\t'

    /** @return null for a path that cannot be written on one line, which is then not recorded. */
    fun encode(entry: JournalEntry): String? {
        val at = Instant.ofEpochSecond(Math.floorDiv(entry.atMillis, 1000L)).toString()
        val fields = when (entry) {
            is JournalEntry.Ended -> listOf(KIND_END)
            is JournalEntry.Shown -> when (val panel = entry.panel) {
                is Panel.Note -> listOf(KIND_NOTE, panel.path.value)
                is Panel.Image -> listOf(KIND_IMAGE, panel.path.value)
                Panel.Info -> listOf(KIND_INFO)
                Panel.Black -> listOf(KIND_BLACK)
            }
        }
        if (fields.any { it.contains(SEPARATOR) || it.contains('\n') || it.contains('\r') }) return null
        return (listOf(at) + fields).joinToString(SEPARATOR.toString())
    }

    fun decode(line: String): JournalEntry? {
        if (line.isBlank() || line.startsWith("#")) return null
        val fields = line.trimEnd('\r').split(SEPARATOR)

        val at = try {
            Instant.parse(fields[0]).toEpochMilli()
        } catch (unreadable: DateTimeParseException) {
            return null
        }
        val path = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::ContentPath)

        return when (fields.getOrNull(1)) {
            KIND_END -> JournalEntry.Ended(at)
            KIND_INFO -> JournalEntry.Shown(at, Panel.Info)
            KIND_BLACK -> JournalEntry.Shown(at, Panel.Black)
            KIND_NOTE -> path?.let { JournalEntry.Shown(at, Panel.Note(it)) }
            KIND_IMAGE -> path?.let { JournalEntry.Shown(at, Panel.Image(it)) }
            else -> null
        }
    }

    fun decodeAll(text: String): List<JournalEntry> = text.lineSequence().mapNotNull(::decode).toList()

    private const val KIND_NOTE = "note"
    private const val KIND_IMAGE = "image"
    private const val KIND_INFO = "info"
    private const val KIND_BLACK = "black"
    private const val KIND_END = "end"
}
