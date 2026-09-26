package com.brigade.journal

import com.brigade.content.ContentPath
import com.brigade.content.Markdown
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every word `Brigade.md` contains that is not a name or a date.
 *
 * Read from string resources by the caller, so no French lives in Kotlin (§21) and this
 * renderer stays plain Kotlin that the JVM tests can reach.
 *
 * @param sessionHeading `%1$s` date, `%2$s` start, `%3$s` end, `%4$s` duration.
 * @param hours `%1$d` hours, `%2$d` minutes.
 */
data class RecapLabels(
    val title: String,
    val notice: String,
    val columnStart: String,
    val columnPanel: String,
    val columnDuration: String,
    val columnTotal: String,
    val longest: String,
    val info: String,
    val black: String,
    val sessionHeading: String,
    val hours: String,
    val minutes: String,
    val seconds: String,
)

/**
 * Writes `Brigade.md`: one section per session, newest first, each a timeline of what was
 * on the players' screen and for how long, then the same ranked by total time.
 *
 * Notes and images are Obsidian wikilinks, so the recap is a way into the campaign rather
 * than a list of names — and every note shown gets a backlink from it.
 */
object RecapMarkdown {

    /**
     * The frontmatter line that marks the file as Brigade's own.
     *
     * The only `Brigade.md` Brigade will ever overwrite is one carrying it. A file of that
     * name without it is the GM's, and is left alone.
     */
    const val MARKER = "generated_by: Brigade"

    fun isOurs(text: String): Boolean =
        Markdown.frontmatter(text)?.lineSequence()?.any { it.trim() == MARKER } == true

    fun render(
        sessions: List<Session>,
        noteOf: Map<ContentPath, ContentPath>,
        labels: RecapLabels,
        zone: ZoneId,
        locale: Locale,
    ): String = buildString {
        appendLine("---")
        appendLine(MARKER)
        appendLine("---")
        appendLine()
        appendLine("> [!info] Brigade")
        appendLine("> ${labels.notice}")
        appendLine()
        appendLine("# ${labels.title}")

        // Newest first: the recap is opened to remember the last session, not the first.
        sessions.sortedByDescending { it.startMillis }.forEach { session ->
            appendLine()
            appendSession(session, noteOf, labels, zone, locale)
        }
    }

    private fun StringBuilder.appendSession(
        session: Session,
        noteOf: Map<ContentPath, ContentPath>,
        labels: RecapLabels,
        zone: ZoneId,
        locale: Locale,
    ) {
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(session.startMillis), zone)
        val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(session.endMillis), zone)

        // Past midnight, the end carries its weekday, so «20:12 → dimanche 01:47» cannot be
        // misread as a session that ran backwards.
        val endLabel = if (end.toLocalDate() == start.toLocalDate()) {
            clock(end)
        } else {
            "${end.dayOfWeek.getDisplayName(TextStyle.FULL, locale)} ${clock(end)}"
        }

        val heading = String.format(
            locale,
            labels.sessionHeading,
            date(start, locale),
            clock(start),
            endLabel,
            duration(session.spanMillis, labels, locale),
        )
        appendLine("## $heading")
        appendLine()

        appendLine("| ${labels.columnStart} | ${labels.columnPanel} | ${labels.columnDuration} |")
        appendLine("| --- | --- | --- |")
        session.appearances(noteOf).forEach { appearance ->
            val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(appearance.startMillis), zone)
            appendLine(
                "| ${clock(at)} | ${name(appearance.panel, labels)} | " +
                    "${duration(appearance.onScreenMillis, labels, locale)} |",
            )
        }

        val totals = session.totals(noteOf)
        if (totals.isNotEmpty()) {
            appendLine()
            appendLine("**${labels.longest}**")
            appendLine()
            appendLine("| ${labels.columnPanel} | ${labels.columnTotal} |")
            appendLine("| --- | --- |")
            totals.forEach { (panel, total) ->
                appendLine("| ${name(panel, labels)} | ${duration(total, labels, locale)} |")
            }
        }
    }

    /**
     * A wikilink with the full campaign path, so a name shared by two files still opens the
     * one that was shown. The pipe is escaped because it sits inside a table cell.
     */
    private fun name(panel: Panel, labels: RecapLabels): String = when (panel) {
        is Panel.Note -> {
            // Obsidian links notes without their extension.
            val bare = panel.path.fileName.substringBeforeLast('.')
            wikilink(ContentPath.of(panel.path.parent.segments + bare).value, bare)
        }
        is Panel.Image -> wikilink(panel.path.value, panel.path.fileName)
        Panel.Info -> labels.info
        Panel.Black -> labels.black
    }

    private fun wikilink(target: String, alias: String) = "[[$target\\|$alias]]"

    /** «Samedi 24 septembre 2026» — capitalised, because it opens a heading. */
    private fun date(at: ZonedDateTime, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale).format(at)
            .replaceFirstChar { it.titlecase(locale) }

    private fun clock(at: ZonedDateTime): String = DateTimeFormatter.ofPattern("HH:mm").format(at)

    /** «45 s», «12 min», «1 h 05». Rounded to the nearest unit shown. */
    fun duration(millis: Long, labels: RecapLabels, locale: Locale): String {
        val seconds = (millis + 500) / 1000
        if (seconds < 60) return String.format(locale, labels.seconds, seconds)

        val minutes = (seconds + 30) / 60
        if (minutes < 60) return String.format(locale, labels.minutes, minutes)

        return String.format(locale, labels.hours, minutes / 60, minutes % 60)
    }
}
