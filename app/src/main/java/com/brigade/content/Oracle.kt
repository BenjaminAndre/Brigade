package com.brigade.content

import kotlin.random.Random

/** The folder at the campaign root whose notes are the oracle tables. */
const val ORACLES_FOLDER: String = "Oracles"

/**
 * Random tables, written as ordinary Markdown notes in the campaign's `Oracles/` folder.
 *
 * ### What an entry is
 *
 * **One top-level list line**: `- …`, `* …`, `+ …` or `1. …`. Everything else — headings,
 * paragraphs explaining the table, frontmatter — is for reading and is never rolled, so a
 * table can say what it is for without polluting itself. Indented list lines are ignored too,
 * which lets the GM keep notes *under* an entry.
 *
 * ### Weights for free
 *
 * Every line is equally likely, and duplicates are kept. Writing «Oui» three times makes it
 * three times as likely: weighting needs no syntax of its own.
 */
object Oracle {

    /** Up to three spaces of indent, a list marker, then the entry. Four or a tab is nested. */
    private val ITEM = Regex("""^ {0,3}(?:[-*+]|\d{1,3}[.)])\s+(.*\S)\s*$""")

    /** An Obsidian task box, which is not part of the entry. */
    private val TASK = Regex("""^\[[ xX]]\s+""")

    /** `[[Note]]` reads as «Note», `[[Note|alias]]` as «alias». */
    private val WIKILINK = Regex("""\[\[([^\]|]*)(?:\|([^\]]*))?]]""")

    fun entries(text: String): List<String> =
        Markdown.body(text).lineSequence()
            .mapNotNull { line -> ITEM.find(line)?.groupValues?.get(1) }
            .map { entry ->
                entry
                    .replace(TASK, "")
                    .replace(WIKILINK) { link -> link.groupValues[2].ifEmpty { link.groupValues[1] } }
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * One roll on the table, or null for a table with no entries.
     *
     * Uniform, and allowed to repeat: a reroll that comes up the same is a real result — on a
     * yes/no table, «oui» twice is an answer — and avoiding it would quietly break the weights.
     */
    fun roll(entries: List<String>, random: Random = Random.Default): String? =
        entries.takeIf { it.isNotEmpty() }?.random(random)
}
