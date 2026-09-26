package com.brigade.journal

import com.brigade.content.ContentPath
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalCodecTest {

    private val at = Instant.parse("2026-09-26T18:12:03Z").toEpochMilli()

    private fun roundTrip(entry: JournalEntry) = JournalCodec.decode(JournalCodec.encode(entry)!!)

    @Test
    fun `every kind of entry survives the file`() {
        listOf(
            JournalEntry.Shown(at, Panel.Note(ContentPath("PNJs/Yue Fei.md"))),
            JournalEntry.Shown(at, Panel.Image(ContentPath("Lieux/Lin'an.jpg"))),
            JournalEntry.Shown(at, Panel.Info),
            JournalEntry.Shown(at, Panel.Black),
            JournalEntry.Ended(at),
        ).forEach { entry -> assertEquals(entry, roundTrip(entry)) }
    }

    /** One line per thing the players saw, readable in a Git diff. */
    @Test
    fun `a line reads as an instant, a kind and a path`() {
        val line = JournalCodec.encode(JournalEntry.Shown(at, Panel.Note(ContentPath("PNJs/Yue Fei.md"))))

        assertEquals("2026-09-26T18:12:03Z\tnote\tPNJs/Yue Fei.md", line)
    }

    @Test
    fun `times are kept to the second`() {
        val entry = JournalCodec.decode(JournalCodec.encode(JournalEntry.Ended(at + 999))!!)

        assertEquals(at, entry?.atMillis)
    }

    @Test
    fun `the header, blank lines and anything unknown are skipped, not fatal`() {
        val text = listOf(
            JournalCodec.FILE_HEADER,
            "",
            "2026-09-26T18:12:03Z\tinfo",
            "not a date\tinfo",
            "2026-09-26T18:13:00Z\tsomething-from-a-later-version",
            "2026-09-26T18:14:00Z\tnote",
            "2026-09-26T18:15:00Z\tend",
        ).joinToString("\n")

        assertEquals(
            listOf(JournalEntry.Shown(at, Panel.Info), JournalEntry.Ended(at + 177_000)),
            JournalCodec.decodeAll(text),
        )
    }

    @Test
    fun `a path that cannot sit on one line is not written`() {
        assertNull(JournalCodec.encode(JournalEntry.Shown(at, Panel.Image(ContentPath("bad\tname.png")))))
    }

    @Test
    fun `windows line endings are tolerated`() {
        val entry = JournalCodec.decode("2026-09-26T18:12:03Z\timage\tLieux/Lin'an.jpg\r")

        assertEquals(JournalEntry.Shown(at, Panel.Image(ContentPath("Lieux/Lin'an.jpg"))), entry)
    }
}
