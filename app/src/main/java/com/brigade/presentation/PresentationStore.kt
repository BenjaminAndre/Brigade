package com.brigade.presentation

import com.brigade.content.ContentId
import com.brigade.content.ContentPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the GM surface is allowed to do to the presentation. */
interface PresentationController {

    val state: StateFlow<PresentationState>

    /**
     * Presents an image directly. Clears any note bar — a plain image came from no note.
     *
     * @param origin the image's campaign-relative path, for the session recap.
     */
    fun show(id: ContentId, fromSlot: SlotId? = null, origin: ContentPath? = null)

    /**
     * Presents a Markdown note: the players see [imageId] (black when null), and the GM bar
     * shows [note].
     *
     * @param origin the note's campaign-relative path, for the session recap.
     */
    fun showNote(imageId: ContentId?, note: NoteBar, fromSlot: SlotId? = null, origin: ContentPath? = null)

    /**
     * Shows or hides the full-screen campaign info panel.
     *
     * This is also the blank control: a campaign with no info configured projects to black,
     * which is the "nothing specific, I am preparing" state.
     */
    fun setInfoMode(on: Boolean)

    /** Lights an incense timer of [minutes], replacing any already burning. */
    fun startTimer(minutes: Int)

    /** Lengthens the burning timer, or lights a fresh one if it has already burnt out. */
    fun extendTimer()

    fun clearTimer()

    /** One step of the GM's pinch-and-drag on the preview. Ignored unless a picture is live. */
    fun panZoom(gesture: ViewportGesture)

    /** Back to the whole picture — the double-tap. */
    fun resetViewport()

    fun setScaling(mode: ScalingMode)

    /** Drops the scene without changing whether the display is blanked. */
    fun clearScene()

    fun restore(state: PresentationState)
}

/**
 * Holds [PresentationState] for the life of the process.
 *
 * **Owned by the application graph, never by a ViewModel.** The player window is an
 * `android.app.Presentation` with its own lifecycle: it can outlive an Activity
 * recreation. State scoped to an Activity would stop emitting underneath it, freezing
 * the players' screen on a stale frame while the GM's UI moves on — a failure you find
 * at the table, not in development.
 *
 * Note this is a plain [MutableStateFlow] and not `stateIn(scope, WhileSubscribed())`.
 * A sharing policy would restart the upstream when collectors momentarily drop to zero,
 * which is exactly what happens while the Activity is being recreated.
 */
