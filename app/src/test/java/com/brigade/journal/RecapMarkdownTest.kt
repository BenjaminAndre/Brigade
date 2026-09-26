package com.brigade.journal

import com.brigade.content.ContentPath
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapMarkdownTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val french = Locale.FRENCH

    /** The values in res/values/strings.xml. */
    private val labels = RecapLabels(
        title = "Séances",
        notice = "Tenu à jour par Brigade.",
        columnStart = "Début",
        columnPanel = "Panneau",
        columnDuration = "Durée",
        columnTotal = "Total",
        longest = "Le plus longtemps à l'écran",
        info = "INFO",
        black = "Noir",
        sessionHeading = "%1\$s · %2\$s → %3\$s · %4\$s",
        hours = "%1\$d h %2\$02d",
        minutes = "%1\$d min",
        seconds = "%1\$d s",
    )

    private val yueFei = Panel.Note(ContentPath("PNJs/Yue Fei.md"))
    private val linAn = Panel.Image(ContentPath("Lieux/Lin'an.jpg"))

    /** Saturday 26 September 2026, Paris time. */
    private fun saturday(hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 9, 26, hour, minute, 0, 0, paris).toInstant().toEpochMilli()

    private fun session(vararg showings: Showing) = Session(showings.toList())

    private fun render(vararg sessions: Session) =
        RecapMarkdown.render(sessions.toList(), emptyMap(), labels, paris, french)

    // ---- Ownership -----------------------------------------------------------------

    @Test
    fun `its own output is recognised as its own`() {
        assertTrue(RecapMarkdown.isOurs(render()))
    }

    /** The one guarantee that makes writing to the campaign root acceptable at all. */
    @Test
    fun `a Brigade md the GM wrote is not`() {
        assertFalse(RecapMarkdown.isOurs("# Brigade\n\nMes notes sur la brigade du préfet.\n"))
        assertFalse(RecapMarkdown.isOurs("---\ntags: [pnj]\n---\n\n# Brigade\n"))
        assertFalse(RecapMarkdown.isOurs(""))
    }

    // ---- Contents ------------------------------------------------------------------

    @Test
    fun `a session is headed by its date, its hours and its length`() {
        val text = render(session(Showing(linAn, saturday(20, 12), saturday(22, 47))))

        assertTrue(text, text.contains("## Samedi 26 septembre 2026 · 20:12 → 22:47 · 2 h 35"))
    }

    @Test
    fun `past midnight the end carries its weekday`() {
        val end = saturday(20, 12) + (5 * 60 + 35) * 60_000L
        val text = render(session(Showing(linAn, saturday(20, 12), end)))

        assertTrue(text, text.contains("20:12 → dimanche 01:47 · 5 h 35"))
    }

    /** Full paths, so a name two files share still opens the one that was shown. */
    @Test
    fun `notes and images are wikilinks, escaped for a table cell`() {
        val text = render(
            session(
                Showing(yueFei, saturday(20, 0), saturday(20, 30)),
                Showing(linAn, saturday(20, 30), saturday(21, 0)),
            ),
        )

        assertTrue(text, text.contains("| 20:00 | [[PNJs/Yue Fei\\|Yue Fei]] | 30 min |"))
        assertTrue(text, text.contains("| 20:30 | [[Lieux/Lin'an.jpg\\|Lin'an.jpg]] | 30 min |"))
    }

    @Test
    fun `a raw image is shown under its note`() {
        val portrait = Panel.Image(ContentPath("Ressources/yue-fei.jpg"))
        val text = RecapMarkdown.render(
            listOf(session(Showing(portrait, saturday(20, 0), saturday(21, 0)))),
            mapOf(portrait.path to yueFei.path),
            labels,
            paris,
            french,
        )

        assertTrue(text, text.contains("[[PNJs/Yue Fei\\|Yue Fei]]"))
        assertFalse(text, text.contains("yue-fei.jpg"))
    }

    @Test
    fun `the newest session comes first`() {
        val earlier = session(Showing(linAn, saturday(14, 0), saturday(15, 0)))
        val later = session(Showing(yueFei, saturday(20, 0), saturday(21, 0)))

        val text = render(earlier, later)

        assertTrue(text.indexOf("20:00 →") < text.indexOf("14:00 →"))
    }

    @Test
    fun `a session of only INFO has a timeline but no ranking`() {
        val text = render(session(Showing(Panel.Info, saturday(20, 0), saturday(21, 0))))

        assertTrue(text, text.contains("| 20:00 | INFO | 1 h 00 |"))
        assertFalse(text, text.contains(labels.longest))
    }

    // ---- Durations -----------------------------------------------------------------

    @Test
    fun `durations read as seconds, minutes, or hours and minutes`() {
        fun d(millis: Long) = RecapMarkdown.duration(millis, labels, french)

        assertEquals("45 s", d(45_000))
        assertEquals("1 min", d(59_600))
        assertEquals("12 min", d(12 * 60_000L + 10_000))
        assertEquals("1 h 00", d(59 * 60_000L + 50_000))
        assertEquals("1 h 05", d(65 * 60_000L))
    }
}
