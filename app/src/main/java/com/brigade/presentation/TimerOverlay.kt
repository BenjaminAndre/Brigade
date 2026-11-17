package com.brigade.presentation

/**
 * A burning incense stick on the player surface.
 *
 * §14's composition model is `Scene { Visual, Audio, Overlay }`, and this is the Overlay:
 * something drawn *over* the presentation without replacing it. It is a sibling of the
 * visual, so changing slot, entering INFO or blanking leaves it burning untouched — which
 * is the only sensible behaviour for a timer the players are watching.
 *
 * ### How two windows burn in step
 *
 * Exactly as [ActiveTransition] does: the start time and the duration live in the state,
 * and each window computes what is left as a pure function of them while sampling its own
 * clock. Nothing ticks in the state, nothing drifts, and a player display reconnecting
 * mid-burn picks up the right length immediately rather than restarting.
 *
 * Never persisted. A timer is ephemeral to a session; restoring a stale one after a relaunch
 * would put a stick on screen that had already burnt out an hour ago.
 */
data class TimerOverlay(
    val startNanos: Long,
    val durationMillis: Long,
) {
    /** 0 at the moment it is lit, 1 when burnt out. Clamped. */
    fun burnedAt(nowNanos: Long): Float {
        if (durationMillis <= 0L) return 1f
        val elapsedMillis = (nowNanos - startNanos) / 1_000_000.0
        return (elapsedMillis / durationMillis).coerceIn(0.0, 1.0).toFloat()
    }

    fun remainingMillisAt(nowNanos: Long): Long {
        val elapsedMillis = (nowNanos - startNanos) / 1_000_000L
        return (durationMillis - elapsedMillis).coerceAtLeast(0L)
    }

    fun isBurntOutAt(nowNanos: Long): Boolean = remainingMillisAt(nowNanos) <= 0L

    /** The moment the timer reaches zero. Fixed once lit, so an effect keyed to it fires once. */
    val burntOutAtNanos: Long get() = startNanos + durationMillis * 1_000_000L

    /**
     * Lengthens the stick in place, keeping the time already burnt.
     *
     * The players see the stick grow, which reads as mercy rather than as a reset — the
     * whole point of "another minute" over "start again".
     */
    fun extendedBy(extraMillis: Long): TimerOverlay =
        copy(durationMillis = durationMillis + extraMillis)

    companion object {
        /** What the GM can light, in minutes. Hardcoded until a session says otherwise. */
        val DURATIONS_MINUTES = listOf(1, 2, 5)

        const val EXTEND_MILLIS = 60_000L

        fun ofMinutes(minutes: Int, nowNanos: Long): TimerOverlay =
            TimerOverlay(startNanos = nowNanos, durationMillis = minutes * 60_000L)
    }
}
