package com.brigade.presentation

import com.brigade.content.ContentId
import com.brigade.content.ContentPath

/**
 * How a visual is fitted to the surface it is drawn on.
 *
 * These are presentation behaviours, not properties of the image (§13). v0.1 only ever
 * sets [Fit]; the other two exist so that adding them later is a UI change and not a
 * model change.
 */
enum class ScalingMode {
    /** Whole image visible, aspect preserved, remainder letterboxed in black. */
    Fit,

    /** Fills the surface, cropping the overflow. */
    Fill,

    /** One image pixel per surface pixel. */
    Native,
}

sealed interface VisualSource {

    /** Nothing to show. Renders as black. */
    data object None : VisualSource

    data class Image(val id: ContentId) : VisualSource
}

data class VisualPresentation(
    val source: VisualSource = VisualSource.None,
    val scaling: ScalingMode = ScalingMode.Fit,

    /**
     * The GM's pan and zoom. Reset whenever a different picture is presented — a slot always
     * comes up whole — and kept through INFO, which covers the picture without replacing it.
     */
    val viewport: Viewport = Viewport.FULL,
)

/** Which composed component is on the surface. INFO replaces the image; it does not overlay it. */
enum class SceneMode { Visual, Info }

/**
 * What the session is currently about.
 *
 * Composition, not subclassing (§16 Trap 7). Audio becomes `val audio: AudioPresentation?`
 * here and sound effects `val overlay: OverlayPresentation?`, with no
 * `ImageWithMusicPresentation` in sight.
 *
 * [mode] selects which component is on the surface while the others stay composed and
 * intact. That buys "INFO then back returns to the same image" for free, and means audio
 * can later arrive as a sibling field that INFO does not switch off.
 */
data class Scene(
    val mode: SceneMode = SceneMode.Visual,
    val visual: VisualPresentation = VisualPresentation(),
    val info: InfoPanel? = null,
    val infoBackground: ContentId? = null,

    /**
     * The note the current visual came from, when it came from one.
     *
     * **GM-facing only.** `frame()` does not consult it and [Frame] has no document field, so
     * there is no path by which this reaches the player display — a structural guarantee
     * rather than a rule to remember. It lives here, rather than in a flow of its own, because
     * it is genuinely part of "what is being presented" and must change in the same atomic
     * update as the visual it describes.
     */
    val note: NoteBar? = null,

    /**
     * The burning incense timer, or null when none is lit.
     *
     * §14's third component. Orthogonal to [visual] on purpose: it is drawn *over* whatever
     * is presented, so changing slot, entering INFO or blanking leaves it burning. `frame()`
     * does not consult it — the picture and the overlay are independent — and the renderer
     * draws it above the transition layers so a scene change does not dissolve it away.
     */
    val overlay: TimerOverlay? = null,

    /**
     * The campaign file the current visual was presented from: the note when it came from
     * one, the image otherwise. Null for black, and for a picture restored from a snapshot
     * that predates it.
     *
     * **GM-side bookkeeping**, for the session recap. Like [note], `frame()` does not consult
     * it, so it cannot change what the players see.
     */
    val origin: ContentPath? = null,
)

/**
 * The single source of truth for what the players can see.
 *
 * Independent of the UI and of any physical display (§10). The GM surface writes it,
 * the two renderers read it, and neither renderer may write it.
 */
data class PresentationState(
    val scene: Scene = Scene(),

    /** Which slot is live, or null when shown ad hoc via *Afficher*. */
    val liveSlot: SlotId? = null,

    /**
     * Monotonic, one increment per change of intent by the GM.
     *
     * Not incremented when a transition finishes — completion is not a new intent.
     */
    val revision: Long = 0L,

    /**
     * The cross-dissolve currently running, or null when the surface is settled.
     *
     * Null in the settled case is load-bearing: with no transition the renderer composes
     * exactly one layer and ticks no frame clock, so a campaign configured for `cut` gets
     * v0.1's render path unchanged rather than a dormant animation harness.
     */
    val transition: ActiveTransition? = null,
)

// The projection the renderers use lives in Frame.kt as `PresentationState.frame()`.
// The old `effectiveVisual()` was deleted with v0.2: it returned a VisualPresentation,
// which cannot express "black" distinctly from "no image", so once INFO existed a
// transition written against it could not tell blanking apart from an empty scene.
