package dev.gradlebenchmark.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Locates the scenario file a run should use.
 *
 * Gradle Profiler accepts exactly one `--scenario-file`, so directory discovery is the
 * CLI's job: it finds the candidates, resolves the user's selection, and passes a single
 * file down.
 */
public object ScenarioDiscovery {

    public const val SCENARIO_EXTENSION: String = ".scenarios"

    /** Scenario files directly inside [scenarioDir], sorted for deterministic output. */
    public fun findScenarioFiles(scenarioDir: Path): List<Path> {
        if (!scenarioDir.isDirectory()) return emptyList()
        return Files.list(scenarioDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(SCENARIO_EXTENSION) }
                .sorted()
                .toList()
        }
    }

    /**
     * Resolves [scenarioDir] and [scenarioFile] into the single file to benchmark.
     *
     * Precedence, with every failure naming what was inspected:
     *
     * - both given: the file is resolved against the directory when it is relative
     * - file only: used as given
     * - directory only: unambiguous when it holds exactly one scenario file
     * - neither: an error, because there is nothing to select from
     */
    public fun selectScenarioFile(scenarioDir: Path?, scenarioFile: Path?): ScenarioFileSelection {
        if (scenarioDir == null && scenarioFile == null) {
            return ScenarioFileSelection.Failed(
                ValidationProblem(
                    "No scenario file was selected.",
                    "Pass --scenario-file, or --scenario-dir to discover one.",
                ),
            )
        }

        if (scenarioDir != null && !scenarioDir.isDirectory()) {
            return ScenarioFileSelection.Failed(
                ValidationProblem(
                    "Scenario directory does not exist: $scenarioDir",
                    "Checked ${scenarioDir.toAbsolutePath()}",
                ),
            )
        }

        if (scenarioFile != null) {
            val resolved = when {
                scenarioDir == null -> scenarioFile
                scenarioFile.isAbsolute -> scenarioFile
                else -> scenarioDir.resolve(scenarioFile)
            }
            return if (resolved.isRegularFile()) {
                ScenarioFileSelection.Selected(resolved)
            } else {
                ScenarioFileSelection.Failed(missingScenarioFile(resolved, scenarioDir))
            }
        }

        val candidates = findScenarioFiles(scenarioDir!!)
        return when (candidates.size) {
            0 -> ScenarioFileSelection.Failed(
                ValidationProblem(
                    "No $SCENARIO_EXTENSION files found in $scenarioDir",
                    "Checked ${scenarioDir.toAbsolutePath()}",
                ),
            )

            1 -> ScenarioFileSelection.Selected(candidates.single())

            else -> ScenarioFileSelection.Failed(
                ValidationProblem(
                    "Multiple $SCENARIO_EXTENSION files found in $scenarioDir; " +
                        "pass --scenario-file to choose one.",
                    "Found: " + candidates.joinToString(", ") { it.name },
                ),
            )
        }
    }

    private fun missingScenarioFile(resolved: Path, scenarioDir: Path?): ValidationProblem {
        val available = scenarioDir?.let { findScenarioFiles(it) }.orEmpty()
        val detail = when {
            available.isEmpty() -> "Checked ${resolved.toAbsolutePath()}"
            else -> "Available in $scenarioDir: " + available.joinToString(", ") { it.name }
        }
        return ValidationProblem("Scenario file does not exist: $resolved", detail)
    }
}

/** Result of resolving a scenario file from the user's directory and file options. */
public sealed interface ScenarioFileSelection {
    public data class Selected(val scenarioFile: Path) : ScenarioFileSelection

    public data class Failed(val problem: ValidationProblem) : ScenarioFileSelection
}
