package com.brigade.presentation

import com.brigade.content.ContentId

/**
 * One still picture of the player surface, fully determined.
 *
 * This is the type transitions are *between*, and it is why it exists at all: a
 * cross-dissolve has to hold two of these at once, and `VisualPresentation` could not
 * express "black" distinctly from "no image" — so a transition written against it cannot
 * tell an intentionally blank screen apart from an empty scene.
 *
 * ### A Frame is resolved, not a recipe
 *
 * Anything requiring a locale, a calendar, a repository lookup or a font metric has
 * already happened by the time a Frame exists. The renderer may not do that work: it runs
 * twice, in two windows, and any of it could differ between them.
 *
 * Frame is also the ONLY resolved projection of presentation state. Nothing else may be
 * duplicated into it — it already restates the picture's framing, [ScalingMode] and
 * [Viewport], which is the cost of the renderer reading nothing but a Frame.
 */
sealed interface Frame {

    /**
     * Opaque black.
     *
     * Drawn, not merely "nothing drawn". Every layer paints its own bounds black before
     * anything else, because a transparent layer lets the outgoing frame show through a
     * letterboxed incoming one and then snap to black when the transition ends.
     */
    data object Black : Frame

    data class Picture(
        val id: ContentId,
        val scaling: ScalingMode,
        val viewport: Viewport = Viewport.FULL,
    ) : Frame

    /**
     * Full-screen campaign info. **Replaces** the picture; it is not an overlay.
     *
     * @param background optional image behind the panel; black when null.
     */
    data class Info(val panel: InfoPanel, val background: ContentId?) : Frame
}

/**
 * The only function the renderers call to decide *what* to draw.
 *
 * Replaces the old `effectiveVisual()`. Pure, so "one state, two renderers" stays a
 * property that can be unit-tested rather than a convention that has to be remembered.
 */
fun PresentationState.frame(): Frame = when (scene.mode) {
    // INFO is also the blank control. A campaign with nothing configured gets black, which
    // is exactly the "nothing specific, I am preparing" state a separate blank button used
    // to provide — so there is no separate button, and no blackout flag in the state.
    SceneMode.Info -> scene.info?.let { Frame.Info(it, scene.infoBackground) } ?: Frame.Black

    SceneMode.Visual -> when (val source = scene.visual.source) {
        VisualSource.None -> Frame.Black
        is VisualSource.Image -> Frame.Picture(source.id, scene.visual.scaling, scene.visual.viewport)
    }
}

/**
 * The same frame with its pan and zoom set aside.
 *
 * What transitions compare. Reframing a picture is not a change of picture: a pinch reports a
 * step per input event, and dissolving on each would wash the map into itself continuously.
 */
fun Frame.unframed(): Frame = if (this is Frame.Picture) copy(viewport = Viewport.FULL) else this
