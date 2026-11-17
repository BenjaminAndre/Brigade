package com.brigade.presentation

/**
 * How the players' timer is drawn. Chosen per campaign by `timer:` in `Campagne.md`.
 *
 * Only the drawing differs between styles. The timer state, its durations and its extension
 * are shared, so switching style cannot change how long anything burns.
 */
enum class TimerStyle {
    /** The incense stick: the default, and what every campaign had before the key existed. */
    Incense,

    /** A segmented neon bar down the right edge, for the cyberpunk one-shots. */
    Neon;

    companion object {
        /**
         * Resolves the campaign's `timer:` key.
         *
         * No key means [Incense], so a campaign that never mentions it is unchanged. An unknown
         * name also falls back to [Incense], which keeps a typo visible on screen rather than
         * silently changing the look.
         */
        fun byName(name: String?): TimerStyle = when (name?.trim()?.lowercase()) {
            "neon" -> Neon
            else -> Incense
        }
    }
}

/**
 * The neon bar's arithmetic, kept apart from drawing so the JVM tests can check it.
 *
 * The bar is [CELLS] cells, each standing for [PERCENT_PER_CELL]% of the countdown. Cells light
 * from the bottom up: the remaining time sits at the bottom, and the level drops from the top
 * like water in a bottle. The top cell is the first to empty, the bottom cell the last.
 */
object NeonBar {

    const val CELLS: Int = 20

    /** 100 / [CELLS]: each cell is 5% of the countdown. */
    const val PERCENT_PER_CELL: Int = 100 / CELLS

    /** Colour bands, as a share of what is left: cyan above 60%, amber above 15%, red below. */
    const val CYAN_ABOVE: Float = 0.60f
    const val AMBER_ABOVE: Float = 0.15f

    enum class Band { Cyan, Amber, Red }

    /** How many cells are still lit, from 0 (burnt out) to [CELLS] (just lit). */
    fun litCells(remaining: Float): Int =
        Math.ceil((remaining.coerceIn(0f, 1f) * CELLS).toDouble()).toInt()

    /**
     * Whether cell [index] (0 = top) is lit when [lit] cells remain.
     *
     * The remaining time sits at the bottom, like water in a bottle, so the lit cells are the
     * bottom ones and the top cell is the first to go dark. Written out here rather than inline
     * in the drawing, so the rule is tested and not just assumed.
     */
    fun isLit(index: Int, lit: Int): Boolean = index >= CELLS - lit

    /**
     * The colour band for what is left.
     *
     * The bands are judged on the remaining share, not on whole cells, so the colour changes at
     * the exact moment the threshold is crossed rather than at the next cell boundary.
     */
    fun bandOf(remaining: Float): Band = when {
        remaining > CYAN_ABOVE -> Band.Cyan
        remaining > AMBER_ABOVE -> Band.Amber
        else -> Band.Red
    }
}
