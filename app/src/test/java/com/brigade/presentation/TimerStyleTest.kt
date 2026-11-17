package com.brigade.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStyleTest {

    @Test
    fun `no key, or an unknown name, is the incense stick`() {
        assertEquals(TimerStyle.Incense, TimerStyle.byName(null))
        assertEquals(TimerStyle.Incense, TimerStyle.byName("segments"))
    }

    @Test
    fun `neon is chosen by name, whatever the case`() {
        assertEquals(TimerStyle.Neon, TimerStyle.byName("neon"))
        assertEquals(TimerStyle.Neon, TimerStyle.byName("  NEON "))
    }

    /** Each cell is 5% of the countdown: twenty of them make the whole. */
    @Test
    fun `there are twenty cells of five percent`() {
        assertEquals(20, NeonBar.CELLS)
        assertEquals(5, NeonBar.PERCENT_PER_CELL)
    }

    @Test
    fun `a full timer lights every cell and an empty one none`() {
        assertEquals(NeonBar.CELLS, NeonBar.litCells(1f))
        assertEquals(0, NeonBar.litCells(0f))
    }

    /** The bar steps down a cell per 5%: at 58% left, 12 cells are still lit. */
    @Test
    fun `the lit count steps by one cell for each five percent`() {
        assertEquals(12, NeonBar.litCells(0.58f))
        assertEquals(11, NeonBar.litCells(0.52f))
        assertEquals(1, NeonBar.litCells(0.04f))
        assertEquals(0, NeonBar.litCells(0f))
    }

    @Test
    fun `the top cells stay lit and the bottom ones go out first`() {
        val lit = 3

        assertTrue(NeonBar.isLit(0, lit))
        assertTrue(NeonBar.isLit(2, lit))
        assertFalse(NeonBar.isLit(3, lit))
        assertFalse(NeonBar.isLit(19, lit))
    }

    /** Cyan above 60%, amber above 15%, red at the last 15%. */
    @Test
    fun `colour changes at the thresholds`() {
        assertEquals(NeonBar.Band.Cyan, NeonBar.bandOf(1f))
        assertEquals(NeonBar.Band.Cyan, NeonBar.bandOf(0.61f))
        assertEquals(NeonBar.Band.Amber, NeonBar.bandOf(0.60f))
        assertEquals(NeonBar.Band.Amber, NeonBar.bandOf(0.16f))
        assertEquals(NeonBar.Band.Red, NeonBar.bandOf(0.15f))
        assertEquals(NeonBar.Band.Red, NeonBar.bandOf(0f))
    }

    @Test
    fun `the glitch is keyed to the moment the timer runs out`() {
        val overlay = TimerOverlay(startNanos = 1_000L, durationMillis = 60_000L)

        assertEquals(1_000L + 60_000L * 1_000_000L, overlay.burntOutAtNanos)
        assertEquals(0f, overlay.burnedAt(1_000L), 0.0001f)
        assertEquals(1f, overlay.burnedAt(overlay.burntOutAtNanos), 0.0001f)
    }
}
