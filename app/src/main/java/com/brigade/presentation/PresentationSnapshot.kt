package com.brigade.presentation

import com.brigade.content.ContentId
import com.brigade.content.ContentPath

/**
 * [PresentationState] flattened to primitives, for surviving process death.
 *
 * Five scalars, no serialization library, no `Parcelable` — which is the payoff for
 * [ContentId] wrapping a `String` rather than a `Uri`.
 *
 * Configuration changes do *not* go through here: presentation state lives on the
 * application graph and survives those for free. This exists only for a cold start
 * after the process was killed.
 */
data class PresentationSnapshot(
    val imageId: String?,
    val scaling: String,
    val liveSlot: Int?,
    /**
     * [Scene.origin], so a relaunch mid-session still knows which note is on screen. Without
     * it, leaving INFO back to the restored picture would go unrecorded in the recap.
     */
    val originPath: String? = null,
)

interface SnapshotStore {
    fun load(): PresentationSnapshot?
    fun save(snapshot: PresentationSnapshot)
}

fun PresentationState.toSnapshot(): PresentationSnapshot = PresentationSnapshot(
    imageId = (scene.visual.source as? VisualSource.Image)?.id?.value,
    scaling = scene.visual.scaling.name,
    liveSlot = liveSlot?.index,
    originPath = scene.origin?.value,
)

/**
 * @param startInInfo the caller's safety policy. On a cold start after a crash or a reboot,
 * silently blasting the last image onto the players' screen during relaunch is worse than
 * coming up on the info panel — or on black, if the campaign configures none. The scene is
 * restored intact underneath, so one tap on the live slot resumes.
 *
 * A reconnecting display does **not** come through here: it re-renders live state, because
 * a display that could mutate state would be the source of truth (§16 Trap 5).
 */
fun PresentationSnapshot.toState(startInInfo: Boolean): PresentationState {
    val source = imageId?.let { VisualSource.Image(ContentId(it)) } ?: VisualSource.None
    val scalingMode = ScalingMode.entries.firstOrNull { it.name == scaling } ?: ScalingMode.Fit
    return PresentationState(
        scene = Scene(
            mode = if (startInInfo) SceneMode.Info else SceneMode.Visual,
            visual = VisualPresentation(source = source, scaling = scalingMode),
            origin = originPath?.let(::ContentPath),
        ),
        // `info` is deliberately absent: the panel is derived from Campagne.md, which the
        // application graph loads separately. Until it does, INFO mode projects to black —
        // which is now a legitimate state rather than a symptom.
        liveSlot = liveSlot?.let(::SlotId),
    )
}
