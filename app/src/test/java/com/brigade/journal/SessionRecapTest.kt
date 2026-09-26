package com.brigade.journal

import com.brigade.content.ContentPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecapTest {

    private val yueFei = Panel.Note(ContentPath("PNJs/Yue Fei.md"))
    private val yueFeiPortrait = Panel.Image(ContentPath("Ressources/yue-fei.jpg"))
    private val linAn = Panel.Image(ContentPath("Lieux/Lin'an.jpg"))
    private val qinHui = Panel.Note(ContentPath("PNJs/Qin Hui.md"))

    /** The portrait Yue Fei's note presents. */
    private val noteOf = mapOf(yueFeiPortrait.path to yueFei.path)

    private fun min(n: Long) = n * 60_000L
    private fun hours(n: Long) = n * 3_600_000L

    private fun shown(atMinute: Long, panel: Panel) = JournalEntry.Shown(min(atMinute), panel)
    private fun ended(atMinute: Long) = JournalEntry.Ended(min(atMinute))

    private fun sessionsOf(vararg entries: JournalEntry, openUntil: Long? = null) =
        SessionRecap.sessions(SessionRecap.showings(entries.toList(), openUntil))

    // ---- Time on screen ------------------------------------------------------------

    @Test
    fun `a panel stays on screen until whatever comes next`() {
        val showings = SessionRecap.showings(
            listOf(shown(0, linAn), shown(20, yueFei), ended(50)),
            openUntil = null,
        )

        assertEquals(listOf(min(20), min(30)), showings.map { it.durationMillis })
    }

    @Test
    fun `nothing counts while the display is gone`() {
        val showings = SessionRecap.showings(
            listOf(shown(0, linAn), ended(10), shown(40, linAn), ended(50)),
            openUntil = null,
        )

        assertEquals(min(20), showings.sumOf { it.durationMillis })
    }

    /** Without an end there is no honest duration — the crash case the heartbeat covers. */
    @Test
    fun `an unterminated last panel is dropped unless recording is live`() {
        val entries = listOf(shown(0, linAn), shown(10, yueFei))

        assertEquals(1, SessionRecap.showings(entries, openUntil = null).size)
        assertEquals(min(25), SessionRecap.showings(entries, openUntil = min(35)).last().durationMillis)
    }

    // ---- What a session is ---------------------------------------------------------

    @Test
    fun `a gap longer than six hours starts a new session`() {
        val sessions = sessionsOf(
            shown(0, linAn), ended(60),
            shown(60 + 6 * 60 + 1, yueFei), ended(60 + 6 * 60 + 61),
        )

        assertEquals(2, sessions.size)
    }

    @Test
    fun `a gap of exactly six hours does not`() {
        val sessions = sessionsOf(
            shown(0, linAn), ended(60),
            shown(60 + 6 * 60, yueFei), ended(60 + 6 * 60 + 60),
        )

        assertEquals(1, sessions.size)
    }

    /** Sessions run past midnight, so a session is a gap rule and never a calendar day. */
    @Test
    fun `a session carries on across midnight and across a break`() {
        // 20:00 to 01:30, with a long dinner break and the cable out for part of it.
        val sessions = sessionsOf(
            shown(20 * 60, linAn), ended(21 * 60),
            shown(22 * 60 + 30, yueFei), shown(24 * 60 + 30, qinHui), ended(25 * 60 + 30),
        )

        assertEquals(1, sessions.size)
        assertEquals(hours(5) + min(30), sessions.single().spanMillis)
    }

    @Test
    fun `under thirty minutes is not a session`() {
        assertTrue(sessionsOf(shown(0, linAn), ended(29)).isEmpty())
        assertEquals(1, sessionsOf(shown(0, linAn), ended(30)).size)
    }

    @Test
    fun `a short test does not drag a real session with it`() {
        val sessions = sessionsOf(
            shown(0, linAn), ended(5),
            shown(hours(24) / 60_000, yueFei), ended(hours(24) / 60_000 + 120),
        )

        assertEquals(listOf(yueFei), sessions.single().showings.map { it.panel })
    }

    // ---- Notes over raw images -----------------------------------------------------

    /** Showing the raw portrait instead of its note is still time spent on that character. */
    @Test
    fun `a raw image is filed under the note that presents it`() {
        val session = sessionsOf(shown(0, yueFeiPortrait), ended(40)).single()

        assertEquals(listOf(yueFei), session.appearances(noteOf).map { it.panel })
    }

    @Test
    fun `an image no note presents stays an image`() {
        val session = sessionsOf(shown(0, linAn), ended(40)).single()

        assertEquals(listOf(linAn), session.appearances(noteOf).map { it.panel })
    }

    @Test
    fun `the raw portrait then its note is one appearance`() {
        val session = sessionsOf(shown(0, yueFeiPortrait), shown(10, yueFei), shown(25, linAn), ended(40)).single()

        val appearances = session.appearances(noteOf)
        assertEquals(listOf(yueFei, linAn), appearances.map { it.panel })
        assertEquals(min(25), appearances.first().onScreenMillis)
        assertEquals(0L, appearances.first().startMillis)
    }

    @Test
    fun `a panel kept up across an unplugged cable counts only the time it was seen`() {
        val session = sessionsOf(shown(0, linAn), ended(20), shown(30, linAn), ended(40)).single()

        val appearance = session.appearances(noteOf).single()
        assertEquals(min(30), appearance.onScreenMillis)
    }

    // ---- Ranking -------------------------------------------------------------------

    @Test
    fun `totals add up returns and rank the longest first`() {
        val session = sessionsOf(
            shown(0, linAn),
            shown(10, yueFei),
            shown(15, linAn),
            shown(35, yueFeiPortrait),
            ended(45),
        ).single()

        assertEquals(
            listOf(linAn to min(30), yueFei to min(15)),
            session.totals(noteOf),
        )
    }

    /** INFO is the GM preparing or pausing; at the top of every evening it would say nothing. */
    @Test
    fun `INFO and black are in the timeline but not the ranking`() {
        val session = sessionsOf(shown(0, Panel.Info), shown(30, linAn), shown(35, Panel.Black), ended(80)).single()

        assertEquals(3, session.appearances(noteOf).size)
        assertEquals(listOf(linAn to min(5)), session.totals(noteOf))
    }
}
