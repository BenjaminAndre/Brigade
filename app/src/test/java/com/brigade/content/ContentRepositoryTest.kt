package com.brigade.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRepositoryTest {

    private val root = ContentId("fake://root")
    private val cartes = ContentId("fake://cartes")
    private val donjon = ContentId("fake://donjon")

    private fun repository() = ContentRepository(
        FakeContentSource(
            rootId = root,
            tree = mapOf(
                root to listOf(
                    image("fake://b", "carte10.png", root.value),
                    folder(cartes.value, "Cartes", root.value),
                    image("fake://a", "carte2.png", root.value),
                    folder("fake://hidden", ".brigade", root.value),
                ),
                cartes to listOf(folder(donjon.value, "Donjon", cartes.value)),
                donjon to listOf(image("fake://salle", "Salle-4.png", donjon.value)),
            ),
        ),
    )

    @Test
    fun `dotfiles are hidden from the browser`() = runTest {
        val names = repository().children(root).map { it.displayName }
        assertEquals(listOf("Cartes", "carte2.png", "carte10.png"), names)
    }

    @Test
    fun `listings come back ordered`() = runTest {
        val images = repository().images(root).map { it.displayName }
        assertEquals(listOf("carte2.png", "carte10.png"), images)
    }

    @Test
    fun `a second read is served from cache`() = runTest {
        val source = FakeContentSource(rootId = root, tree = mapOf(root to listOf(image("x", "a.png"))))
        val repository = ContentRepository(source)

        repository.children(root)
        repository.children(root)

        assertEquals(1, source.listCalls)
    }

    @Test
    fun `refresh re-queries the source`() = runTest {
        val source = FakeContentSource(rootId = root, tree = mapOf(root to listOf(image("x", "a.png"))))
        val repository = ContentRepository(source)

        repository.children(root)
        repository.children(root, refresh = true)

        assertEquals(2, source.listCalls)
    }

    /** The cross-folder recall that makes the slot bank worth having (§6.1). */
    @Test
    fun `resolve walks several levels from the campaign root`() = runTest {
        val item = repository().resolve(ContentPath("Cartes/Donjon/Salle-4.png"))
        assertEquals("Salle-4.png", item?.displayName)
    }

    @Test
    fun `resolve returns null for a renamed file`() = runTest {
        assertNull(repository().resolve(ContentPath("Cartes/Donjon/Salle-5.png")))
    }

    @Test
    fun `resolve returns null when a path segment is a file, not a folder`() = runTest {
        assertNull(repository().resolve(ContentPath("carte2.png/nested.png")))
    }

    @Test
    fun `resolve returns null for an empty path`() = runTest {
        assertNull(repository().resolve(ContentPath("")))
    }

    // ---- Notes -------------------------------------------------------------------

    private val personnages = ContentId("fake://personnages")
    private val jadeFoxNote = note("fake://jade-fox-md", "Jade Fox.md", personnages.value)

    private fun campaign(notes: NoteSource = NoteSource { null }) = ContentRepository(
        source = FakeContentSource(
            rootId = root,
            tree = mapOf(
                root to listOf(folder(personnages.value, "Personnages", root.value)),
                personnages to listOf(
                    jadeFoxNote,
                    note("fake://secrets-md", "Secrets.md", personnages.value),
                    image("fake://jade-fox-png", "Jade-Fox.png", personnages.value),
                ),
            ),
        ),
        notes = notes,
    )

    private fun links(vararg targets: String) =
        NoteDocument(name = "Jade Fox", imageLinks = targets.toList())

    private val inPersonnages = ContentPath("Personnages")

    @Test
    fun `a note presents the first link that resolves, skipping broken and remote ones`() = runTest {
        val document = links("https://example.com/fox.png", "Renamed.png", "Jade-Fox.png")

        val image = campaign().firstImage(document, inPersonnages)

        assertEquals("Jade-Fox.png", image?.displayName)
    }

    /** An embedded note resolves — it is a file — but there is nothing in it to show the table. */
    @Test
    fun `an embedded note is skipped rather than presented as a picture`() = runTest {
        val document = links("Secrets.md", "Jade-Fox.png")

        val image = campaign().firstImage(document, inPersonnages)

        assertEquals("Jade-Fox.png", image?.displayName)
    }

    @Test
    fun `a note linking nothing presentable has no image`() = runTest {
        assertNull(campaign().firstImage(links("Secrets.md", "Nowhere.png"), inPersonnages))
        assertNull(campaign().firstImage(links(), inPersonnages))
    }

    @Test
    fun `notes are read through the note source`() = runTest {
        val document = links("Jade-Fox.png")
        val repository = campaign(notes = { if (it == jadeFoxNote) document else null })

        assertEquals(document, repository.readNote(jadeFoxNote))
    }

    /** Notes are edited in Obsidian mid-session; presenting one must show what it says now. */
    @Test
    fun `notes are read afresh every time, never cached`() = runTest {
        var reads = 0
        val repository = campaign(notes = { reads++; links() })

        repository.readNote(jadeFoxNote)
        repository.readNote(jadeFoxNote)

        assertEquals(2, reads)
    }

    // ---- The session recap ---------------------------------------------------------

    /** Brigade's own record, not content: recalling it would present black with a bar. */
    @Test
    fun `the recap at the campaign root is hidden from the browser`() = runTest {
        val repository = ContentRepository(
            FakeContentSource(
                rootId = root,
                tree = mapOf(
                    root to listOf(
                        note("fake://recap", RECAP_FILE_NAME, root.value),
                        folder(cartes.value, "Cartes", root.value),
                    ),
                    cartes to listOf(note("fake://other-recap", RECAP_FILE_NAME, cartes.value)),
                ),
            ),
        )

        assertEquals(listOf("Cartes"), repository.children(root).map { it.displayName })
        // Only the root one is Brigade's; a note of that name anywhere else is the GM's.
        assertEquals(listOf(RECAP_FILE_NAME), repository.children(cartes).map { it.displayName })
    }

    /** So that a raw portrait shown by mistake is filed under its note in the recap. */
    @Test
    fun `each image a note presents maps back to that note`() = runTest {
        val repository = campaign(
            notes = { item -> if (item == jadeFoxNote) links("Jade-Fox.png") else links() },
        )

        val byImage = repository.notesByImage()

        assertEquals(
            mapOf(ContentId("fake://jade-fox-png") to ContentPath("Personnages/Jade Fox.md")),
            byImage,
        )
    }

    @Test
    fun `the reverse index is built once, until invalidated`() = runTest {
        var reads = 0
        val repository = campaign(notes = { reads++; links("Jade-Fox.png") })

        repository.notesByImage()
        repository.notesByImage()
        val afterTwo = reads
        repository.invalidate()
        repository.notesByImage()

        // Two notes in the campaign, read once per build.
        assertEquals(2, afterTwo)
        assertEquals(4, reads)
    }
}
