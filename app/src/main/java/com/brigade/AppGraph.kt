package com.brigade

import android.app.Application
import android.net.Uri
import com.brigade.content.CampaignConfig
import com.brigade.content.ContentId
import com.brigade.content.ContentItem
import com.brigade.content.ContentKind
import com.brigade.content.ContentPath
import com.brigade.content.ContentRepository
import com.brigade.content.RootStore
import com.brigade.content.saf.CampaignFile
import com.brigade.content.saf.DocumentTreeSource
import com.brigade.content.saf.NoteReader
import com.brigade.content.saf.SafSlotStore
import com.brigade.content.saf.SafTextFile
import com.brigade.display.PlayerDisplayHost
import com.brigade.display.PlayerDisplayStatus
import com.brigade.display.PresentationPlayerDisplayHost
import com.brigade.journal.HeartbeatStore
import com.brigade.journal.JournalCodec
import com.brigade.journal.JournalEntry
import com.brigade.journal.HiddenDays
import com.brigade.journal.Panel
import com.brigade.journal.Seances
import com.brigade.journal.SeancesData
import com.brigade.journal.SessionRecorder
import com.brigade.journal.Showing
import com.brigade.render.PlayerImageModel
import com.brigade.render.playerImageModel
import com.brigade.presentation.CampaignInfo
import com.brigade.presentation.NoteBar
import com.brigade.presentation.PresentationStore
import com.brigade.presentation.SceneMode
import com.brigade.presentation.SlotBank
import com.brigade.presentation.SlotBankState
import com.brigade.presentation.SlotContent
import com.brigade.presentation.SlotId
import com.brigade.presentation.SnapshotStore
import com.brigade.presentation.TransitionSpec
import com.brigade.presentation.ViewportGesture
import com.brigade.presentation.toSnapshot
import com.brigade.presentation.toState
import com.brigade.storage.PrefsHeartbeatStore
import com.brigade.storage.PrefsRootStore
import com.brigade.storage.PrefsSnapshotStore
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether a campaign is open — with [Restoring] distinct from [None].
 *
 * Restoring the saved root suspends on a disk read, so the first composition always runs
 * before it finishes. Collapsing these two into a single boolean made every launch flash
 * the full "no campaign" screen, complete with a live folder-picker button, before the
 * browser appeared.
 */
enum class CampaignState { Restoring, None, Open }

/** The session journal and the Séances settings, in `.brigade/` beside the slot bank. */
private const val BRIGADE_DIRECTORY = ".brigade"
private const val JOURNAL_FILE_NAME = "journal.tsv"
private const val SEANCES_FILE_NAME = "seances.json"

/**
 * Decode size used while no player display is attached.
 *
 * Matches [PlayerDisplayStatus.DEFAULT_PLAYER_ASPECT], so the preview's letterboxing looks
 * the same before and after the cable goes in.
 */
private const val FALLBACK_PLAYER_WIDTH_PX = 1920
private const val FALLBACK_PLAYER_HEIGHT_PX = 1080

/**
 * Hand-rolled dependency graph, scoped to the process.
 *
 * Roughly thirty lines instead of Hilt or Koin, for four singletons.
 *
 * **Why these live here and not on a ViewModel:** the player window is an
 * `android.app.Presentation` with a lifecycle of its own, and both it and the GM
 * Activity must observe *the same* `StateFlow` instance. State scoped to the Activity's
 * `ViewModelStore` would be cleared underneath the player window, and a `viewModel()`
 * call inside the player composition would quietly create a second, independent state
 * holder — exactly the "two independent pieces of business logic" §5 forbids.
 *
 * Only genuinely-UI state (the browsing stack) belongs to a ViewModel, where dying with
 * the UI is the correct behaviour.
 */
