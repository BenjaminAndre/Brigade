package com.brigade.presentation

import com.brigade.content.ContentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    private val jadeFox = ContentId("doc://jade-fox")
    private val taverne = ContentId("doc://taverne")

    private val eps = 0.0001f

    private fun pinch(by: Float, atX: Float = 0.5f, atY: Float = 0.5f) =
        ViewportGesture(zoomChange = by, focusX = atX, focusY = atY)

    private fun drag(x: Float = 0f, y: Float = 0f) = ViewportGesture(panX = x, panY = y)

    /** Where on the surface, as a fraction, a normalised point of the picture is drawn. */
    private fun Viewport.screenX(pointX: Float) = 0.5f + (pointX - centerX) * zoom
    private fun Viewport.screenY(pointY: Float) = 0.5f + (pointY - centerY) * zoom

    // ---- Zoom ----------------------------------------------------------------------

    @Test
    fun `full view is zoom one, centred`() {
        assertTrue(Viewport.FULL.isFull)
        assertEquals(Viewport(1f, 0.5f, 0.5f), Viewport.FULL)
    }

    @Test
    fun `zoom is capped at the decode multiple, so it never goes soft`() {
        val zoomed = Viewport.FULL.applied(pinch(10f))
        assertEquals(Viewport.MAX_ZOOM, zoomed.zoom, eps)
    }

    @Test
    fun `pinching out stops at the whole picture rather than shrinking it`() {
        val zoomed = Viewport.FULL.applied(pinch(2f)).applied(pinch(0.1f))

        assertEquals(1f, zoomed.zoom, eps)
        assertTrue(zoomed.isFull)
    }

    /** What makes a pinch feel like taking hold of the map rather than zooming its middle. */
    @Test
    fun `the point under the fingers stays under the fingers`() {
        val start = Viewport(zoom = 1.2f, centerX = 0.5f, centerY = 0.5f)
        val focusX = 0.7f
        val focusY = 0.35f

        // The picture point currently drawn under the fingers.
        val pointX = start.centerX + (focusX - 0.5f) / start.zoom
        val pointY = start.centerY + (focusY - 0.5f) / start.zoom

        val zoomed = start.applied(pinch(1.4f, focusX, focusY))

        assertEquals(focusX, zoomed.screenX(pointX), eps)
        assertEquals(focusY, zoomed.screenY(pointY), eps)
    }

    @Test
    fun `pinching at the corner zooms into that corner`() {
        val zoomed = Viewport.FULL.applied(pinch(2f, atX = 1f, atY = 1f))

        assertEquals(0.75f, zoomed.centerX, eps)
        assertEquals(0.75f, zoomed.centerY, eps)
    }

    // ---- Pan -----------------------------------------------------------------------

    @Test
    fun `the whole picture cannot be panned`() {
        val panned = Viewport.FULL.applied(drag(x = 0.3f, y = -0.2f))
        assertEquals(Viewport.FULL, panned)
    }

    /** The picture follows the finger, so the view moves the opposite way. */
    @Test
    fun `dragging right moves the view left`() {
        val zoomed = Viewport(zoom = 2f)
        val panned = zoomed.applied(drag(x = 0.1f))

        // A tenth of the surface at zoom 2 is a twentieth of the picture.
        assertEquals(0.45f, panned.centerX, eps)
        assertEquals(0.5f, panned.centerY, eps)
    }

    @Test
    fun `panning stops at the edge of the surface`() {
        val panned = Viewport(zoom = 2f).applied(drag(x = -5f, y = 5f))

        // At zoom 2 the view is half the surface wide, so its centre stays within a quarter
        // of either edge.
        assertEquals(0.75f, panned.centerX, eps)
        assertEquals(0.25f, panned.centerY, eps)
    }

    @Test
    fun `zooming back out recentres, so full view is always the whole picture`() {
        val cornered = Viewport.FULL.applied(pinch(2f, atX = 1f, atY = 0f))
        val back = cornered.applied(pinch(0.5f, atX = 0f, atY = 1f))

        assertEquals(Viewport.FULL, back)
    }

    // ---- The store -----------------------------------------------------------------

    @Test
    fun `a pinch on the live picture reaches the frame both windows draw`() {
        val store = PresentationStore()
        store.show(jadeFox)
        store.panZoom(pinch(2f, atX = 1f, atY = 1f))

        val frame = store.state.value.frame() as Frame.Picture
        assertEquals(2f, frame.viewport.zoom, eps)
        assertEquals(0.75f, frame.viewport.centerX, eps)
    }

    @Test
    fun `double-tap returns to the whole picture`() {
        val store = PresentationStore()
        store.show(jadeFox)
        store.panZoom(pinch(2f, atX = 0f, atY = 0f))

        store.resetViewport()

        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)
    }

    /** The GM chose this: a slot always means the same picture, framed whole. */
    @Test
    fun `presenting a different picture starts it whole`() {
        val store = PresentationStore()
        store.show(jadeFox)
        store.panZoom(pinch(2f, atX = 1f, atY = 1f))

        store.show(taverne)

        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)
    }

    @Test
    fun `presenting a note starts it whole too`() {
        val store = PresentationStore()
        store.show(jadeFox)
        store.panZoom(pinch(2f))

        store.showNote(taverne, NoteBar(name = "Taverne"))

        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)
    }

    /** INFO covers the picture without replacing it, framing included. */
    @Test
    fun `INFO and back keeps the zoom`() {
        val store = PresentationStore()
        store.show(jadeFox)
        store.panZoom(pinch(2f, atX = 1f, atY = 1f))
        val framed = store.state.value.scene.visual.viewport

        store.setInfoMode(true)
        store.setInfoMode(false)

        assertEquals(framed, store.state.value.scene.visual.viewport)
    }

    @Test
    fun `there is nothing to zoom on INFO or on black`() {
        val store = PresentationStore()
        store.panZoom(pinch(2f))
        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)

        store.show(jadeFox)
        store.setInfoMode(true)
        store.panZoom(pinch(2f))
        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)
    }

    /**
     * A pinch reports a step per input event. A dissolve on each would wash the map into
     * itself continuously; a revision on each would call every finger movement a new intent.
     */
    @Test
    fun `reframing stamps no transition and bumps no revision`() {
        var now = 0L
        val store = PresentationStore(spec = { TransitionSpec.WATERCOLOR }, nowNanos = { now })
        store.show(jadeFox)
        now += 10_000_000_000L
        val before = store.state.value

        store.panZoom(pinch(1.5f))
        store.panZoom(drag(x = 0.1f))
        store.resetViewport()

        assertEquals(before.transition, store.state.value.transition)
        assertEquals(before.revision, store.state.value.revision)
    }

    /** Re-tapping the live slot while zoomed snaps back, exactly like the double-tap. */
    @Test
    fun `re-presenting the zoomed picture resets it without dissolving`() {
        var now = 0L
        val store = PresentationStore(spec = { TransitionSpec.WATERCOLOR }, nowNanos = { now })
        store.show(jadeFox, fromSlot = SlotId(0))
        now += 10_000_000_000L
        store.panZoom(pinch(2f))
        val settled = store.state.value.transition

        store.show(jadeFox, fromSlot = SlotId(0))

        assertEquals(Viewport.FULL, store.state.value.scene.visual.viewport)
        assertEquals(settled, store.state.value.transition)
    }

    /** A picture dissolving out keeps the framing it had, rather than jumping to whole. */
    @Test
    fun `the outgoing picture dissolves out as it was framed`() {
        var now = 0L
        val store = PresentationStore(spec = { TransitionSpec.WATERCOLOR }, nowNanos = { now })
        store.show(jadeFox)
        now += 10_000_000_000L
        store.panZoom(pinch(2f, atX = 1f, atY = 1f))
        val framed = store.state.value.scene.visual.viewport

        store.show(taverne)

        val from = store.state.value.transition?.from as Frame.Picture
        assertEquals(jadeFox, from.id)
        assertEquals(framed, from.viewport)
    }

    @Test
    fun `transitions compare pictures, not framings`() {
        val whole = Frame.Picture(jadeFox, ScalingMode.Fit)
        val zoomed = Frame.Picture(jadeFox, ScalingMode.Fit, Viewport(zoom = 2f))

        assertFalse(whole == zoomed)
        assertEquals(whole.unframed(), zoomed.unframed())
        assertEquals(Frame.Black, Frame.Black.unframed())
    }

    // ---- Persistence ---------------------------------------------------------------

    /** Relaunching onto a corner of a map nobody remembers zooming into would be baffling. */
    @Test
    fun `a zoom is never restored from a snapshot`() {
        val snapshot = PresentationState(
            scene = Scene(
                visual = VisualPresentation(VisualSource.Image(jadeFox), viewport = Viewport(zoom = 2f)),
            ),
        ).toSnapshot()

        val restored = snapshot.toState(startInInfo = false)
        assertEquals(Viewport.FULL, restored.scene.visual.viewport)
        assertNull(restored.scene.overlay)
    }
}
