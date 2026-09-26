package com.brigade.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.brigade.BrigadeApp
import com.brigade.content.ContentId
import com.brigade.content.ContentItem
import com.brigade.content.ContentKind
import com.brigade.content.ContentPath
import com.brigade.content.ContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One level of the campaign tree. [name] is null for the campaign root, whose label is
 * a string resource — keeping French out of Kotlin (§21.1).
 */
data class FolderCrumb(val id: ContentId, val name: String?)

enum class BrowsingError { Unavailable }

/**
 * What the GM is *looking at*.
 *
 * Deliberately separate from `PresentationState`, which is what the players see
 * (§16 Trap 4). With the slot bank this is more than a modelling nicety: browsing
 * genuinely never touches the presentation, so the GM can wander the whole campaign
 * mid-scene without revealing anything.
 */
data class BrowsingState(
    val stack: List<FolderCrumb> = emptyList(),
    val entries: List<ContentItem> = emptyList(),
    val loading: Boolean = false,
    val error: BrowsingError? = null,

    /**
     * Each note's thumbnail: the image recalling it would present. A note is absent until it
     * has been read, and stays absent when it links no image — both show as the filename.
     */
    val noteImages: Map<ContentId, ContentId> = emptyMap(),
) {
    val currentFolder: ContentId? get() = stack.lastOrNull()?.id
    val canGoUp: Boolean get() = stack.size > 1

    /** Folders between the campaign root and here, root excluded. Builds slot paths. */
    val folderNames: List<String> get() = stack.drop(1).mapNotNull { it.name }

    /** The same, as a path — what a note's links resolve relative to. */
    val folderPath: ContentPath get() = ContentPath.of(folderNames)

    val folders: List<ContentItem> get() = entries.filter { it.kind == ContentKind.Folder }
    val images: List<ContentItem> get() = entries.filter { it.kind == ContentKind.Image }
    val documents: List<ContentItem> get() = entries.filter { it.kind == ContentKind.Markdown }
    val isEmpty: Boolean get() = folders.isEmpty() && images.isEmpty() && documents.isEmpty()
}

class GmViewModel(
    private val repositoryFlow: StateFlow<ContentRepository?>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _browsing = MutableStateFlow(BrowsingState())
    val browsing: StateFlow<BrowsingState> = _browsing.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repositoryFlow.collectLatest { repository ->
                if (repository == null) {
                    _browsing.value = BrowsingState()
                } else {
                    restoreLocation(repository)
                }
            }
        }
    }

    // ---- Navigation -------------------------------------------------------------
    //
    // The back stack IS BrowsingState.stack, so every operation is a list operation and
    // no navigation library is needed.

    fun enter(folder: ContentItem) {
        if (folder.kind != ContentKind.Folder) return
        _browsing.update { it.copy(stack = it.stack + FolderCrumb(folder.id, folder.displayName)) }
        reload()
    }

    fun up() {
        if (!_browsing.value.canGoUp) return
        _browsing.update { it.copy(stack = it.stack.dropLast(1)) }
        reload()
    }

    /** Jumps to the crumb at [index]; index 0 is the campaign root. */
    fun jumpTo(index: Int) {
        val stack = _browsing.value.stack
        if (index !in stack.indices || index == stack.lastIndex) return
        _browsing.update { it.copy(stack = it.stack.take(index + 1)) }
        reload()
    }

    fun refresh() {
        viewModelScope.launch {
            repositoryFlow.value?.invalidate()
            reload(force = true)
        }
    }

    // ---- Loading ----------------------------------------------------------------

    private suspend fun restoreLocation(repository: ContentRepository) {
        // The location is saved as folder *names*, not ids: names survive a process
        // death and a reinstall, which document ids under a fresh grant may not.
        //
        // It is keyed by root, though, precisely because names are not unique across
        // campaigns. Without that check, switching from a campaign where you were in
        // Cartes/Donjon to one that also has those folders would drop you three levels
        // deep in the new campaign instead of at its root.
        val savedSegments = if (savedState.get<String>(KEY_LOCATION_ROOT) == repository.rootId.value) {
            savedState.get<ArrayList<String>>(KEY_LOCATION).orEmpty()
        } else {
            emptyList()
        }

        var stack = listOf(FolderCrumb(repository.rootId, null))
        var folder = repository.rootId
        for (segment in savedSegments) {
            val match = runCatching { repository.children(folder) }.getOrNull()
                ?.firstOrNull { it.displayName == segment && it.kind == ContentKind.Folder }
                ?: break
            stack = stack + FolderCrumb(match.id, match.displayName)
            folder = match.id
        }

        _browsing.value = BrowsingState(stack = stack)
        reload()
    }

    private fun reload(force: Boolean = false) {
        val repository = repositoryFlow.value ?: return
        val folder = _browsing.value.currentFolder ?: return
        val folderPath = _browsing.value.folderPath

        // One load at a time. Without this, leaving a folder before its notes were all read
        // would keep reading them — and a slow listing could land after a newer one.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _browsing.update { it.copy(loading = true, error = null) }
            val entries = runCatching { repository.children(folder, refresh = force) }
            // runCatching also catches the cancellation from a newer load. Stop here rather
            // than reporting it as an unreadable folder.
            ensureActive()

            _browsing.update { state ->
                entries.fold(
                    onSuccess = { listed ->
                        val ids = listed.mapTo(HashSet()) { it.id }
                        state.copy(
                            entries = listed,
                            loading = false,
                            error = null,
                            // Kept across a refresh, so thumbnails do not blink out and back.
                            noteImages = state.noteImages.filterKeys { it in ids },
                        )
                    },
                    onFailure = {
                        state.copy(entries = emptyList(), loading = false, error = BrowsingError.Unavailable)
                    },
                )
            }
            savedState[KEY_LOCATION] = ArrayList(_browsing.value.folderNames)
            savedState[KEY_LOCATION_ROOT] = repository.rootId.value

            entries.getOrNull()?.let { loadNoteImages(repository, it, folderPath) }
        }
    }

    /**
     * Reads the folder's notes for their thumbnails, one after another, after the grid is
     * already on screen with their filenames.
     *
     * Only this folder's notes — never the tree — and in grid order, so the top of the grid
     * fills first. Read again on every visit: notes change in Obsidian beside Brigade, and a
     * folder of notes is a few small files.
     */
    private suspend fun loadNoteImages(
        repository: ContentRepository,
        entries: List<ContentItem>,
        folderPath: ContentPath,
    ) {
        entries.filter { it.kind == ContentKind.Markdown }.forEach { note ->
            val image = runCatching {
                repository.readNote(note)?.let { repository.firstImage(it, folderPath) }
            }.getOrNull()
            currentCoroutineContext().ensureActive()

            _browsing.update { state ->
                state.copy(
                    noteImages = if (image != null) {
                        state.noteImages + (note.id to image.id)
                    } else {
                        // A note edited since the last visit may no longer link an image.
                        state.noteImages - note.id
                    },
                )
            }
        }
    }

    companion object {

        private const val KEY_LOCATION = "browsing_location"
        private const val KEY_LOCATION_ROOT = "browsing_location_root"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BrigadeApp
                GmViewModel(app.graph.repository, createSavedStateHandle())
            }
        }
    }
}
