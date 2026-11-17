package com.brigade.render

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.floor

/**
 * The noise field the watercolor dissolve washes through.
 *
 * ### Why a field and a threshold, rather than a shader
 *
 * AGSL `RuntimeShader` needs API 33 and the target tablet may be on 12, so the effect has
 * to be portable. Sweeping a threshold across a soft, irregular noise field and using the
 * result as an alpha mask gives the pigment-bleed look without one: the wash front follows
 * the noise contours instead of a straight line, which is the whole difference between
 * "watercolour" and "wipe".
 *
 * ### Process-wide, immutable, never null
 *
 * One field, generated once, held in a `by lazy` on an object. That matters more than it
 * looks: if the field could arrive *during* a transition, one window could still be
 * waiting for it while the other had already started washing — two render targets showing
 * different things, which is the §5 failure this architecture exists to prevent. An
 * immutable field that is always present cannot do that.
 *
 * Variation between consecutive dissolves comes from four dihedral orientations applied at
 * draw time (see [PresentationSurface]), not from a bank of fields. Two sign flips, no
 * plumbing, no readiness race — and nobody at a table has ever noticed a repeated dissolve
 * pattern anyway.
 */
object DissolveMask {

    /**
     * Deliberately small. The field is stretched across the whole surface and sampled
     * bilinearly, so a low resolution reads as *softer*, which is what we want, as well as
     * being cheaper to generate and to upload.
     */
    private const val FIELD_SIZE = 256

    /**
     * How wide the soft edge of the wash front is, in field units.
     *
     * A hard threshold gives crunchy, aliased edges. This band is what makes the front
     * bleed. Not campaign-configurable: below roughly 0.05 it produces contour banding
     * whose cause would not be remotely obvious from a config file.
     */
    const val BAND = 0.12f

    val field: ImageBitmap by lazy { generateField(FIELD_SIZE) }

    /** Forces generation off the main thread. Call once at startup. */
    fun warm() {
        field
    }

    /**
     * Maps the field's red channel to alpha, so that `progress` sweeps the wash front.
     *
     * `alpha = clamp((threshold + BAND - noise) / 2·BAND)`, expressed as a colour matrix so
     * the GPU does it per pixel with no per-frame CPU work. The threshold travels from
     * `-BAND` to `1 + BAND`, overshooting the field's range at both ends — which is why the
     * field does not need to span exactly `[0,1]` for the transition to start fully hidden
     * and end fully revealed.
     *
     * RGB rows are zeroed: `BlendMode.DstIn` reads only the source alpha.
     */
    fun rampMatrix(progress: Float): ColorMatrix {
        val threshold = progress * (1f + 2f * BAND) - BAND
        val slope = -1f / (2f * BAND)
        val offset = (threshold + BAND) / (2f * BAND)

        return ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                // The fifth column is in 0..255 units, not 0..1 — android.graphics.ColorMatrix's
                // convention, which ColorFilter.colorMatrix lowers onto.
                slope, 0f, 0f, 0f, offset * 255f,
            ),
        )
    }

    // ---- Field generation ---------------------------------------------------------

    private fun generateField(size: Int): ImageBitmap {
        val values = FloatArray(size * size)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x.toFloat() / size
                val v = y.toFloat() / size

                // Domain warp: distorting the sample position with more noise is what turns
                // smooth blobs into the curled, uneven fronts that read as flowing pigment.
                val wx = u + WARP * fbm(u * 3f + 11.3f, v * 3f + 5.1f)
                val wy = v + WARP * fbm(u * 3f + 2.7f, v * 3f + 19.7f)

                values[y * size + x] = fbm(wx * 4f, wy * 4f)
            }
        }

        equalise(values)

        val pixels = IntArray(size * size)
        for (i in values.indices) {
            val grey = (values[i] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, size, 0, 0, size, size) }
            .asImageBitmap()
    }

    /**
     * Flattens the field's value distribution in place.
     *
     * Raw fBm clusters around the middle, so a linear threshold sweep would crawl at the
     * start and end and race through the middle. Equalising makes the wash advance at a
     * roughly constant *area* per unit time, which is what the eye reads as even.
     */
    private fun equalise(values: FloatArray) {
        val histogram = IntArray(HISTOGRAM_BINS)
        for (value in values) {
            histogram[binOf(value)]++
        }

        val lookup = FloatArray(HISTOGRAM_BINS)
        var cumulative = 0
        for (bin in 0 until HISTOGRAM_BINS) {
            val count = histogram[bin]
            lookup[bin] = (cumulative + count * 0.5f) / values.size
            cumulative += count
        }

        for (i in values.indices) {
            values[i] = lookup[binOf(values[i])]
        }
    }

    private fun binOf(value: Float): Int =
        (value * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)

    // ---- Value noise --------------------------------------------------------------

    /** Fractal value noise in 0..1. Shared with the blood timer, which draws from the same source. */
    internal fun fbm(x: Float, y: Float): Float {
        var sum = 0f
        var amplitude = 0.5f
        var frequency = 1f
        var normalisation = 0f

        repeat(OCTAVES) {
            sum += amplitude * valueNoise(x * frequency, y * frequency)
            normalisation += amplitude
            amplitude *= GAIN
            frequency *= LACUNARITY
        }

        return sum / normalisation
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi

        // Quintic rather than linear or cubic: it has zero first AND second derivative at
        // the cell edges, so the lattice does not show up as a faint grid in the wash.
        val u = quintic(xf)
        val v = quintic(yf)

        val topLeft = hash(xi, yi)
        val topRight = hash(xi + 1, yi)
        val bottomLeft = hash(xi, yi + 1)
        val bottomRight = hash(xi + 1, yi + 1)

        return lerp(lerp(topLeft, topRight, u), lerp(bottomLeft, bottomRight, u), v)
    }

    private fun quintic(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Deterministic: the same field every launch, so both windows and every run agree. */
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFF) / 0x7FFFFFFF.toFloat()
    }

    private const val WARP = 0.35f
    private const val OCTAVES = 4
    private const val GAIN = 0.5f
    private const val LACUNARITY = 2f
    private const val HISTOGRAM_BINS = 512
}