class PresentationStore(
    initial: PresentationState = PresentationState(),
    /**
     * The campaign's configured transition. A supplier rather than a constructor value so
     * it can change when a different campaign folder is opened — and a lambda rather than
     * a method on [PresentationController], which stays a list of things a *GM* can do,
     * not a place to put configuration.
     */
    private val spec: () -> TransitionSpec = { TransitionSpec.CUT },
    private val nowNanos: () -> Long = System::nanoTime,
) : PresentationController {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PresentationState> = _state.asStateFlow()

    override fun show(id: ContentId, fromSlot: SlotId?, origin: ContentPath?) =
        present(VisualSource.Image(id), note = null, fromSlot = fromSlot, origin = origin)

    override fun showNote(imageId: ContentId?, note: NoteBar, fromSlot: SlotId?, origin: ContentPath?) =
        present(
            source = if (imageId == null) VisualSource.None else VisualSource.Image(imageId),
            note = note,
            fromSlot = fromSlot,
            origin = origin,
        )

    private fun present(
        source: VisualSource,
        note: NoteBar?,
        fromSlot: SlotId?,
        origin: ContentPath?,
    ) = change {
        it.copy(
            scene = it.scene.copy(
                mode = SceneMode.Visual,
                // Every recall comes up whole. A zoom belongs to the moment the GM pointed at
                // something, not to the picture — carried over, it would frame the next image
                // on a corner chosen for a different one.
                visual = it.scene.visual.copy(source = source, viewport = Viewport.FULL),
                // Always assigned, never merged: showing a plain image must clear a bar left
                // over from the note before it. The same goes for its origin.
                note = note,
                origin = origin,
            ),
            liveSlot = fromSlot,
        )
    }

    /**
     * Loads the campaign info panel.
     *
     * Not on [PresentationController]: that interface is the list of things a *GM* can do,
     * and this is data arriving from `Campagne.md`. While the scene is showing an image it
     * changes no frame and so stamps no transition; re-reading the file *while* the panel is
     * up does change the frame, and dissolving to the updated panel is the right thing.
     */
    fun setInfo(panel: InfoPanel?, background: ContentId?) = change {
        it.copy(scene = it.scene.copy(info = panel, infoBackground = background))
    }

    override fun startTimer(minutes: Int) = change {
        it.copy(scene = it.scene.copy(overlay = TimerOverlay.ofMinutes(minutes, nowNanos())))
    }

    override fun extendTimer() = change {
        val burning = it.scene.overlay ?: return@change it
        val now = nowNanos()

        // Extending a stick that has already burnt out lights a fresh one instead of adding
        // to a duration the elapsed time has long overtaken — otherwise "another minute" on
        // an expired timer would do nothing visible at all.
        val extended = if (burning.isBurntOutAt(now)) {
            TimerOverlay(startNanos = now, durationMillis = TimerOverlay.EXTEND_MILLIS)
        } else {
            burning.extendedBy(TimerOverlay.EXTEND_MILLIS)
        }

        it.copy(scene = it.scene.copy(overlay = extended))
    }

    override fun clearTimer() = change { it.copy(scene = it.scene.copy(overlay = null)) }

    override fun setInfoMode(on: Boolean) = change {
        it.copy(
            scene = it.scene.copy(mode = if (on) SceneMode.Info else SceneMode.Visual),
            // Deliberately does NOT clear liveSlot, so "INFO then back returns to the same
            // slot" comes free. The control bar's highlight is computed from state, so
            // nothing lights up wrongly meanwhile.
        )
    }

    override fun panZoom(gesture: ViewportGesture) = reframe { it.applied(gesture) }

    override fun resetViewport() = reframe { Viewport.FULL }

    /**
     * Changes the framing of the live picture, bypassing [change].
     *
     * Reframing is neither a new intent nor a new picture, so it bumps no revision and stamps
     * no transition. It is also read-modify-write *inside* the update: a pinch reports steps
     * faster than the GM surface recomposes, and a step computed from a viewport the UI read
     * earlier would silently drop the one before it.
     *
     * Nothing to frame on INFO or black, so it does nothing there rather than storing a zoom
     * nobody can see.
     */
    private fun reframe(block: (Viewport) -> Viewport) {
        _state.update { current ->
            if (current.frame() !is Frame.Picture) return@update current
            val visual = current.scene.visual
            current.copy(scene = current.scene.copy(visual = visual.copy(viewport = block(visual.viewport))))
        }
    }

    override fun setScaling(mode: ScalingMode) = change {
        it.copy(scene = it.scene.copy(visual = it.scene.visual.copy(scaling = mode)))
    }

    override fun clearScene() = change {
        it.copy(
            // copy, not a fresh Scene: the info panel is campaign-derived, not session
            // state, so clearing what is *shown* must not discard what was *loaded*.
            scene = it.scene.copy(
                mode = SceneMode.Visual,
                visual = it.scene.visual.copy(source = VisualSource.None, viewport = Viewport.FULL),
                note = null,
                origin = null,
            ),
            liveSlot = null,
        )
    }

    override fun restore(state: PresentationState) {
        // A restored state never arrives mid-dissolve.
        _state.value = state.copy(transition = null)
    }

    /**
     * Applies [block], bumps the revision, and stamps a transition when what the players
     * can see actually changed.
     */
    private fun change(block: (PresentationState) -> PresentationState) {
        _state.update { current ->
            val next = block(current)
            next.copy(
                revision = current.revision + 1,
                transition = transitionFor(current, next),
            )
        }
    }

    private fun transitionFor(
        current: PresentationState,
        next: PresentationState,
    ): ActiveTransition? {
        // Nothing the players can see moved — a scaling tweak, a slot re-tap on the image
        // already showing. Leave any running dissolve alone rather than restarting it.
        //
        // Compared unframed, so re-tapping the live slot while zoomed snaps back to the whole
        // picture exactly as a double-tap does, instead of washing a map into itself.
        if (current.frame().unframed() == next.frame().unframed()) return current.transition

        val chosen = spec()
        if (chosen.kind == TransitionKind.Cut || chosen.durationMillis <= 0) return null

        return ActiveTransition(
            from = outgoingFrame(current),
            spec = chosen,
            startNanos = nowNanos(),
            seed = (current.revision + 1).toInt(),
        )
    }

    /**
     * What the new dissolve should start *from*.
     *
     * When one is already running — the GM taps slot 3 then slot 5 immediately, which will
     * happen at a real table — carry over whichever layer the players are mostly seeing.
     * Worst case is a single-frame step at the midpoint. Always taking the interrupted
     * target instead pops to an image the GM has already moved past, on every interruption.
     */
    private fun outgoingFrame(current: PresentationState): Frame {
        val running = current.transition ?: return current.frame()
        return if (running.progressAt(nowNanos()) < 0.5f) running.from else current.frame()
    }
}
