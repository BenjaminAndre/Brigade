package com.brigade.content

/**
 * Reads a campaign note.
 *
 * The same seam as [ContentSource]: the implementation needs a `ContentResolver` and lives in
 * `saf/`, while [ContentRepository] — and therefore the one rule for which image a note
 * presents — stays plain Kotlin that the JVM tests can reach.
 */
fun interface NoteSource {

    /** The parsed note, or null when it cannot be read at all. */
    suspend fun read(note: ContentItem): NoteDocument?
}
