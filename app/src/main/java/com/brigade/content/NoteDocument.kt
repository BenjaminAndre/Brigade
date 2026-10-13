package com.brigade.content

/**
 * What Brigade read out of a campaign note.
 *
 * Raw, not resolved — exactly the same split as [CampaignConfig]. Link targets are the
 * strings the GM wrote; turning «Métal» into an element, or a target into a [ContentId],
 * happens a layer up. It is what keeps `org.yaml` and `DocumentsContract` out of everything
 * above `content/saf/`.
 *
 * Note there is **no date**. The bar always shows the campaign date from `Campagne.md`
 * (§23), so a per-note `date:` would be read and never used — and a field that exists but
 * does nothing is worse than no field.
 */
data class NoteDocument(
    /** Filename without its extension. This is the character name. */
    val name: String,
    /** Raw French value, e.g. «Métal». Matched to an element in `presentation/`. */
    val element: String? = null,
    val faction: String? = null,
    /** Every image target in the body, in order, unfiltered. */
    val imageLinks: List<String> = emptyList(),
    /** The note's entries as an oracle table — see [Oracle.entries]. */
    val listItems: List<String> = emptyList(),
)
