package com.brigade.journal

import com.brigade.content.ContentId
import com.brigade.content.ContentPath
import com.brigade.presentation.NoteBar
import com.brigade.presentation.PresentationState
import com.brigade.presentation.PresentationStore
import com.brigade.presentation.Scene
import com.brigade.presentation.SceneMode
import com.brigade.presentation.ViewportGesture
import com.brigade.presentation.VisualPresentation
import com.brigade.presentation.VisualSource
import com.brigade.presentation.toSnapshot
import com.brigade.presentation.toState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {

    private val portraitId = ContentId("doc://yue-fei-jpg")
    private val mapId = ContentId("doc://lin-an-jpg")

    private val yueFeiNote = ContentPath("PNJs/Yue Fei.md")
    private val linAn = ContentPath("Lieux/Lin'an.jpg")

    /** A recorder over a real store, writing to a list. */
    private class Rig(scope: TestScope) {
        var now = 1_000_000L
        val store = PresentationStore()
        val attached = MutableStateFlow(false)
        val entries = mutableListOf<JournalEntry>()
        val heartbeats = mutableListOf<Long>()
        val recorder = SessionRecorder(
            clock = { now },
            append = { entries += it },
            heartbeat = { heartbeats += it },
        )

        init {
            recorder.start(scope.backgroundScope, store.state, attached)
        }

        val panels: List<Panel?>
            get() = entries.map { (it as? JournalEntry.Shown)?.panel }
    }

    private fun TestScope.rig() = Rig(this).also { runCurrent() }

    // ---- When it records -----------------------------------------------------------

    /** Recalling slots at home with no second screen is preparation, not a session. */
    @Test
    fun `nothing is recorded without a player display`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        runCurrent()

        assertTrue(rig.entries.isEmpty())
    }

    @Test
    fun `plugging in records what is already up, and unplugging ends it`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        rig.attached.value = true
        runCurrent()
        rig.now += 60_000
        rig.attached.value = false
        runCurrent()

        assertEquals(
            listOf(JournalEntry.Shown(1_000_000L, Panel.Image(linAn)), JournalEntry.Ended(1_060_000L)),
            rig.entries,
        )
        assertFalse(rig.recorder.isOpen)
    }

    @Test
    fun `each thing shown is one entry, whatever path it took`() = runTest {
        val rig = rig()
        rig.attached.value = true
        runCurrent()

        rig.store.showNote(portraitId, NoteBar(name = "Yue Fei"), origin = yueFeiNote)
        runCurrent()
        rig.store.setInfoMode(true)
        runCurrent()
        rig.store.setInfoMode(false)
        runCurrent()

        assertEquals(
            listOf(Panel.Black, Panel.Note(yueFeiNote), Panel.Info, Panel.Note(yueFeiNote)),
            rig.panels,
        )
    }

    /** A pinch updates the state at gesture rate; the recap must not see a single one. */
    @Test
    fun `panning, zooming and the timer are not new panels`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        rig.attached.value = true
        runCurrent()

        rig.store.panZoom(ViewportGesture(zoomChange = 2f))
        rig.store.panZoom(ViewportGesture(panX = 0.1f))
        rig.store.startTimer(5)
        rig.store.resetViewport()
        runCurrent()

        assertEquals(1, rig.entries.size)
    }

    @Test
    fun `re-tapping the live slot is not a new panel`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        rig.attached.value = true
        runCurrent()

        rig.store.show(mapId, origin = linAn)
        runCurrent()

        assertEquals(1, rig.entries.size)
    }

    @Test
    fun `closing ends the open panel once`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        rig.attached.value = true
        runCurrent()

        rig.recorder.close()
        rig.recorder.close()

        assertEquals(1, rig.entries.count { it is JournalEntry.Ended })
    }

    // ---- Crash safety --------------------------------------------------------------

    @Test
    fun `a heartbeat is left every minute while something is on screen`() = runTest {
        val rig = rig()
        rig.store.show(mapId, origin = linAn)
        rig.attached.value = true
        runCurrent()
        val atStart = rig.heartbeats.size

        rig.now += 60_000
        advanceTimeBy(SessionRecorder.HEARTBEAT_MILLIS + 1)
        runCurrent()

        assertTrue(rig.heartbeats.size > atStart)
        assertEquals(rig.now, rig.heartbeats.last())
    }

    @Test
    fun `a panel left open by a crash ends at the last heartbeat`() {
        val journal = listOf(JournalEntry.Shown(1_000L, Panel.Image(linAn)))

        assertEquals(JournalEntry.Ended(61_000L), SessionRecorder.closeDangling(journal, lastHeartbeat = 61_000L))
    }

    @Test
    fun `a crash before the first heartbeat never ends a panel before it began`() {
        val journal = listOf(JournalEntry.Shown(5_000L, Panel.Image(linAn)))

        assertEquals(JournalEntry.Ended(5_000L), SessionRecorder.closeDangling(journal, lastHeartbeat = 1_000L))
        assertEquals(JournalEntry.Ended(5_000L), SessionRecorder.closeDangling(journal, lastHeartbeat = null))
    }

    @Test
    fun `a journal that ended cleanly needs nothing`() {
        val journal = listOf(JournalEntry.Shown(1_000L, Panel.Info), JournalEntry.Ended(1_000L))

        assertNull(SessionRecorder.closeDangling(journal, lastHeartbeat = 99_000L))
        assertNull(SessionRecorder.closeDangling(emptyList(), lastHeartbeat = 99_000L))
    }

    // ---- Naming the panel ----------------------------------------------------------

    @Test
    fun `a note is named by its path, and so is a raw image`() {
        fun visual(origin: ContentPath, note: NoteBar?) = PresentationState(
            scene = Scene(visual = VisualPresentation(VisualSource.Image(portraitId)), note = note, origin = origin),
        ).panel()

        assertEquals(Panel.Note(yueFeiNote), visual(yueFeiNote, NoteBar(name = "Yue Fei")))
        assertEquals(Panel.Image(linAn), visual(linAn, null))
    }

    /** INFO is also the blank control, so it is logged as INFO whether or not a panel is set up. */
    @Test
    fun `INFO is INFO, and an empty scene is black`() {
        assertEquals(Panel.Info, PresentationState(scene = Scene(mode = SceneMode.Info)).panel())
        assertEquals(Panel.Black, PresentationState().panel())
    }

    @Test
    fun `a picture whose origin is unknown is not named at all`() {
        val state = PresentationState(scene = Scene(visual = VisualPresentation(VisualSource.Image(mapId))))

        assertNull(state.panel())
    }

    /** A relaunch mid-session comes up on INFO; leaving it must still name the picture. */
    @Test
    fun `the origin survives a relaunch`() {
        val store = PresentationStore()
        store.showNote(portraitId, NoteBar(name = "Yue Fei"), origin = yueFeiNote)

        val restored = store.state.value.toSnapshot().toState(startInInfo = false)

        assertEquals(yueFeiNote, restored.scene.origin)
        assertEquals(Panel.Note(yueFeiNote), restored.panel())
    }
}
