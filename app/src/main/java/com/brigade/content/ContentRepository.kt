package com.brigade.content

import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session recap Brigade writes at the campaign root.
 *
 * Named here, in `content/`, because it is the one campaign-root file the browser hides: it
 * is Brigade's own record, not content to present, and a note that recalled as black with
 * «Brigade» in the bar would only be a trap at the table.
 */
const val RECAP_FILE_NAME: String = "Brigade.md"

/**
 * Caching, filtering and ordering over a [ContentSource].
 *
 * Lives on the application graph, not on a ViewModel, so the cache survives Activity
 * recreation — which on a DeX tablet happens every time the window is dragged or the
 * device is rotated.
 */
class ContentRepository(
    private val source: ContentSource,
    private val notes: NoteSource = NoteSource { null },
) {

    private val mutex = Mutex()
    private val cache = LinkedHashMap<ContentId, List<ContentItem>>()

    /** Guards [filenameIndex] separately: building it calls [children], which takes [mutex]. */
    private val indexMutex = Mutex()
    private var filenameIndex: Map<String, List<IndexedFile>>? = null

    /** Its own lock again: building [notesByImage] reads notes, which reaches [filenameIndex]. */
    private val notesIndexMutex = Mutex()
    private var notesByImage: Map<ContentId, ContentPath>? = null

    val rootId: ContentId get() = source.rootId

    suspend fun children(folder: ContentId, refresh: Boolean = false): List<ContentItem> =
        mutex.withLock {
            if (!refresh) cache[folder]?.let { return@withLock it }
            val atRoot = folder == source.rootId
            val listed = source.listChildren(folder)
                // Hides .brigade/ and .git/ from the browser. §2.3 keeps application state out
                // of the way of campaign content, and this is the other half of that bargain:
                // the GM never has to look at it.
                .filterNot { it.displayName.startsWith(".") }
                .filterNot { atRoot && it.displayName == RECAP_FILE_NAME }
                .sortedWith(ContentOrder.comparator)
            cache[folder] = listed
            listed
        }

    suspend fun images(folder: ContentId): List<ContentItem> =
        children(folder).filter { it.kind == ContentKind.Image }

    suspend fun markdown(folder: ContentId): List<ContentItem> =
        children(folder).filter { it.kind == ContentKind.Markdown }

    suspend fun folders(folder: ContentId): List<ContentItem> =
        children(folder).filter { it.kind == ContentKind.Folder }

    /**
     * Walks [path] from the campaign root by display name.
     *
     * Returns null when any segment is missing or is not the kind it needs to be — a slot
     * whose file was renamed resolves to null and is rendered as *missing* rather than
     * silently vanishing (§6.1).
     */
    suspend fun resolve(path: ContentPath): ContentItem? {
        val segments = path.segments
        if (segments.isEmpty()) return null

        var folder = source.rootId
        segments.forEachIndexed { index, segment ->
            val match = children(folder).firstOrNull { it.displayName == segment } ?: return null
            if (index == segments.lastIndex) return match
            if (match.kind != ContentKind.Folder) return null
            folder = match.id
        }
        return null
    }

    /**
     * Reads [note] afresh.
     *
     * Deliberately not cached, unlike listings: the GM edits notes in Obsidian beside Brigade
     * mid-session, and presenting one should show what it says now.
     */
    suspend fun readNote(note: ContentItem): NoteDocument? = notes.read(note)

    /**
     * The image a note presents: the first of its embeds that **resolves to an image**, not
     * the first one written. An `http` URL, a link to a renamed file, or an embedded note or
     * PDF is skipped rather than leaving the players looking at black.
     *
     * The one place this rule lives. Presenting a note and drawing its thumbnail both come
     * through here, so the browser shows exactly the picture the players will get.
     *
     * @param noteFolder the campaign-relative folder the note sits in.
     */
    suspend fun firstImage(document: NoteDocument, noteFolder: ContentPath): ContentItem? =
        document.imageLinks.firstNotNullOfOrNull { link ->
            resolveLink(link, noteFolder)?.takeIf { it.kind == ContentKind.Image }
        }

    /**
     * Resolves one image link written inside a note.
     *
     * Two different rules, because the two syntaxes mean different things:
     *
     *  - **A target containing `/` is a path**, relative to the note's own folder, with `..`
     *    honoured. That is what `![](../Portraits/Jade-Fox.png)` means everywhere.
     *  - **A bare filename is a name**, looked up across the whole campaign. That is what
     *    Obsidian's `![[Jade-Fox.png]]` means — not a path, but "the file called this,
     *    wherever it lives".
     *
     * @param noteFolder the campaign-relative folder the note sits in.
     * @return null for an unresolvable target — a broken link, or an `http` URL, which cannot
     * be fetched by an app with no network permission and no wish for one.
     */
    suspend fun resolveLink(target: String, noteFolder: ContentPath): ContentItem? {
        val clean = target.trim()
        if (clean.isEmpty() || clean.isRemote()) return null

        if (clean.contains('/')) {
            ContentPath.relativeTo(noteFolder, clean)?.let { resolve(it) }?.let { return it }
            // A target that did not resolve relative to the note may still be written from the
            // campaign root, which is how a hand-written link often reads.
            return resolve(ContentPath(clean))
        }

        return byFilename(clean, noteFolder)
    }

    /**
     * Obsidian-style lookup by filename.
     *
     * Duplicate filenames across folders are legal and common, so the tie-break is written
     * down rather than left to whatever the tree walk happened to reach first: **the note's
     * own folder, then the shallowest path, then natural order.** Deterministic, and it
     * matches the intuition that a link most likely means the file sitting next to the note.
     */
    private suspend fun byFilename(name: String, noteFolder: ContentPath): ContentItem? {
        val index = filenameIndex()

        // Obsidian lets an embed omit the extension. Cheap to support once the index exists.
        val candidates = index[name.lowercase(Locale.ROOT)]
            ?: IMAGE_EXTENSIONS.firstNotNullOfOrNull { index["$name.$it".lowercase(Locale.ROOT)] }
            ?: return null

        return candidates
            .sortedWith(
                compareBy<IndexedFile> { if (it.folder.value == noteFolder.value) 0 else 1 }
                    .thenBy { it.folder.segments.size }
                    .thenComparator { a, b ->
                        ContentOrder.compareNatural(a.item.displayName, b.item.displayName)
                    },
            )
            .firstOrNull()
            ?.item
    }

    /**
     * Every non-folder in the campaign, keyed by lowercased filename.
     *
     * Built lazily and once: it costs one query per folder, which is bounded by the size of
     * the campaign and only ever paid when a note actually contains a bare-filename link.
     * Cleared by [invalidate] along with everything else.
     */
    private suspend fun filenameIndex(): Map<String, List<IndexedFile>> = indexMutex.withLock {
        filenameIndex?.let { return@withLock it }

        val built = LinkedHashMap<String, MutableList<IndexedFile>>()
        forEachFile { item, folder ->
            built.getOrPut(item.displayName.lowercase(Locale.ROOT)) { mutableListOf() }
                .add(IndexedFile(item, folder))
        }

        filenameIndex = built
        built
    }

    /**
     * Every image a note presents, mapped to that note — [firstImage], reversed.
     *
     * For the session recap, which files a raw image shown by mistake under the note it
     * belongs to, even when that note was never shown itself.
     *
     * Reads every note in the campaign, so unlike [readNote] it is kept: built on first use,
     * dropped by [invalidate]. An image newly added to a note mid-session is therefore
     * attributed from the next *Actualiser* on. When two notes present the same image, the
     * shallowest wins, then natural order — the same tie-break as a bare-filename link.
     */
    suspend fun notesByImage(): Map<ContentId, ContentPath> = notesIndexMutex.withLock {
        notesByImage?.let { return@withLock it }

        val built = LinkedHashMap<ContentId, ContentPath>()
        forEachFile { item, folder ->
            if (item.kind != ContentKind.Markdown) return@forEachFile
            val image = readNote(item)?.let { firstImage(it, folder) } ?: return@forEachFile
            built.putIfAbsent(image.id, ContentPath.of(folder.segments + item.displayName))
        }

        notesByImage = built
        built
    }

    /**
     * Every non-folder in the campaign with the folder it sits in, breadth first — so
     * shallower files come first, and within a folder in natural order.
     */
    private suspend fun forEachFile(block: suspend (item: ContentItem, folder: ContentPath) -> Unit) {
        val queue = ArrayDeque<Pair<ContentId, ContentPath>>()
        queue += source.rootId to ContentPath("")

        while (queue.isNotEmpty()) {
            val (folder, path) = queue.removeFirst()
            children(folder).forEach { item ->
                if (item.kind == ContentKind.Folder) {
                    queue += item.id to ContentPath.of(path.segments + item.displayName)
                } else {
                    block(item, path)
                }
            }
        }
    }

    suspend fun isAvailable(): Boolean = source.isAvailable()

    suspend fun invalidate() {
        mutex.withLock { cache.clear() }
        indexMutex.withLock { filenameIndex = null }
        notesIndexMutex.withLock { notesByImage = null }
    }

    private data class IndexedFile(val item: ContentItem, val folder: ContentPath)

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")

        fun String.isRemote(): Boolean =
            startsWith("http://", ignoreCase = true) ||
                startsWith("https://", ignoreCase = true) ||
                startsWith("data:", ignoreCase = true)
    }
}
