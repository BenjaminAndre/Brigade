package com.brigade.presentation

/**
 * How the blood stain darkens once the timer has run out.
 *
 * Pure, so the rule can be tested on the JVM. The renderer asks how dark the stain is, and
 * darkens its colours to match.
 */
object BloodStain {

    /** How long the stain takes to go from its fresh red to its deepest dark, after zero. */
    const val DARKEN_NANOS: Long = 2_000_000_000L

    /**
     * 0 while the timer is still running or has only just run out, rising to 1 over
     * [DARKEN_NANOS]. Clamped, so a long-spent timer stays at its deepest.
     */
    fun darkness(sinceBurntOutNanos: Long): Float {
        if (sinceBurntOutNanos <= 0L) return 0f
        return (sinceBurntOutNanos.toFloat() / DARKEN_NANOS).coerceIn(0f, 1f)
    }
}
