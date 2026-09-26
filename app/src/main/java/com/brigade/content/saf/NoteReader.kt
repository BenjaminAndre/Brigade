package com.brigade.content.saf

import android.content.ContentResolver
import android.net.Uri
import com.brigade.content.ContentItem
import com.brigade.content.Markdown
import com.brigade.content.NoteDocument
import com.brigade.content.NoteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Reads a campaign note.
 *
 * Keys are English (`element:`, `faction:`); values are the GM's own French. That split is
 * deliberate: the keys are Brigade's interface and match `Campagne.md`, the values are
 * campaign content and are never translated or normalised here (§21.3).
 *
 * Brigade only ever **reads** notes. Nothing in the app writes to one.
 */
class NoteReader(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NoteSource {

    /**
     * @return the parsed note, or null when it cannot be read at all. A note that is present
     * but has no frontmatter and no links is perfectly valid and yields a [NoteDocument] with
     * just a name — which is still enough to fill the bar.
     */
    override suspend fun read(note: ContentItem): NoteDocument? = withContext(io) {
        runCatching {
            val text = resolver.openInputStream(Uri.parse(note.id.value))?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@runCatching null

            parse(note.displayName, text)
        }.getOrNull()
    }

    companion object {

        /** Pure, so the whole format is testable on the JVM — where CI is the only check. */
        fun parse(displayName: String, text: String): NoteDocument {
            val properties = frontmatterProperties(text)

            return NoteDocument(
                // The character name IS the filename, so a note is named by renaming the file
                // in Obsidian rather than by keeping a `name:` property in sync with it.
                name = displayName.substringBeforeLast('.').trim(),
                element = properties[KEY_ELEMENT],
                faction = properties[KEY_FACTION],
                imageLinks = Markdown.imageLinks(text),
            )
        }

        /**
         * Top-level scalar properties, as strings.
         *
         * Total: a note with broken YAML still yields its name and its links, because losing
         * the whole note over a stray bracket in frontmatter would be a poor trade mid-session.
         */
        private fun frontmatterProperties(text: String): Map<String, String> = runCatching {
            val block = Markdown.frontmatter(text) ?: return@runCatching emptyMap()

            // SafeConstructor for the same reason as Campagne.md: the default one can
            // instantiate arbitrary classes named by YAML tags.
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(block) as? Map<*, *>
                ?: return@runCatching emptyMap()

            root.entries.mapNotNull { (key, value) ->
                val name = key?.toString()?.trim()?.lowercase() ?: return@mapNotNull null
                val text = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                name to text
            }.toMap()
        }.getOrDefault(emptyMap())

        private const val KEY_ELEMENT = "element"
        private const val KEY_FACTION = "faction"
    }
}
