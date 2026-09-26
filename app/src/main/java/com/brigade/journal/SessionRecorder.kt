package com.brigade.journal

import com.brigade.presentation.PresentationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** When the recorder last knew a panel was still on screen. See [SessionRecorder]. */
interface HeartbeatStore {
    fun load(campaign: String): Long?
    fun save(campaign: String, atMillis: Long)
}

/**
 * Writes the journal: one entry each time what the players see changes.
 *
 * ### Only while a player display is attached
 *
 * The recap measures time *on the players' screen*. Recalling slots at home with no second
 * display is preparation, and recording it would pad every session with rehearsal. So
 * nothing is written while the preview is the only screen, and unplugging the cable ends the
 * open panel on the spot.
 *
 * ### Crash safety
 *
 * A panel is written when it *starts*, so a Brigade killed mid-session would leave its last
 * one with no end. The recorder therefore leaves a heartbeat every minute, and the next
 * launch closes a dangling panel at the last heartbeat — see [closeDangling]. At worst a
 * minute of the last panel is lost, rather than the panel.
 *
 * It derives everything from the state, rather than being told at each call site. Slots,
 * *Afficher* and INFO all end up here without any of them knowing the journal exists.
 */
class SessionRecorder(
    private val clock: () -> Long,
    private val append: suspend (JournalEntry) -> Unit,
    private val heartbeat: (atMillis: Long) -> Unit,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {

    /** True while a panel is on the players' screen and its entry has no end yet. */
    @Volatile
    var isOpen: Boolean = false
        private set

    fun start(
        scope: CoroutineScope,
        state: Flow<PresentationState>,
        attached: Flow<Boolean>,
    ): Job = scope.launch {
        launch {
            while (true) {
                delay(heartbeatMillis)
                if (isOpen) heartbeat(clock())
            }
        }

        // The state changes at gesture rate during a pinch; panel() ignores framing, so
        // distinctUntilChanged leaves one emission per thing actually shown.
        combine(attached, state) { on, current -> if (on) current.panel() else null }
            .distinctUntilChanged()
            .collect { record(it) }
    }

    /** Ends the open panel now, if there is one. Called before recording stops for good. */
    suspend fun close() {
        if (!isOpen) return
        append(JournalEntry.Ended(clock()))
        isOpen = false
    }

    private suspend fun record(panel: Panel?) {
        val now = clock()
        if (panel == null) {
            if (isOpen) append(JournalEntry.Ended(now))
            isOpen = false
        } else {
            append(JournalEntry.Shown(now, panel))
            isOpen = true
            heartbeat(now)
        }
    }

    companion object {

        const val HEARTBEAT_MILLIS: Long = 60_000L

        /**
         * The entry that ends a panel left open by a previous run, or null when none is.
         *
         * Ended at the last heartbeat, never before the panel began: a crash before the first
         * heartbeat costs that panel its time, not the journal its order.
         *
         * The last *line*, not the latest time: the journal is only ever appended to, and at
         * one-second resolution a panel and its end can share a timestamp.
         */
        fun closeDangling(entries: List<JournalEntry>, lastHeartbeat: Long?): JournalEntry.Ended? {
            val last = entries.lastOrNull() as? JournalEntry.Shown ?: return null
            return JournalEntry.Ended(maxOf(lastHeartbeat ?: last.atMillis, last.atMillis))
        }
    }
}
