package com.brigade.content

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleTest {

    @Test
    fun `only list lines are entries, so a table can explain itself`() {
        val text = """
            ---
            tags: [oracle]
            ---
            # Émotions

            *Tirer quand un PNJ réagit.*

            - Colère froide
            * Honte
            + Soulagement
            1. Envie
            12) Mélancolie
        """.trimIndent()

        assertEquals(
            listOf("Colère froide", "Honte", "Soulagement", "Envie", "Mélancolie"),
            Oracle.entries(text),
        )
    }

    /** Lets the GM keep notes under an entry without rolling them. */
    @Test
    fun `indented lines are notes, not entries`() {
        val text = "- Batelier\n    - connaît les passeurs du Qiantang\n\t- et leurs dettes\n- Scribe"

        assertEquals(listOf("Batelier", "Scribe"), Oracle.entries(text))
    }

    @Test
    fun `links read as their text and task boxes vanish`() {
        val text = "- [[PNJs/Qin Hui|Qin Hui]] s'en mêle\n- [[Yue Fei]] en personne\n- [ ] Une dette"

        assertEquals(listOf("Qin Hui s'en mêle", "Yue Fei en personne", "Une dette"), Oracle.entries(text))
    }

    /** Duplicates are how a table is weighted, so they must survive. */
    @Test
    fun `repeated lines are kept`() {
        assertEquals(listOf("Oui", "Oui", "Non"), Oracle.entries("- Oui\n- Oui\n- Non"))
    }

    @Test
    fun `empty markers and a table without a list give nothing`() {
        assertTrue(Oracle.entries("- \n-\n# Rien ici").isEmpty())
        assertNull(Oracle.roll(emptyList()))
    }

    @Test
    fun `every entry can come up`() {
        val entries = listOf("a", "b", "c")
        val random = Random(1137)

        val seen = (1..200).mapNotNull { Oracle.roll(entries, random) }.toSet()

        assertEquals(entries.toSet(), seen)
    }
}
