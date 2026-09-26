package com.brigade.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import com.brigade.content.ContentId
import com.brigade.presentation.ActiveTransition
import com.brigade.presentation.Frame
import com.brigade.presentation.PresentationState
import com.brigade.presentation.ScalingMode
import com.brigade.presentation.TransitionKind
import com.brigade.presentation.Viewport
import com.brigade.presentation.frame
import kotlin.math.roundToInt

/**
 * **The** renderer. Drawn identically by the GM preview and by the player window.
 *
 * ### Invariants
 *
 * Violating any of these breaks the "one state, two renderers" guarantee (§5, §10):
 *
 *  - never branch on which window it is being drawn in;
 *  - never take an aspect-ratio parameter — the **caller** sizes the box;
 *  - never branch on a size. `DrawScope.size` may be read to apply a *uniform scale*, so
 *    that content laid out at one virtual resolution is merely scaled to each target — see
 *    [drawInfoPanel] — but a size must never change *what* is drawn, how text wraps, or how
 *    many of anything there are;
 *  - never load an image without the caller's [model]. Coil keys its cache by request
 *    size, so a per-window default means two decodes that become ready at different
 *    moments — invisible with a hard cut, glaring through a dissolve. See
 *    [PlayerImageModel].
 *
 * ### Why fit/letterbox needs no code
 *
 * The black background paints the whole target surface; `ContentScale.Fit` scales the
 * bitmap by `min(boxW/imgW, boxH/imgH)` and centres it. The uncovered remainder *is* the
 * letterbox, and it is the same black (§13).
 *
 * `ContentScale.Fit` is scale-invariant: the geometry is a pure function of
 * `imageAspect / boxAspect`. So a 480 px preview and a 3840 px player window are
 * geometrically identical as long as both boxes share an aspect ratio.
 *
 * ### Pan and zoom
 *
 * Arrive as a [Viewport] in **normalised coordinates**, never in pixels, and are turned into
 * this window's pixels only at draw time — see [viewport]. A pixel offset means different
 * things in a 480 px preview and a 3840 px window, and the preview would quietly start lying
 * about what the players see. The gestures that produce it live on the GM side; this
 * composable only ever reads it.
 */
@Composable
fun PresentationSurface(
    state: PresentationState,
    modifier: Modifier = Modifier,
    /**
     * How a [ContentId] becomes something Coil can load.
     *
     * Supplied by the caller, and both callers must supply the same one — see
     * [PlayerImageModel]. The bare default exists for previews and tests only.
     */
    model: PlayerImageModel = PlayerImageModel { it.value },
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val transition = state.transition
        if (transition == null) {
            // No transition configured or none running: exactly v0.1's render path, one
            // layer and no frame clock.
            FrameLayer(state.frame(), model)
        } else {
            Dissolve(transition, state.frame(), model)
        }

        // Composed AFTER the layers, so a scene change does not dissolve the timer away
        // with the image it happened to be burning over. The overlay is orthogonal to the
        // picture — §14's third component — and outlives any of them.
        state.scene.overlay?.let { overlay ->
            IncenseTimer(overlay, Modifier.matchParentSize())
        }
    }
}

/**
 * Cross-dissolves [transition]`.from` into [target].
 *
 * Both layers stay composed; the outgoing frame is never captured to a bitmap.
 * `GraphicsLayer.toImageBitmap()` would be a multi-megabyte readback per transition on the
 * player window, for something two composed layers already give us.
 */
@Composable
private fun Dissolve(
    transition: ActiveTransition,
    target: Frame,
    model: PlayerImageModel,
) {
    val clock = remember(transition.startNanos) { TransitionClock() }

    LaunchedEffect(transition.startNanos) {
        while (true) {
            withFrameNanos { }
            // System.nanoTime rather than the callback's argument: startNanos was stamped
            // from the same clock in the store, and mixing time bases across two windows
            // is precisely the drift this design exists to avoid.
            val progress = transition.progressAt(System.nanoTime())
            clock.progress = progress
            if (progress >= 1f) {
                clock.running = false
                break
            }
        }
    }

    if (clock.running) {
        FrameLayer(transition.from, model)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dissolveMask(transition.spec.kind, transition.seed) { clock.progress },
        ) {
            FrameLayer(target, model)
        }
    } else {
        FrameLayer(target, model)
    }
}

/**
 * Per-frame progress, held so it can be read in the **draw** phase only.
 *
 * [progress] is read exclusively inside `graphicsLayer`/`drawWithContent` lambdas, so a
 * frame tick invalidates drawing without recomposing. Reading it in composition instead
 * would recompose this subtree — and re-invoke `AsyncImage` with a fresh modifier —
 * around sixty times a second, in both windows.
 *
 * [running] is the one field read *in* composition, and it changes exactly once per
 * transition, to unmount the outgoing layer.
 */
