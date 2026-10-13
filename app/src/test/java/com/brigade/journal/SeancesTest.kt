package com.brigade.journal

import com.brigade.content.ContentPath
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeancesTest {

    private val paris = ZoneId.of("Europe/Paris")

    private val yueFei = Panel.Note(ContentPath("PNJs/Yue Fei.md"))
    private val yueFeiPortrait = Panel.Image(ContentPath("Ressources/yue-fei.jpg"))
    private val linAn = Panel.Image(ContentPath("Lieux/Lin'an.jpg"))
    private val qinHui = Panel.Note(ContentPath("PNJs/Qin Hui.md"))

    /** The portrait Yue Fei's note presents. */
    private val noteOf = mapOf(yueFeiPortrait.path to yueFei.path)

    private val saturday = LocalDate.of(2026, 9, 26)
    private val sunday = LocalDate.of(2026, 9, 27)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(day.atTime(hour, minute), paris).toInstant().toEpochMilli()

    private fun min(n: Long) = n * 60_000L

    private fun showing(panel: Panel, start: Long, minutes: Long) = Showing(panel, start, start + min(minutes))

    // ---- Time on screen ------------------------------------------------------------

    @Test
    fun `a panel stays on screen until whatever comes next`() {
        val showings = Seances.showings(
            listOf(
                JournalEntry.Shown(0, linAn),
                JournalEntry.Shown(min(20), yueFei),
                JournalEntry.Ended(min(50)),
            ),
            openUntil = null,
        )

        assertEquals(listOf(min(20), min(30)), showings.map { it.durationMillis })
    }

    /** Live: a test shows up the moment something has been on screen. */
    @Test
    fun `the panel on screen right now counts up to now`() {
        val entries = listOf(JournalEntry.Shown(0, linAn))

        assertTrue(Seances.showings(entries, openUntil = null).isEmpty())
        assertEquals(min(3), Seances.showings(entries, openUntil = min(3)).single().durationMillis)
    }

    // ---- Days run from 06:00 to 06:00 ----------------------------------------------

    @Test
    fun `an evening past midnight belongs to the day it started`() {
        assertEquals(saturday, Seances.dayOf(at(saturday, 20), paris))
        assertEquals(saturday, Seances.dayOf(at(sunday, 1, 47), paris))
        assertEquals(saturday, Seances.dayOf(at(sunday, 5, 59), paris))
        assertEquals(sunday, Seances.dayOf(at(sunday, 6), paris))
    }

    @Test
    fun `a whole evening across midnight is one day`() {
        val byDay = Seances.byDay(
            listOf(showing(linAn, at(saturday, 23), 30), showing(qinHui, at(sunday, 0, 30), 60)),
            noteOf,
            paris,
        )

        assertEquals(listOf(saturday), byDay.keys.toList())
        assertEquals(mapOf(linAn to min(30), qinHui to min(60)), byDay[saturday])
    }

    // ---- What counts ---------------------------------------------------------------

    /** Showing the raw portrait instead of its note is still time spent on that character. */
    @Test
    fun `a raw image is counted under the note that presents it`() {
        val byDay = Seances.byDay(
            listOf(showing(yueFeiPortrait, at(saturday, 20), 10), showing(yueFei, at(saturday, 20, 10), 15)),
            noteOf,
            paris,
        )

        assertEquals(mapOf(yueFei to min(25)), byDay[saturday])
    }

    @Test
    fun `INFO and black are never counted`() {
        val byDay = Seances.byDay(
            listOf(
                showing(Panel.Info, at(saturday, 20), 30),
                showing(linAn, at(saturday, 20, 30), 5),
                showing(Panel.Black, at(saturday, 20, 35), 40),
            ),
            noteOf,
            paris,
        )

        assertEquals(mapOf(linAn to min(5)), byDay[saturday])
    }

    @Test
    fun `a day that only ever showed INFO is not a day`() {
        val byDay = Seances.byDay(listOf(showing(Panel.Info, at(saturday, 20), 30)), noteOf, paris)

        assertTrue(byDay.isEmpty())
    }

    // ---- The three views -----------------------------------------------------------

    @Test
    fun `the ranking puts the longest first`() {
        val ranking = Seances.ranking(mapOf(yueFei to min(5), linAn to min(30), qinHui to min(12)))

        assertEquals(listOf(linAn, qinHui, yueFei), ranking.map { it.first })
    }

    @Test
    fun `six entries make six slices`() {
        val ranking = (1..6).map { Panel.Image(ContentPath("img$it.png")) to min(it.toLong()) }

        val pie = Seances.pie(ranking)

        assertEquals(6, pie.size)
        assertTrue(pie.all { it.panel != null })
    }

    @Test
    fun `past six, the top five and everything else as Autres`() {
        val ranking = (10 downTo 1).map { Panel.Image(ContentPath("img$it.png")) to min(it.toLong()) }

        val pie = Seances.pie(ranking)

        assertEquals(6, pie.size)
        assertEquals(ranking.take(5).map { it.first }, pie.take(5).map { it.panel })
        assertNull(pie.last().panel)
        assertEquals(min(5 + 4 + 3 + 2 + 1), pie.last().millis)
    }

    /** The gaps are part of the answer to «when did this NPC show up?». */
    @Test
    fun `a note's history has a bar for every visible day, empty ones included`() {
        val byDay = mapOf<LocalDate, Map<Panel, Long>>(
            saturday to mapOf(yueFei to min(30)),
            sunday to mapOf(linAn to min(10)),
        )

        assertEquals(
            listOf(saturday to min(30), sunday to 0L),
            Seances.history(yueFei, byDay, listOf(saturday, sunday)),
        )
    }

    @Test
    fun `hidden days leave every view`() {
        val byDay = mapOf<LocalDate, Map<Panel, Long>>(
            sunday to mapOf(linAn to min(10)),
            saturday to mapOf(yueFei to min(30)),
        )

        assertEquals(listOf(saturday, sunday), Seances.visibleDays(byDay, emptySet()))
        assertEquals(listOf(sunday), Seances.visibleDays(byDay, setOf(saturday)))
    }

    // ---- Hidden days on disk -------------------------------------------------------

    @Test
    fun `hidden days survive the file`() {
        val days = setOf(sunday, saturday)

        assertEquals(days, HiddenDays.decode(HiddenDays.encode(days)))
    }

    @Test
    fun `the file is a plain list of dates, in order`() {
        assertEquals(
            "{\n  \"version\": 1,\n  \"hidden_days\": [\"2026-09-26\", \"2026-09-27\"]\n}\n",
            HiddenDays.encode(setOf(sunday, saturday)),
        )
    }

    /** Edited by hand, reformatted, or from a later version: the dates are still the dates. */
    @Test
    fun `any dates in the file are read, whatever surrounds them`() {
        assertEquals(setOf(saturday), HiddenDays.decode("hidden: 2026-09-26, and 2026-13-45 is not a date"))
        assertTrue(HiddenDays.decode("").isEmpty())
    }

    // ---- Durations -----------------------------------------------------------------

    @Test
    fun `durations read as seconds, minutes, or hours and minutes`() {
        fun d(millis: Long) = Seances.formatDuration(millis, "%1\$d h %2\$02d", "%1\$d min", "%1\$d s", Locale.FRENCH)

        assertEquals("45 s", d(45_000))
        assertEquals("1 min", d(59_600))
        assertEquals("12 min", d(min(12) + 10_000))
        assertEquals("1 h 00", d(min(59) + 50_000))
        assertEquals("1 h 05", d(min(65)))
    }
}
