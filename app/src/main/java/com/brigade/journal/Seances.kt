package com.brigade.journal

import com.brigade.content.ContentId
import com.brigade.content.ContentPath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One uninterrupted stretch of [panel] on the players' screen. */
data class Showing(val panel: Panel, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * One slice of a day's pie: a note or image, or — [panel] null — everything past the top five.
 */
data class PieSlice(val panel: Panel?, val millis: Long)

/**
 * Everything the Séances screen draws.
 *
 * @param byDay time on screen per note or image, per day — see [Seances.byDay].
 * @param hidden the days the GM switched off, applied to every view.
 * @param pictures what each note or image is drawn with; absent for a note linking no image,
 * which is then drawn as its name.
 */
data class SeancesData(
    val byDay: Map<LocalDate, Map<Panel, Long>>,
    val hidden: Set<LocalDate>,
    val pictures: Map<Panel, ContentId>,
) {
    val visibleDays: List<LocalDate> get() = Seances.visibleDays(byDay, hidden)

    companion object {
        val EMPTY = SeancesData(emptyMap(), emptySet(), emptyMap())
    }
}

/**
 * What the Séances screen shows, computed from the journal.
 *
 * ### A day runs from 06:00 to 06:00
 *
 * The GM never starts a game before six in the morning and never plays past it, so an
 * evening that runs past midnight belongs to the day it started on — with nothing to track
 * and no gap to tune. And they never prepare and play on the same day, so a day *is* a
 * session.
 *
 * ### Content only
 *
 * INFO and black are the GM preparing or pausing. They are recorded, but never shown: in a
 * chart of what an evening was about, «break» would only crowd out the answer.
 */
object Seances {

    /** The hour a day starts at, local time. See the class. */
    const val DAY_STARTS_AT_HOUR: Long = 6

    /** The pie shows at most this many slices; past it, the smallest merge into «Autres». */
    const val MAX_SLICES: Int = 6

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

    /** The day [millis] belongs to: its local date, six hours earlier. */
    fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

    /**
     * Time on screen per note or image, for every day something was shown.
     *
     * Raw images are filed under the note that presents them ([noteOf]), so a portrait shown
     * by mistake instead of its note still counts as time on that character. A stretch is
     * filed under the day it started on. Days that only ever showed INFO or black are left
     * out — there was nothing to see.
     */
    fun byDay(
        showings: List<Showing>,
        noteOf: Map<ContentPath, ContentPath>,
        zone: ZoneId,
    ): Map<LocalDate, Map<Panel, Long>> {
        val days = sortedMapOf<LocalDate, MutableMap<Panel, Long>>()
        showings.forEach { showing ->
            val panel = showing.panel.attributed(noteOf)
            if (panel is Panel.Info || panel is Panel.Black) return@forEach
            val day = days.getOrPut(dayOf(showing.startMillis, zone)) { LinkedHashMap() }
            day[panel] = (day[panel] ?: 0L) + showing.durationMillis
        }
        return days
    }

    /** A day's notes and images, longest on screen first. Ties keep the order they were shown in. */
    fun ranking(day: Map<Panel, Long>): List<Pair<Panel, Long>> =
        day.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /**
     * The pie: every entry when there are at most [MAX_SLICES], otherwise the top five and one
     * «Autres» slice holding the rest — a sixth slice is worth more as a total than as one more
     * thin wedge.
     */
    fun pie(ranking: List<Pair<Panel, Long>>): List<PieSlice> {
        if (ranking.size <= MAX_SLICES) return ranking.map { (panel, millis) -> PieSlice(panel, millis) }
        val top = ranking.take(MAX_SLICES - 1).map { (panel, millis) -> PieSlice(panel, millis) }
        return top + PieSlice(null, ranking.drop(MAX_SLICES - 1).sumOf { it.second })
    }

    /** Recorded days minus the ones the GM switched off, oldest first. */
    fun visibleDays(byDay: Map<LocalDate, Map<Panel, Long>>, hidden: Set<LocalDate>): List<LocalDate> =
        byDay.keys.filterNot { it in hidden }.sorted()

    /**
     * When was [subject] on screen: its time on each visible day, zero included — a day it was
     * absent from is part of the answer.
     */
    fun history(
        subject: Panel,
        byDay: Map<LocalDate, Map<Panel, Long>>,
        days: List<LocalDate>,
    ): List<Pair<LocalDate, Long>> = days.map { day -> day to (byDay[day]?.get(subject) ?: 0L) }

    /**
     * «45 s», «12 min», «1 h 05», rounded to the nearest unit shown.
     *
     * The formats come from string resources (§21): [hours] takes hours then minutes.
     */
    fun formatDuration(millis: Long, hours: String, minutes: String, seconds: String, locale: Locale): String {
        val totalSeconds = (millis + 500) / 1000
        if (totalSeconds < 60) return String.format(locale, seconds, totalSeconds)

        val totalMinutes = (totalSeconds + 30) / 60
        if (totalMinutes < 60) return String.format(locale, minutes, totalMinutes)

        return String.format(locale, hours, totalMinutes / 60, totalMinutes % 60)
    }
}

/**
 * Which recorded days the Séances screen leaves out, so the GM can keep only campaign days —
 * a test at home, or another campaign's evening in the same folder, would otherwise sit
 * among them.
 *
 * Stored as `.brigade/seances.json`, a plain list of dates:
 *
 * ```
 * { "version": 1, "hidden_days": ["2026-09-20", "2026-10-02"] }
 * ```
 *
 * Read by picking every ISO date out of the text, so a file edited by hand, reformatted, or
 * written by a later version still yields its dates rather than nothing.
 */
object HiddenDays {

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun decode(text: String): Set<LocalDate> =
        DATE.findAll(text).mapNotNull { runCatching { LocalDate.parse(it.value) }.getOrNull() }.toSet()

    fun encode(days: Set<LocalDate>): String {
        val list = days.sorted().joinToString(", ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"hidden_days\": [$list]\n}\n"
    }
}
