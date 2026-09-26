package com.brigade.presentation

/**
 * Which part of the player surface the players are looking at — the GM's pointer.
 *
 * ### Normalised, never pixels
 *
 * [centerX] and [centerY] are fractions of the surface, (0.5, 0.5) being its middle, and
 * [zoom] is a pure ratio. A pixel offset means one thing in a 480 px preview and another in a
 * 3840 px window, so a viewport in pixels would have the preview quietly lying about what the
 * table sees. In fractions it is the same region at every size.
 *
 * ### Of the surface, not of the image
 *
 * The viewport knows nothing of the picture's aspect ratio, so it cannot come out differently
 * in two windows that finish loading at different moments. The cost is that a letterboxed
 * image can be panned into its black bars — which the preview shows exactly as the players
 * see it, so the GM notices and pans back.
 */
data class Viewport(
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {

    val isFull: Boolean get() = zoom <= 1f

    /** One step of a pinch-and-drag applied, clamped so the view never leaves the surface. */
    fun applied(gesture: ViewportGesture): Viewport {
        val next = (zoom * gesture.zoomChange).coerceIn(1f, MAX_ZOOM)

        // Keeps the point under the fingers under the fingers, which is what makes a pinch
        // feel like taking hold of the map rather than zooming into its middle. The point at
        // focus f sits at `center + (f - 0.5) / zoom`; holding it fixed across the zoom
        // change gives this shift.
        val anchor = 1f / zoom - 1f / next

        return Viewport(
            zoom = next,
            // A drag moves the picture with the finger, so the view moves the other way — and
            // by less when zoomed in, since a surface-width then covers less of the picture.
            centerX = centerX + (gesture.focusX - 0.5f) * anchor - gesture.panX / next,
            centerY = centerY + (gesture.focusY - 0.5f) * anchor - gesture.panY / next,
        ).clamped()
    }

    /** Keeps the visible window inside the surface. At zoom 1 that pins the centre. */
    private fun clamped(): Viewport {
        val half = 0.5f / zoom
        return copy(
            centerX = centerX.coerceIn(half, 1f - half),
            centerY = centerY.coerceIn(half, 1f - half),
        )
    }

    companion object {

        val FULL = Viewport()

        /**
         * How far in the GM can go.
         *
         * Tied to the decode: images are decoded at this multiple of the player display's
         * resolution (see `playerImageModel`), so every zoom up to here still has a source
         * pixel for each screen pixel. Raising this without the decode buys only blur.
         */
        const val MAX_ZOOM = 2f
    }
}

/**
 * One step of a pinch-and-drag, as fractions of the surface it happened on.
 *
 * The GM surface divides by its own size before anything reaches the state, which is the
 * whole point: the player window never sees a pixel from the preview.
 *
 * @param zoomChange multiplicative, as gesture detectors report it; 1 for a plain drag.
 * @param panX the drag as a fraction of the surface's width.
 * @param panY the drag as a fraction of the surface's height.
 * @param focusX where the fingers are, as a fraction of the width.
 * @param focusY where the fingers are, as a fraction of the height.
 */
data class ViewportGesture(
    val zoomChange: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
)