@Stable
private class TransitionClock {
    var progress by mutableFloatStateOf(0f)
    var running by mutableStateOf(true)
}

/**
 * Reveals this layer according to [kind] and [progress].
 *
 * [progress] is a lambda on purpose: it is read inside the `graphicsLayer` and
 * `drawWithContent` blocks, which run in the layer and draw phases. Passing the `Float`
 * itself would make every frame a recomposition of this subtree in both windows.
 */
private fun Modifier.dissolveMask(
    kind: TransitionKind,
    seed: Int,
    progress: () -> Float,
): Modifier = when (kind) {
    // A cut never stamps a transition, so this branch is unreachable; it exists so the
    // `when` stays exhaustive and the compiler finds it when a kind is added.
    TransitionKind.Cut -> this

    TransitionKind.Fade -> this.graphicsLayer { alpha = progress() }

    TransitionKind.Watercolor -> this
        // MUST come before drawWithContent. The offscreen layer is what gives DstIn
        // something to mask against; without it the blend has no destination and the
        // result is either nothing at all or a black rectangle.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()

            // Four dihedral orientations from two sign flips, so consecutive dissolves do
            // not wash identically. Done with scale about the centre rather than by
            // cropping a sub-rectangle of the field: a crop would change the apparent
            // feature size instead of mirroring, and would break the normalised sampling
            // that keeps the preview and the player window showing the same wash.
            val flipX = if (seed and 1 == 0) 1f else -1f
            val flipY = if (seed and 2 == 0) 1f else -1f

            scale(flipX, flipY) {
                drawImage(
                    image = DissolveMask.field,
                    // Stretched across the whole surface, so the wash pattern is a
                    // function of normalised position and is identical at both scales.
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    colorFilter = ColorFilter.colorMatrix(DissolveMask.rampMatrix(progress())),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * One [Frame], filling its bounds.
 *
 * **Every layer paints its own bounds opaque black before anything else.** Without that, a
 * dissolve between images of differing aspect leaves the incoming layer transparent in its
 * letterbox bars — the outgoing image shows through them at full opacity for the whole
 * wash, then snaps to black the instant the transition ends. A visible pop at the end of
 * every transition, which is the artefact the transition was bought to remove.
 */
@Composable
private fun FrameLayer(
    frame: Frame,
    model: PlayerImageModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // A zoomed picture is drawn larger than the layer. Clipped here, per layer, and
            // not only by the surface: inside a dissolve the incoming layer is masked in an
            // offscreen buffer, and whatever spilt past its bounds would be masked with it.
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        when (frame) {
            // The background IS the blank state. The player surface renders no
            // application text — campaign info is the one exception, and it is content.
            Frame.Black -> Unit

            is Frame.Picture -> {
                // Remembered by id: a pinch recomposes this layer at gesture rate, and
                // handing Coil a freshly built request on each step would lean on its request
                // equality to avoid reloading. Keeping the same instance leans on nothing.
                val request = remember(frame.id, model) { model(frame.id) }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = frame.scaling.toContentScale(),
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .viewport(frame.viewport),
                )
            }

            is Frame.Info -> {
                frame.background?.let { background ->
                    AsyncImage(
                        model = model(background),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                val measurer = rememberPanelMeasurer()
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawInfoPanel(frame.panel, measurer)
                }
            }
        }
    }
}

/**
 * Frames the picture on the GM's pointer.
 *
 * Reads the layer's size only to turn the viewport's fractions into this window's pixels — a
 * uniform scale of one geometry, which is exactly what the invariants permit, and what keeps
 * a 480 px preview and a 3840 px window showing the same region.
 *
 * Nothing at all at full view, so an unzoomed picture takes the path it always took.
 */
private fun Modifier.viewport(viewport: Viewport): Modifier =
    if (viewport.isFull) {
        this
    } else {
        graphicsLayer {
            // Scaled about the middle, the default transform origin; the translation then
            // slides the viewport's centre to the middle of the layer.
            scaleX = viewport.zoom
            scaleY = viewport.zoom
            translationX = -viewport.zoom * size.width * (viewport.centerX - 0.5f)
            translationY = -viewport.zoom * size.height * (viewport.centerY - 0.5f)
        }
    }

private fun ScalingMode.toContentScale(): ContentScale = when (this) {
    ScalingMode.Fit -> ContentScale.Fit
    ScalingMode.Fill -> ContentScale.Crop
    ScalingMode.Native -> ContentScale.None
}
