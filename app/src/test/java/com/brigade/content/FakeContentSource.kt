package com.brigade.content

/**
 * In-memory [ContentSource].
 *
 * Hand-written rather than mocked: it is shorter than the mock setup would be, it reads
 * as data, and it does not break on a library upgrade.
 */
class FakeContentSource(
    override val rootId: ContentId = ContentId("fake://root"),
    private val tree: Map<ContentId, List<ContentItem>> = emptyMap(),
    private val available: Boolean = true,
) : ContentSource {

    var listCalls: Int = 0
        private set

    override suspend fun listChildren(parent: ContentId): List<ContentItem> {
        listCalls++
        return tree[parent].orEmpty()
    }

    override suspend fun itemOf(id: ContentId): ContentItem? =
        tree.values.flatten().firstOrNull { it.id == id }

    override suspend fun isAvailable(): Boolean = available
}

fun folder(id: String, name: String, parent: String? = null) = ContentItem(
    id = ContentId(id),
    parentId = parent?.let(::ContentId),
    displayName = name,
    mimeType = ContentClassifier.MIME_DIRECTORY,
    kind = ContentKind.Folder,
)

fun image(id: String, name: String, parent: String? = null) = ContentItem(
    id = ContentId(id),
    parentId = parent?.let(::ContentId),
    displayName = name,
    mimeType = "image/png",
    kind = ContentKind.Image,
)

fun note(id: String, name: String, parent: String? = null) = ContentItem(
    id = ContentId(id),
    parentId = parent?.let(::ContentId),
    displayName = name,
    mimeType = "text/markdown",
    kind = ContentKind.Markdown,
)
