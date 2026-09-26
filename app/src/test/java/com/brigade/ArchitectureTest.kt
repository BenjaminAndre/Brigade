package com.brigade

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Enforces the package dependency rules from `ARCHITECTURE.md` by reading the source.
 *
 * Brigade is a single Gradle module, so the compiler does not enforce these boundaries.
 * Forty lines of test does, which is a great deal cheaper than four build files, four
 * manifests and an `api`/`implementation` decision on every new type.
 *
 * The rule that matters most is the first one: keeping `content/` and `presentation/`
 * free of `android.*` is exactly what lets the interesting logic be unit-tested here at
 * all, with no Robolectric and no emulator.
 */
class ArchitectureTest {

    private data class Rule(
        val packagePath: String,
        val forbidden: List<String>,
        val exceptPaths: List<String> = emptyList(),
    )

    private val rules = listOf(
        // Pure domain. content/saf is the single Android-touching leaf.
        //
        // org.yaml is forbidden here even though it is not `android.*`: the campaign file
        // parser belongs in content/saf (it needs the ContentResolver anyway) and must hand
        // these packages a plain data class. Without this rule snakeyaml would pass the
        // test while violating its whole point.
        Rule(
            "content",
            listOf("android.", "androidx.", "coil3.", "org.yaml", "com.brigade.ui"),
            listOf("content/saf"),
        ),
        Rule("presentation", listOf("android.", "androidx.", "coil3.", "org.yaml", "com.brigade.ui")),

        // The session recap: which sessions, how long, what Brigade.md says. Pure, because
        // the rules the GM set — six hours, thirty minutes, notes over raw images — are the
        // whole feature, and are only worth anything if the JVM tests can hold them.
        Rule(
            "journal",
            listOf(
                "android.",
                "androidx.",
                "coil3.",
                "org.yaml",
                "com.brigade.ui",
                "com.brigade.render",
                "com.brigade.display",
            ),
        ),

        // The shared renderer must not know which window it is in, nor reach for UI.
        //
        // Nor for Material: the GM theme follows the tablet's light/dark mode, and a renderer
        // reading MaterialTheme would make the players' screen change with it. What the table
        // sees is campaign content on black, whatever the GM's tablet is set to.
        Rule("render", listOf("com.brigade.ui", "com.brigade.display", "androidx.compose.material")),

        // The player display is a renderer, not a screen.
        Rule("display", listOf("com.brigade.ui", "androidx.compose.material")),
    )

    @Test
    fun `package dependencies point one way`() {
        val located = findSourceRoot()
        assumeTrue("Source root not found from ${File("").absolutePath}", located != null)
        val sourceRoot = located!!

        val violations = mutableListOf<String>()

        rules.forEach { rule ->
            val directory = File(sourceRoot, rule.packagePath)
            if (!directory.isDirectory) return@forEach

            directory.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file ->
                    rule.exceptPaths.any { file.invariantPath().contains("/$it/") }
                }
                .forEach { file ->
                    file.readLines()
                        .filter { it.startsWith("import ") }
                        .forEach { line ->
                            val imported = line.removePrefix("import ").trim()
                            rule.forbidden
                                .filter { imported.startsWith(it) }
                                .forEach { violations += "${rule.packagePath}/${file.name}: $imported" }
                        }
                }
        }

        assertTrue(
            "Package dependency rules violated:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    /** Gradle runs unit tests with the module directory as the working directory. */
    private fun findSourceRoot(): File? = listOf(
        "src/main/java/com/brigade",
        "app/src/main/java/com/brigade",
    ).map(::File).firstOrNull { it.isDirectory }
}
