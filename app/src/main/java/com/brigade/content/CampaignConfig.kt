package com.brigade.content

/**
 * What Brigade read out of `Campagne.md`.
 *
 * Raw, not resolved: strings exactly as the GM wrote them, in the order they wrote them.
 * Turning `date: 1127-03-12` into «jeudi 12 mars 1127», or a path into a [ContentId],
 * happens later — this type only says what the file contained.
 *
 * That split is deliberate. The parser lives in `content/saf` because it needs a
 * `ContentResolver` and a YAML library; everything above it takes this plain data class and
 * stays free of both.
 */
data class CampaignConfig(
    /** The `transition:` key, unvalidated. `TransitionSpec.byName` decides what it means. */
    val transition: String? = null,
    /** The `timer:` key, unvalidated. `TimerStyle.byName` decides what it means. */
    val timer: String? = null,
    val info: CampaignInfoConfig? = null,
) {
    companion object {
        val EMPTY = CampaignConfig()
    }
}

data class CampaignInfoConfig(
    /** Image behind the info panel, relative to the campaign root. Black when absent. */
    val background: ContentPath? = null,

    /** Draw the moon in its phase for whatever `date` entry the campaign supplies. */
    val showLunarState: Boolean = false,

    /** In the order written. Both halves are raw text; meaning is applied later. */
    val entries: List<CampaignInfoEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty() && background == null
}

/**
 * One line of the info block, as written.
 *
 * [key] is matched case-insensitively against the handful of names Brigade understands
 * (`title`, `location`, `date`); anything else is shown verbatim as a label.
 */
data class CampaignInfoEntry(val key: String, val value: String)
