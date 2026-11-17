package com.brigade.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.brigade.presentation.NeonBar
import com.brigade.presentation.TimerOverlay
import kotlinx.coroutines.delay

/**
 * The cyberpunk timer: a segmented neon bar down the right edge, where the incense stick hangs
 * in the default style.
 *
 * Twenty cells, each for 5% of the countdown. The time left sits at the bottom, and the level
 * drops from the top like water in a bottle. A slanted gap separates
 * the cells, so the bar reads as a strip of hardware rather than a graph. Cyan while there is
 * time, amber in the last 40%, red in the last 15%. When it reaches zero the bar glitches once
 * and then stays dark, so the table notices without an ongoing distraction.
 *
 * ### Same rules as the stick
 *
 * Every dimension is a fraction of the surface, and the look is a pure function of the timer
 * and the clock. Both windows therefore draw the same bar at their different sizes, and the
 * glitch lands on both at the same moment. Like the stick, it is drawn above the transition
 * layers, so a scene change does not wipe it.
 *
 * No numerals, for the same reason as the stick (§21.2).
 */
@Composable
fun NeonTimer(overlay: TimerOverlay, modifier: Modifier = Modifier) {
    var now by remember(overlay) { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(overlay) {
        while (true) {
            now = System.nanoTime()
            // The glitch lasts well under a second; ticking at the stick's rate is plenty,
            // and stops once the glitch is over so a spent timer costs nothing.
            if (now >= overlay.burntOutAtNanos + GLITCH_NANOS) break
            delay(TICK_MILLIS)
        }
    }

    Canvas(modifier) { drawNeon(overlay, now) }
}

private const val TICK_MILLIS = 100L

/** How long the one-shot glitch plays after the bar reaches zero. */
private const val GLITCH_NANOS = 600_000_000L

private val CYAN = Color(0xFF3EE8F5)
private val AMBER = Color(0xFFFFB02E)
private val RED = Color(0xFFFF3B5C)
private val UNLIT = Color(0x2E3EE8F5)
private val FRAME = Color(0x993EE8F5)
private val SCRIM = Color(0x59000000)

/** Where the glitch is strongest, as a fraction of its duration: it decays from here. */
private val GLITCH_SLICE_OFFSETS = listOf(-0.012f, 0.009f, -0.005f, 0.014f, -0.009f)

private fun DrawScope.drawNeon(overlay: TimerOverlay, nowNanos: Long) {
    val remaining = 1f - overlay.burnedAt(nowNanos)
    val lit = NeonBar.litCells(remaining)
    val band = NeonBar.bandOf(remaining)
    val colour = when (band) {
        NeonBar.Band.Cyan -> CYAN
        NeonBar.Band.Amber -> AMBER
        NeonBar.Band.Red -> RED
    }

    // The bar sits in the same column as the stick, a little wider so 20 cells read clearly.
    val barWidth = size.width * 0.03f
    val barX = size.width * 0.945f - barWidth / 2f
    val top = size.height * 0.10f
    val bottom = size.height * 0.88f
    val barHeight = bottom - top

    val cellHeight = barHeight / NeonBar.CELLS
    val gap = cellHeight * 0.22f
    val slant = barWidth * 0.35f

    // A faint housing behind the bar, so it stays findable over a pale sky or a lantern.
    drawRect(
        color = SCRIM,
        topLeft = Offset(barX - size.width * 0.006f, top - size.height * 0.02f),
        size = Size(barWidth + size.width * 0.012f, barHeight + size.height * 0.04f),
    )

    // Cells are numbered from the top. The level drops from the top, so the top cells go dark
    // first and the bottom one last. NeonBar.isLit holds that rule so the tests can check it.
    for (index in 0 until NeonBar.CELLS) {
        val cellTop = top + index * cellHeight
        val isLit = NeonBar.isLit(index, lit)
        drawCell(
            x = barX,
            y = cellTop + gap / 2f,
            width = barWidth,
            height = cellHeight - gap,
            slant = slant,
            colour = if (isLit) colour else UNLIT,
        )
    }

    // The slanted frame around the whole bar, so the cells read as one instrument.
    drawRect(
        color = FRAME,
        topLeft = Offset(barX, top),
        size = Size(barWidth, barHeight),
        style = Stroke(width = size.width * 0.0015f),
    )

    // The glitch: a short burst after the bar reaches zero, then dark. Drawn last so it sits
    // over the bar, and only once the timer has run out.
    val sinceBurnOut = nowNanos - overlay.burntOutAtNanos
    if (sinceBurnOut in 0L until GLITCH_NANOS) {
        drawGlitch(barX, top, barWidth, barHeight, sinceBurnOut.toFloat() / GLITCH_NANOS)
    }
}

/** One cell: a parallelogram, slanted so neighbouring cells meet at an angle. */
private fun DrawScope.drawCell(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    slant: Float,
    colour: Color,
) {
    val path = Path().apply {
        moveTo(x + slant, y)
        lineTo(x + width, y)
        lineTo(x + width - slant, y + height)
        lineTo(x, y + height)
        close()
    }
    // Filled only. A stroked halo here spilled into the gaps and blurred neighbouring stripes
    // together, so each cell stays inside its own slanted edges.
    drawPath(path, colour)
}

/**
 * The glitch: the bar's cells slice sideways and split in colour, decaying over the glitch.
 *
 * Stateless, so the look at a given moment is the same in both windows. The offsets come from a
 * fixed list rather than a random source, for the same reason.
 */
private fun DrawScope.drawGlitch(barX: Float, top: Float, width: Float, height: Float, progress: Float) {
    val decay = 1f - progress
    GLITCH_SLICE_OFFSETS.forEachIndexed { index, shift ->
        val sliceTop = top + height * (index / GLITCH_SLICE_OFFSETS.size.toFloat())
        val sliceHeight = height / GLITCH_SLICE_OFFSETS.size
        val dx = shift * size.width * decay
        drawRect(
            color = if (index % 2 == 0) CYAN.copy(alpha = 0.7f * decay) else RED.copy(alpha = 0.7f * decay),
            topLeft = Offset(barX + dx, sliceTop),
            size = Size(width, sliceHeight * 0.6f),
        )
    }
}
