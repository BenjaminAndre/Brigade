package com.brigade.storage

import android.content.Context
import com.brigade.content.RootStore
import com.brigade.journal.HeartbeatStore
import com.brigade.presentation.PresentationSnapshot
import com.brigade.presentation.SnapshotStore

/**
 * `SharedPreferences` implementations of the two tiny app-private stores.
 *
 * Not DataStore: one string and four scalars do not justify its artifact, its
 * coroutine machinery and its okio/protobuf surface, and the interfaces they implement
 * live in `content/` and `presentation/` — so swapping the backing store later is one
 * file, not a refactor.
 *
 * Note what is *not* here: the slot bank. That points at campaign content, so it lives
 * with the campaign in `.brigade/slots.json` (§6.1), not in app-private storage.
 */
private const val PREFS_NAME = "brigade"

class PrefsRootStore(context: Context) : RootStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_ROOT, null)

    override fun save(treeUri: String?) {
        prefs.edit().putString(KEY_ROOT, treeUri).apply()
    }

    private companion object {
        const val KEY_ROOT = "root_tree_uri"
    }
}

class PrefsSnapshotStore(context: Context) : SnapshotStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PresentationSnapshot? {
        val imageId = prefs.getString(KEY_IMAGE, null)
        val scaling = prefs.getString(KEY_SCALING, null) ?: return null
        val slot = prefs.getInt(KEY_SLOT, -1)
        return PresentationSnapshot(
            imageId = imageId,
            scaling = scaling,
            liveSlot = slot.takeIf { it >= 0 },
            originPath = prefs.getString(KEY_ORIGIN, null),
        )
    }

    override fun save(snapshot: PresentationSnapshot) {
        prefs.edit()
            .putString(KEY_IMAGE, snapshot.imageId)
            .putString(KEY_SCALING, snapshot.scaling)
            .putInt(KEY_SLOT, snapshot.liveSlot ?: -1)
            .putString(KEY_ORIGIN, snapshot.originPath)
            .apply()
    }

    private companion object {
        const val KEY_IMAGE = "presentation_image_id"
        const val KEY_SCALING = "presentation_scaling"
        const val KEY_SLOT = "presentation_live_slot"
        const val KEY_ORIGIN = "presentation_origin"
    }
}

/**
 * When the recorder last knew a panel was still on screen, per campaign.
 *
 * App-private rather than in the journal: it is overwritten once a minute, and a minute's
 * worth of lines in a Git-tracked file for the sake of a crash would be the wrong trade.
 */
class PrefsHeartbeatStore(context: Context) : HeartbeatStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(campaign: String): Long? {
        if (prefs.getString(KEY_CAMPAIGN, null) != campaign) return null
        return prefs.getLong(KEY_AT, -1L).takeIf { it >= 0 }
    }

    override fun save(campaign: String, atMillis: Long) {
        prefs.edit()
            .putString(KEY_CAMPAIGN, campaign)
            .putLong(KEY_AT, atMillis)
            .apply()
    }

    private companion object {
        const val KEY_CAMPAIGN = "journal_alive_campaign"
        const val KEY_AT = "journal_alive_at"
    }
}
