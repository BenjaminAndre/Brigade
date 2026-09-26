package com.brigade.journal

import com.brigade.content.ContentClassifier
import com.brigade.content.ContentKind
import com.brigade.content.ContentPath
import com.brigade.presentation.PresentationState
import com.brigade.presentation.SceneMode
import com.brigade.presentation.VisualSource

/**
 * What the players had on screen, as the session recap names it.
 *
 * Coarser than a `Frame` on purpose. A pan, a zoom, a timer or a dissolve changes what is
 * drawn but not what the table is looking *at*, and a recap listing every pinch would bury
 * the scene under its own framing.
 */
sealed interface Panel {

    /** A note, presented as its image — or recalled as black when it links none. */
    data class Note(val path: ContentPath) : Panel

    /** An image shown on its own. The recap files it under its note when it has one. */
    data class Image(val path: ContentPath) : Panel

    /** The campaign panel, or black when none is configured — INFO is also the blank control. */
    data object Info : Panel

    data object Black : Panel
}

/**
 * The panel this state puts in front of the players, or null when it cannot be named.
 *
 * Null only for a picture whose origin is unknown, which a snapshot from before 0.6 can
 * restore. The recorder treats it as nothing being on screen, so no time is attributed to
 * something it cannot name.
 */
fun PresentationState.panel(): Panel? {
    if (scene.mode == SceneMode.Info) return Panel.Info

    val origin = scene.origin
    if (origin != null) {
        // By the name, the same way the browser decided it was a note in the first place.
        return when (ContentClassifier.classify("", origin.fileName)) {
            ContentKind.Markdown -> Panel.Note(origin)
            else -> Panel.Image(origin)
        }
    }

    return if (scene.visual.source is VisualSource.None) Panel.Black else null
}

/** This panel filed under the note that presents it, when it is an image that has one. */
fun Panel.attributed(noteOf: Map<ContentPath, ContentPath>): Panel =
    if (this is Panel.Image) noteOf[path]?.let { Panel.Note(it) } ?: this else this
