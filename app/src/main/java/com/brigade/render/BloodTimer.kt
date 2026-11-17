package com.brigade.render

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import com.brigade.presentation.BloodStain
import com.brigade.presentation.TimerOverlay
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The blood timer: a stain that seeps in from the top-right corner as time runs out.
 *
 * It starts at the corner, creeps across the picture, and reaches near full coverage at zero.
 * Once the timer has run out, the stain darkens over [BloodStain.DARKEN_NANOS] but stays
 * faintly red, so the table can still see what it covers. Adding a minute clears the stain back
 * towards the corner, the same way the incense stick grows back.
 *
 * ### Same rules as the other styles
 *
 * The look is a pure function of the timer and the clock, and the noise field is fixed, so both
 * windows draw the same stain at their own sizes. It sits above the transition layers, so a
 * scene change does not wipe it away.
 */
@Composable
fun BloodTimer(overlay: TimerOverlay, modifier: Modifier = Modifier) {
    var now by remember(overlay) { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(overlay) {
        while (true) {
            now = System.nanoTime()
            // Keeps ticking while the stain is still moving or still darkening, then stops, so
            // a spent timer costs nothing.
            if (now >= overlay.burntOutAtNanos + BloodStain.DARKEN_NANOS) break
            delay(TICK_MILLIS)
        }
    }

    Canvas(modifier) { drawBlood(overlay, now) }
}

private const val TICK_MILLIS = 200L

private fun DrawScope.drawBlood(overlay: TimerOverlay, nowNanos: Long) {
    val burned = overlay.burnedAt(nowNanos)
    val darkness = BloodStain.darkness(nowNanos - overlay.burntOutAtNanos)

    drawImage(
        image = BloodMask.field,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        colorFilter = ColorFilter.colorMatrix(BloodMask.matrix(burned, darkness)),
    )
}

/**
 * The stain's noise field and the colour mapping that turns it into blood.
 *
 * Each pixel carries two values in its channels:
 *  - **red**: the front. Low near the top-right corner, high far away, so the stain reaches a
 *    pixel once the burn passes its value. Built from the corner distance, a wobbling noise and
 *    streaks that run downward, so the edge creeps unevenly and drips.
 *  - **green**: the depth. It picks a colour between fresh and dried blood, so the stain is not
 *    one flat red.
 */
object BloodMask {

    private const val FIELD_SIZE = 256

    /** How much the organic noise moves the front, against the corner distance. */
    private const val FRONT_NOISE = 0.22f

    /** How much the downward streaks move the front. Smaller, so they drip rather than dominate. */
    private const val DRIP_NOISE = 0.12f

    /** How strongly the domain warp curls the noise, as in [DissolveMask]. */
    private const val WARP = 0.35f

    /** How far the blood darkens at its deepest, as a share of its colour. Never fully black. */
    private const val DARKEN_STRENGTH = 0.65f

    /** Fresh blood, where the stain first reaches. */
    private val LIGHT = Color(0xFF9E1E22)

    /** Dried blood, in the deep parts of the stain. */
    private val DEEP = Color(0xFF5A0F12)

    private val SQRT_2 = sqrt(2f)

    val field: ImageBitmap by lazy { generate(FIELD_SIZE) }

    /**
     * The colour filter for a given burn and darkness.
     *
     * The alpha is the same sweep as the watercolour dissolve, reading the front from red. The
     * colour is a line from [LIGHT] to [DEEP], read from green, scaled down as the stain darkens.
     */
    fun matrix(burned: Float, darkness: Float): ColorMatrix {
        val band = DissolveMask.BAND
        val threshold = burned * (1f + 2f * band) - band
        val slope = -1f / (2f * band)
        val offset = (threshold + band) / (2f * band)
        val scale = 1f - DARKEN_STRENGTH * darkness.coerceIn(0f, 1f)

        return ColorMatrix(
            floatArrayOf(
                0f, (DEEP.red - LIGHT.red) * scale, 0f, 0f, LIGHT.red * scale * 255f,
                0f, (DEEP.green - LIGHT.green) * scale, 0f, 0f, LIGHT.green * scale * 255f,
                0f, (DEEP.blue - LIGHT.blue) * scale, 0f, 0f, LIGHT.blue * scale * 255f,
                slope, 0f, 0f, 0f, offset * 255f,
            ),
        )
    }

    private fun generate(size: Int): ImageBitmap {
        val count = size * size
        val front = FloatArray(count)
        val depth = FloatArray(count)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x.toFloat() / size
                val v = y.toFloat() / size
                val index = y * size + x

                // Distance from the top-right corner, 0 there and 1 at the far bottom-left.
                val spread = sqrt((1f - u) * (1f - u) + v * v) / SQRT_2

                val wx = u + WARP * DissolveMask.fbm(u * 3f + 11.3f, v * 3f + 5.1f)
                val wy = v + WARP * DissolveMask.fbm(u * 3f + 2.7f, v * 3f + 19.7f)
                val organic = DissolveMask.fbm(wx * 4f, wy * 4f) - 0.5f

                // Stretched: fast across the stain, slow down it, so the streaks run vertically.
                val drips = DissolveMask.fbm(u * 16f + 3.1f, v * 2f + 7.7f) - 0.5f

                front[index] = spread + FRONT_NOISE * organic + DRIP_NOISE * drips
                depth[index] = DissolveMask.fbm(wx * 6f + 1.3f, wy * 6f + 8.8f)
            }
        }

        normalise(front)
        normalise(depth)

        val pixels = IntArray(count)
        for (i in 0 until count) {
            val r = (front[i] * 255f).toInt().coerceIn(0, 255)
            val g = (depth[i] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8)
        }

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, size, 0, 0, size, size) }
            .asImageBitmap()
    }

    /** Stretches the values to fill 0..1, so the front reaches both corners in full. */
    private fun normalise(values: FloatArray) {
        val min = values.min()
        val range = (values.max() - min).takeIf { it > 0f } ?: 1f
        for (i in values.indices) values[i] = (values[i] - min) / range
    }
}