class AppGraph(private val app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The campaign's configured transition.
     *
     * Read through a lambda by the store rather than passed by value, so opening a
     * different campaign folder changes it without rebuilding presentation state. The
     * `transition:` key in `Campagne.md` sets it; until that reader exists it holds the
     * default from [TransitionSpec.byName] for an absent config.
     */
    private val _transitionSpec = MutableStateFlow(TransitionSpec.byName(null))

    /** Last read of `Campagne.md`. Re-read when a campaign is opened. */
    private val _campaignConfig = MutableStateFlow(CampaignConfig.EMPTY)
    val campaignConfig: StateFlow<CampaignConfig> = _campaignConfig.asStateFlow()

    private var campaignFile: CampaignFile? = null

    val presentation = PresentationStore(spec = { _transitionSpec.value })
    val slots = SlotBank()

    private val rootStore: RootStore = PrefsRootStore(app)
    private val snapshotStore: SnapshotStore = PrefsSnapshotStore(app)

    private val _repository = MutableStateFlow<ContentRepository?>(null)
    val repository: StateFlow<ContentRepository?> = _repository.asStateFlow()

    private val _campaign = MutableStateFlow(CampaignState.Restoring)
    val campaign: StateFlow<CampaignState> = _campaign.asStateFlow()

    /** True when the slot bank could not be written back to `.brigade/slots.json`. */
    private val _slotWriteFailed = MutableStateFlow(false)
    val slotWriteFailed: StateFlow<Boolean> = _slotWriteFailed.asStateFlow()

    private var slotStore: SafSlotStore? = null

    // ---- Séances state -------------------------------------------------------------
    //
    // Per campaign, replaced by openRoot. Everything lives in .brigade/: the journal is the
    // record, seances.json the days the GM chose to hide. Nothing outside it is ever written.

    private val heartbeats: HeartbeatStore = PrefsHeartbeatStore(app)
    private var journalFile: SafTextFile? = null
    private var seancesFile: SafTextFile? = null
    private var recorder: SessionRecorder? = null
    private var recorderJob: Job? = null

    /** True when the journal could not be written to `.brigade/`. */
    private val _journalWriteFailed = MutableStateFlow(false)
    val journalWriteFailed: StateFlow<Boolean> = _journalWriteFailed.asStateFlow()

    /**
     * The picture each note or image is drawn with on the Séances screen, kept between
     * refreshes: resolving a note's picture reads the note, and the screen refreshes live.
     * Cleared by *Actualiser*, like everything else read from the campaign.
     */
    private val seancePictures = HashMap<Panel, ContentId?>()

    /**
     * The one image request builder both render targets use.
     *
     * Declared before [playerDisplay] and updated from its status in [init], rather than
     * derived from it with `map`/`stateIn` — the display host needs this flow at
     * construction and this flow needs the display's size, so one of the two has to start
     * with a stand-in. Doing it this way keeps the cycle broken without a lazy indirection.
     */
    private val _imageModel = MutableStateFlow(
        playerImageModel(app, FALLBACK_PLAYER_WIDTH_PX, FALLBACK_PLAYER_HEIGHT_PX),
    )
    val imageModel: StateFlow<PlayerImageModel> = _imageModel.asStateFlow()

    val playerDisplay: PlayerDisplayHost =
        PresentationPlayerDisplayHost(app, presentation.state, imageModel)

    init {
        // Re-pin the decode size whenever the player display changes, so both windows keep
        // sharing one Coil cache entry at the display's actual resolution.
        playerDisplay.status
            .onEach { status ->
                _imageModel.value = when (status) {
                    is PlayerDisplayStatus.Attached ->
                        playerImageModel(app, status.widthPx, status.heightPx)

                    PlayerDisplayStatus.Absent ->
                        playerImageModel(app, FALLBACK_PLAYER_WIDTH_PX, FALLBACK_PLAYER_HEIGHT_PX)
                }
            }
            .launchIn(appScope)

        appScope.launch {
            // The snapshot is restored BEFORE the campaign is opened, because `restore`
            // replaces the whole state — running it afterwards would discard the info
            // panel that openRoot had just loaded, leaving INFO mode showing black.
            val snapshot = withContext(Dispatchers.IO) { snapshotStore.load() }
            if (snapshot != null) {
                // Cold start comes up on INFO. After a crash or a reboot, silently blasting
                // the last image onto the players' screen during relaunch is worse than the
                // info panel — or black, if the campaign configures none. The scene is
                // restored intact underneath, so one tap on the live slot resumes.
                //
                // A *reconnecting* display does not come through here: it re-renders live
                // state, because a display that could change state would be the source of
                // truth (§16 Trap 5).
                presentation.restore(snapshot.toState(startInInfo = true))
            }

            val saved = restoreRoot()
            if (saved != null) openRoot(saved) else _campaign.value = CampaignState.None

            // Started only after the restore, so the default state cannot overwrite
            // what we were about to load.
            presentation.state
                // Written only when the snapshot itself changes. A pinch updates the state at
                // gesture rate and none of the framing is persisted, so without this every
                // finger movement would be a disk write of identical data.
                .map { it.toSnapshot() }
                .distinctUntilChanged()
                .onEach { snapshot ->
                    withContext(Dispatchers.IO) { snapshotStore.save(snapshot) }
                }
                .launchIn(appScope)
        }
    }

    // ---- Campaign root ----------------------------------------------------------

    fun setRoot(treeUri: Uri) {
        rootStore.save(treeUri.toString())
        appScope.launch { openRoot(treeUri) }
    }

    private suspend fun openRoot(treeUri: Uri) {
        // The previous campaign's open panel is ended in its own journal before anything is
        // shown under the new one.
        stopRecording()

        val repository = ContentRepository(
            source = DocumentTreeSource(app.contentResolver, treeUri),
            notes = NoteReader(app.contentResolver),
        )
        val store = SafSlotStore(app.contentResolver, treeUri)

        slotStore = store
        _repository.value = repository
        _campaign.value = CampaignState.Open
        _slotWriteFailed.value = false

        // Read Campagne.md before anything can be presented, so the very first slot tap
        // already dissolves the way the campaign asked for.
        campaignFile = CampaignFile(app.contentResolver, treeUri)
        reloadCampaignConfig()

        // The bank is loaded as paths and only then resolved against the tree, so a
        // renamed file surfaces as a *missing* slot rather than vanishing (§6.1).
        slots.replaceAll(SlotBankState.unresolved(store.load()))
        resolveSlots(repository)

        startRecording(treeUri)
    }

    // ---- Séances --------------------------------------------------------------------

    private suspend fun startRecording(treeUri: Uri) {
        val campaign = treeUri.toString()
        val journal = SafTextFile(
            resolver = app.contentResolver,
            treeUri = treeUri,
            directory = BRIGADE_DIRECTORY,
            name = JOURNAL_FILE_NAME,
            mimeType = "text/tab-separated-values",
            allowPrefixMatch = true,
        )
        journalFile = journal
        seancesFile = SafTextFile(
            resolver = app.contentResolver,
            treeUri = treeUri,
            directory = BRIGADE_DIRECTORY,
            name = SEANCES_FILE_NAME,
            mimeType = "application/json",
            allowPrefixMatch = true,
        )
        seancePictures.clear()

        // A panel left open by a Brigade killed mid-session ends at its last heartbeat,
        // before the new recorder writes anything after it.
        val recorded = journal.read().getOrNull()?.let(JournalCodec::decodeAll).orEmpty()
        SessionRecorder.closeDangling(recorded, heartbeats.load(campaign))
            ?.let { appendToJournal(journal, it) }

        val next = SessionRecorder(
            clock = System::currentTimeMillis,
            append = { entry -> appendToJournal(journal, entry) },
            heartbeat = { at -> heartbeats.save(campaign, at) },
        )
        recorder = next
        recorderJob = next.start(
            scope = appScope,
            state = presentation.state,
            attached = playerDisplay.status.map { it is PlayerDisplayStatus.Attached },
        )
    }

    private suspend fun stopRecording() {
        recorderJob?.cancelAndJoin()
        recorderJob = null
        recorder?.close()
        recorder = null
    }

    private suspend fun appendToJournal(journal: SafTextFile, entry: JournalEntry) {
        val line = JournalCodec.encode(entry) ?: return
        _journalWriteFailed.value =
            !journal.append("$line\n", headerIfNew = JournalCodec.FILE_HEADER + "\n")
    }

    /**
     * Everything the Séances screen draws, read fresh from the journal.
     *
     * Live: the panel on screen right now counts up to now, so a test shows up the moment
     * something has been shown.
     */
    suspend fun loadSeances(): SeancesData {
        val repository = _repository.value ?: return SeancesData.EMPTY
        val entries = journalFile?.read()?.getOrNull()?.let(JournalCodec::decodeAll).orEmpty()
        val hidden = seancesFile?.read()?.getOrNull()?.let(HiddenDays::decode).orEmpty()

        val now = System.currentTimeMillis()
        val showings = Seances.showings(entries, openUntil = if (recorder?.isOpen == true) now else null)
        val byDay = Seances.byDay(showings, attributeImages(repository, showings), ZoneId.systemDefault())

        val pictures = byDay.values.flatMap { it.keys }.distinct().associateWith { panel ->
            seancePictures.getOrPut(panel) { pictureOf(repository, panel) }
        }
        return SeancesData(byDay = byDay, hidden = hidden, pictures = pictures.filterValuesNotNull())
    }

    // ---- Oracles ------------------------------------------------------------------

    /** The campaign's oracle tables, or null when it has no `Oracles/` folder. */
    suspend fun oracleTables(): List<ContentItem>? = _repository.value?.oracleTables()

    /** A table's entries, read afresh: the GM may be editing it in Obsidian beside Brigade. */
    suspend fun oracleEntries(table: ContentItem): List<String> =
        _repository.value?.readNote(table)?.listItems.orEmpty()

    /** Hides a day from every Séances view, or shows it again. Saved in `.brigade/seances.json`. */
    suspend fun setDayHidden(day: LocalDate, hidden: Boolean) {
        val file = seancesFile ?: return
        val current = file.read().getOrNull()?.let(HiddenDays::decode).orEmpty()
        val next = if (hidden) current + day else current - day
        if (next != current) file.write(HiddenDays.encode(next))
    }

    /**
     * The note or image a browser item stands for in the Séances history — for an image a note
     * presents, that note, since that is where its time on screen was filed.
     */
    suspend fun subjectOf(item: ContentItem, folder: ContentPath): Panel {
        val path = ContentPath.of(folder.segments + item.displayName)
        if (item.kind == ContentKind.Markdown) return Panel.Note(path)

        val repository = _repository.value ?: return Panel.Image(path)
        val note = runCatching { repository.notesByImage()[item.id] }.getOrNull()
        return note?.let { Panel.Note(it) } ?: Panel.Image(path)
    }

    /** What a note or image is drawn with: the image itself, or the one the note presents. */
    private suspend fun pictureOf(repository: ContentRepository, panel: Panel): ContentId? = when (panel) {
        is Panel.Image -> repository.resolve(panel.path)?.id
        is Panel.Note -> repository.resolve(panel.path)
            ?.let { repository.readNote(it) }
            ?.let { repository.firstImage(it, panel.path.parent) }
            ?.id
        Panel.Info, Panel.Black -> null
    }

    /**
     * For each raw image in the journal, the note that presents it — so an image shown by
     * mistake instead of its note is filed under that note.
     *
     * Reads every note in the campaign the first time, which is why it is skipped outright
     * when no raw image was ever shown.
     */
    private suspend fun attributeImages(
        repository: ContentRepository,
        showings: List<Showing>,
    ): Map<ContentPath, ContentPath> {
        val images = showings.mapNotNull { (it.panel as? Panel.Image)?.path }.distinct()
        if (images.isEmpty()) return emptyMap()

        val notesByImage = runCatching { repository.notesByImage() }.getOrElse { return emptyMap() }
        return images.mapNotNull { path ->
            val id = repository.resolve(path)?.id ?: return@mapNotNull null
            notesByImage[id]?.let { note -> path to note }
        }.toMap()
    }

    private fun <K, V : Any> Map<K, V?>.filterValuesNotNull(): Map<K, V> =
        mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    private suspend fun restoreRoot(): Uri? = withContext(Dispatchers.IO) {
        val saved = rootStore.load()?.let(Uri::parse) ?: return@withContext null

        // The grant survives reboots, but the user can revoke it in Settings and the
        // folder can be gone. Checking is cheaper than failing mysteriously later.
        val stillGranted = app.contentResolver.persistedUriPermissions
            .any { it.uri == saved && it.isReadPermission }

        if (!stillGranted) {
            rootStore.save(null)
            return@withContext null
        }
        saved
    }

    private suspend fun resolveSlots(repository: ContentRepository) {
        slots.state.value.slots.forEach { slot ->
            val path = when (val content = slot.content) {
                is SlotContent.Missing -> content.path
                is SlotContent.Filled -> content.path
                SlotContent.Empty -> return@forEach
            }

            val item = repository.resolve(path)
            if (item == null) {
                slots.markMissing(slot.id)
                return@forEach
            }

            val presented = presentableFor(item, path.parent)
            slots.markResolved(slot.id, item.displayName, presented.imageId, presented.note)
        }
    }

    /**
     * What a browsed or slotted item actually presents.
     *
     * An image presents itself. A note presents [ContentRepository.firstImage] — the same
     * call the browser makes for its thumbnail, so the two cannot disagree.
     */
    private suspend fun presentableFor(item: ContentItem, folder: ContentPath): Presentable {
        if (item.kind != ContentKind.Markdown) return Presentable(item.id, null)

        val repository = _repository.value ?: return Presentable(null, null)
        val document = repository.readNote(item) ?: return Presentable(null, null)

        return Presentable(repository.firstImage(document, folder)?.id, NoteBar.from(document))
    }

    /**
     * The campaign's in-world date, as written. Never per-note — see §23.
     *
     * Derived live from the config rather than stored, so a change to `Campagne.md` reaches
     * every note bar at once, including slots resolved long before.
     */
    val campaignDateIso: StateFlow<String?> = _campaignConfig
        .map { config ->
            config.info?.entries?.firstOrNull { it.key.equals("date", ignoreCase = true) }?.value
        }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    private data class Presentable(val imageId: ContentId?, val note: NoteBar?)

    // ---- Presentation actions ---------------------------------------------------

    /**
     * *Afficher*: transient, and deliberately does not touch the bank.
     *
     * A note shown this way fills the GM bar exactly as a slotted one does — the bar reflects
     * what is presented, not how it got there.
     */
    fun showNow(item: ContentItem, folder: ContentPath) {
        appScope.launch {
            val presented = presentableFor(item, folder)
            present(presented, fromSlot = null, origin = ContentPath.of(folder.segments + item.displayName))
        }
    }

    fun recall(slot: SlotId) {
        when (val content = slots.contentOf(slot)) {
            is SlotContent.Filled -> present(Presentable(content.imageId, content.note), slot, content.path)
            // Nothing to present; the bar already shows the slot as missing or empty.
            is SlotContent.Missing, SlotContent.Empty -> Unit
        }
    }

    /** @param origin the note's or image's campaign-relative path, for the session recap. */
    private fun present(presented: Presentable, fromSlot: SlotId?, origin: ContentPath) {
        val note = presented.note
        if (note != null) {
            presentation.showNote(presented.imageId, note, fromSlot, origin)
        } else if (presented.imageId != null) {
            presentation.show(presented.imageId, fromSlot, origin)
        }
    }

    /**
     * Re-reads `Campagne.md`.
     *
     * Called from *Actualiser*, and whenever the GM surface comes back to the foreground —
     * which is what makes editing the file in Obsidian, in the DeX split view, show up in
     * Brigade without touching anything. Cheap: one small file.
     *
     * Not a filesystem watch. A `ContentObserver` over the Storage Access Framework is
     * unreliable across document providers, and re-reading on resume catches every case that
     * actually happens at a table.
     */
    fun refreshCampaign() {
        appScope.launch { reloadCampaignConfig() }
        // A note edited in Obsidian may now present a different picture on the Séances screen.
        seancePictures.clear()
    }

    // ---- Timer -------------------------------------------------------------------

    fun startTimer(minutes: Int) = presentation.startTimer(minutes)

    fun extendTimer() = presentation.extendTimer()

    fun clearTimer() = presentation.clearTimer()

    // ---- Pointer -----------------------------------------------------------------

    fun panZoom(gesture: ViewportGesture) = presentation.panZoom(gesture)

    fun resetViewport() = presentation.resetViewport()

    fun toggleInfo() {
        // Toggled immediately, then the file is re-read in the background. A button that
        // waits on disk before doing anything is the wrong trade at a table; if the file did
        // change, the panel dissolves to the new content a moment later, which is correct.
        presentation.setInfoMode(presentation.state.value.scene.mode != SceneMode.Info)
        appScope.launch { reloadCampaignConfig() }
    }

    /**
     * Re-reads `Campagne.md` and rebuilds the info panel.
     *
     * Cheap enough to do on every INFO toggle, which is how an edit made in Obsidian — in
     * the DeX split view, mid-session — shows up without Brigade watching the filesystem.
     */
    private suspend fun reloadCampaignConfig() {
        val config = campaignFile?.read() ?: CampaignConfig.EMPTY
        _campaignConfig.value = config
        _transitionSpec.value = TransitionSpec.byName(config.transition)

        val info = config.info
        if (info == null || info.isEmpty) {
            presentation.setInfo(null, null)
            return
        }

        // Resolved here, not in the renderer: a Frame must arrive fully determined, and a
        // path lookup is exactly the kind of work that could come out differently in the
        // two windows.
        val background = info.background
            ?.let { path -> _repository.value?.resolve(path)?.id }

        presentation.setInfo(CampaignInfo.build(info), background)
    }

    // ---- Slot actions -----------------------------------------------------------

    /** @param folderNames folders between the campaign root and [item], root excluded. */
    fun assignSlot(slot: SlotId, item: ContentItem, folderNames: List<String>) {
        val folder = ContentPath.of(folderNames)
        val path = ContentPath.of(folderNames, item.displayName)

        // Assigned optimistically so the bar fills the instant it is tapped; a note's image
        // and properties are read straight after and the slot updates in place. Only the
        // path is persisted, so the slow half never blocks the write.
        slots.assign(slot, path, item.displayName, imageId = item.id.takeIf { item.kind != ContentKind.Markdown })
        persistSlots()

        if (item.kind == ContentKind.Markdown) {
            appScope.launch {
                val presented = presentableFor(item, folder)
                slots.markResolved(slot, item.displayName, presented.imageId, presented.note)
            }
        }
    }

    fun clearSlot(slot: SlotId) {
        slots.clear(slot)
        persistSlots()
    }

    /**
     * Only assignment and clearing change the stored paths, so persistence is explicit
     * here rather than a debounced observer over the bank — resolution does not need to
     * write anything back.
     */
    private fun persistSlots() {
        val store = slotStore ?: return
        val paths = slots.state.value.paths()
        appScope.launch {
            _slotWriteFailed.value = !store.save(paths)
        }
    }
}
