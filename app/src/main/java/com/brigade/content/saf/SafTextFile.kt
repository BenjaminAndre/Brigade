package com.brigade.content.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One UTF-8 text file in the campaign folder that Brigade owns: the session journal in
 * `.brigade/`, or the recap at the root.
 *
 * Looked up by name on every call rather than cached. The GM can delete either file in
 * Obsidian mid-session, and a cached document URI would then fail every write until a
 * restart; a lookup is one query, and these files are touched every few minutes at most.
 *
 * Every operation holds one lock, so the recorder appending and the recap being rendered
 * never interleave on the same file.
 *
 * @param directory a folder at the campaign root to keep the file in, created on first
 * write; null for the root itself.
 */
class SafTextFile(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val directory: String?,
    private val name: String,
    private val mimeType: String,
    private val allowPrefixMatch: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    /**
     * The file's text, `null` when it does not exist — or a failure when it exists and cannot
     * be read, which is not the same thing: a caller about to overwrite must not mistake an
     * unreadable file for an absent one.
     */
    suspend fun read(): Result<String?> = mutex.withLock {
        withContext(io) {
            runCatching {
                val file = find() ?: return@runCatching null
                resolver.openInputStream(file)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("$name could not be opened")
            }
        }
    }

    /** Replaces the file's contents, creating it if needed. False on failure, never a throw. */
    suspend fun write(text: String): Boolean = mutex.withLock {
        withContext(io) {
            runCatching {
                val file = find() ?: create() ?: return@runCatching false
                writeTo(file, text)
            }.getOrDefault(false)
        }
    }

    /**
     * Adds [text] to the end, creating the file — starting with [headerIfNew] — if needed.
     *
     * Appends in place where the provider allows it, which is the safe way to grow a log: a
     * failure then loses the new line, not the file. Where it does not, falls back to
     * rewriting the whole file, which for a journal of a few hundred lines is nothing.
     */
    suspend fun append(text: String, headerIfNew: String = ""): Boolean = mutex.withLock {
        withContext(io) {
            runCatching {
                val existing = find()
                    ?: return@runCatching create()?.let { writeTo(it, headerIfNew + text) } ?: false

                val appended = runCatching {
                    resolver.openOutputStream(existing, "wa")?.use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }.getOrDefault(false)
                if (appended) return@runCatching true

                val old = resolver.openInputStream(existing)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@runCatching false
                writeTo(existing, old + text)
            }.getOrDefault(false)
        }
    }

    // Mode "wt" truncates. Without it, writing a shorter text leaves the tail of the longer
    // one before it behind.
    private fun writeTo(file: Uri, text: String): Boolean =
        resolver.openOutputStream(file, "wt")?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            true
        } ?: false

    private fun find(): Uri? {
        val parent = directoryUri(create = false) ?: return null
        return resolver.findChild(treeUri, parent, name, allowPrefixMatch)
    }

    private fun create(): Uri? {
        val parent = directoryUri(create = true) ?: return null
        return DocumentsContract.createDocument(resolver, parent, mimeType, name)
    }

    private fun directoryUri(create: Boolean): Uri? {
        val root = rootDocumentUri(treeUri)
        val folder = directory ?: return root
        return resolver.findChild(treeUri, root, folder)
            ?: if (create) {
                DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, folder)
            } else {
                null
            }
    }
}
