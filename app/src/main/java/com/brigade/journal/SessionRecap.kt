package com.brigade.journal

import com.brigade.content.ContentPath

/** One uninterrupted stretch of [panel] on the players' screen. */
data class Showing(val panel: Panel, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * One panel's turn in a session's timeline, after grouping.
 *
 * [onScreenMillis] can be less than the wall time it spans: a panel that stayed up across an
 * unplugged cable is one appearance, but only the time the players could see it counts.
 */
data class Appearance(val panel: Panel, val startMillis: Long, val onScreenMillis: Long)

/** An evening at the table, in the order things were shown. Never empty. */
data class Session(val showings: List<Showing>) {

    val startMillis: Long get() = showings.first().startMillis
    val endMillis: Long get() = showings.last().endMillis
    val spanMillis: Long get() = endMillis - startMillis

    /**
     * The timeline: each raw image filed under its note, then neighbours showing the same
     * thing merged — a raw portrait followed by its own note, or a slot re-tapped, is one
     * appearance and not two.
     */
    fun appearances(noteOf: Map<ContentPath, ContentPath>): List<Appearance> {
        val merged = mutableListOf<Appearance>()
        showings.forEach { showing ->
            val panel = showing.panel.attributed(noteOf)
            val last = merged.lastOrNull()
            if (last != null && last.panel == panel) {
                merged[merged.lastIndex] = last.copy(onScreenMillis = last.onScreenMillis + showing.durationMillis)
            } else {
                merged += Appearance(panel, showing.startMillis, showing.durationMillis)
            }
        }
        return merged
    }

    /**
     * Total time on screen per note or image, longest first — the proxy for what the
     * session was about.
     *
     * INFO and black are left out: they are the GM preparing or pausing, and ranking them
     * would put "break" at the top of every evening.
     */
    fun totals(noteOf: Map<ContentPath, ContentPath>): List<Pair<Panel, Long>> {
        val totals = LinkedHashMap<Panel, Long>()
        showings.forEach { showing ->
            val panel = showing.panel.attributed(noteOf)
            if (panel is Panel.Info || panel is Panel.Black) return@forEach
            totals[panel] = (totals[panel] ?: 0L) + showing.durationMillis
        }
        // Stable: ties stay in order of first appearance.
        return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

object SessionRecap {

    /**
     * Longer than this with nothing on screen and the next thing shown opens a new session.
     *
     * A gap rather than a calendar day, because sessions run past midnight — and six hours is
     * longer than any break at a table and shorter than any night between two sessions.
     */
    const val NEW_SESSION_GAP_MILLIS: Long = 6 * 60 * 60 * 1000L

    /** Shorter than this, from first thing shown to last, is a test and not a session. */
    const val MIN_SESSION_MILLIS: Long = 30 * 60 * 1000L

    /**
     * Turns the journal into stretches of time on screen.
     *
     * Each shown panel lasts until the next entry, whatever it is.
     *
     * @param openUntil when recording is live and the last entry is still on screen, the time
     * to end it at. Null drops an unterminated last entry, whose end is unknown.
     */
    fun showings(entries: List<JournalEntry>, openUntil: Long?): List<Showing> {
        // Stable sort, in case the clock was ever set back mid-session.
        val sorted = entries.sortedBy { it.atMillis }
        val result = mutableListOf<Showing>()

        sorted.forEachIndexed { index, entry ->
            if (entry !is JournalEntry.Shown) return@forEachIndexed
            val end = sorted.getOrNull(index + 1)?.atMillis ?: openUntil ?: return@forEachIndexed
            if (end > entry.atMillis) result += Showing(entry.panel, entry.atMillis, end)
        }
        return result
    }

    /** Splits stretches into sessions at every long gap, and drops the ones too short to count. */
    fun sessions(showings: List<Showing>): List<Session> {
        val groups = mutableListOf<MutableList<Showing>>()

        showings.forEach { showing ->
            val current = groups.lastOrNull()
            if (current == null || showing.startMillis - current.last().endMillis > NEW_SESSION_GAP_MILLIS) {
                groups += mutableListOf(showing)
            } else {
                current += showing
            }
        }

        return groups.map { Session(it) }.filter { it.spanMillis >= MIN_SESSION_MILLIS }
    }
}
