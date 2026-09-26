package com.brigade.content.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** The campaign root as a document, which is what children are created under. */
internal fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
    treeUri,
    DocumentsContract.getTreeDocumentId(treeUri),
)

/**
 * Finds a child of [parent] by display name.
 *
 * With [allowPrefixMatch], falls back to the first child whose name *starts* with [name],
 * because some providers derive the extension from the mime type and will happily create
 * `slots.json.json`. Without the fallback Brigade would fail to find its own file and create
 * a new one on every launch. Off for a file at the campaign root, where a prefix match could
 * just as well be a file of the GM's.
 */
internal fun ContentResolver.findChild(
    treeUri: Uri,
    parent: Uri,
    name: String,
    allowPrefixMatch: Boolean = true,
): Uri? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getDocumentId(parent),
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )

    var prefixMatch: String? = null
    query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (idColumn < 0 || nameColumn < 0) return null

        while (cursor.moveToNext()) {
            val childName = cursor.getString(nameColumn) ?: continue
            val childId = cursor.getString(idColumn) ?: continue
            if (childName == name) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
            }
            if (allowPrefixMatch && prefixMatch == null && childName.startsWith(name)) prefixMatch = childId
        }
    }
    return prefixMatch?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
}
