package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.rendering.AnsiLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Records the CLI's entire public surface as reviewable text under `docs/cli-surface/`.
 *
 * These snapshots are the artifact a reviewer reads to judge whether the command line
 * meets its specification. Because the CLI is the product, its usage text is public
 * behavior, and a change to it should show up as a reviewable diff rather than being
 * discovered by a user.
 *
 * Regenerate after an intentional change:
 *
 * ```
 * ./gradlew :cli:test -PupdateCliSurface
 * ```
 */
class CliSurfaceSnapshotTest {

    /** Pinned so recorded text does not shift with the width of the terminal running CI. */
    private val renderWidth = 90

    private val repoRoot: Path
        get() = Path.of(
            System.getProperty("gradleBenchmark.repoRoot")
                ?: error("gradleBenchmark.repoRoot system property is not set"),
        )

    private val snapshotDir: Path get() = repoRoot.resolve("docs/cli-surface")

    private val updating: Boolean
        get() = System.getProperty("gradleBenchmark.updateCliSurface").toBoolean()

    private val surfaces = listOf(
        "gradle-benchmark" to listOf("--help"),
        "gradle-benchmark-validate" to listOf("validate", "--help"),
        "gradle-benchmark-run" to listOf("run", "--help"),
        "gradle-benchmark-compare" to listOf("compare", "--help"),
        "gradle-benchmark-help" to listOf("help", "--help"),
    )

    @TestFactory
    fun `documented CLI surface matches the recorded snapshots`(): List<DynamicTest> = surfaces.map { (name, argv) ->
        DynamicTest.dynamicTest(name) {
            val rendered = render(argv)
            val snapshot = snapshotDir.resolve("$name.txt")

            if (updating) {
                snapshotDir.createDirectories()
                snapshot.writeText(rendered)
                return@dynamicTest
            }

            assertThat(Files.exists(snapshot))
                .describedAs(
                    "Missing CLI surface snapshot %s. Regenerate with " +
                        "./gradlew :cli:test -PupdateCliSurface",
                    snapshot,
                )
                .isTrue()

            assertThat(rendered)
                .describedAs(
                    "The CLI's public surface changed. If intended, regenerate with " +
                        "./gradlew :cli:test -PupdateCliSurface and review the diff.",
                )
                .isEqualTo(snapshot.readText())
        }
    }

    private fun render(argv: List<String>): String = buildCommandTree()
        .test(argv, width = renderWidth, ansiLevel = AnsiLevel.NONE)
        .output
}
