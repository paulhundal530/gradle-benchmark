package dev.gradlebenchmark.engine

import java.nio.file.Path

/**
 * Asks Gradle Profiler which scenarios a selection resolves to.
 *
 * Uses `--dump-scenarios`, which resolves and validates the scenario definition without
 * invoking Gradle. `--dry-run` also validates, but it executes real builds and writes
 * benchmark artifacts, making it roughly seventy times slower on a trivial project. That
 * cost belongs to proving a scenario can *execute*, not to checking that it is well
 * formed.
 *
 * Reusing the profiler's own resolution also keeps grouping semantics in one place rather
 * than reimplementing the scenario DSL.
 */
public class ScenarioInspector(private val profiler: GradleProfiler) {

    public fun inspect(
        scenarioFile: Path,
        group: String? = null,
        projectDir: Path? = null,
        scenarioNames: List<String> = emptyList(),
    ): InspectionResult {
        val arguments = buildList {
            add("--benchmark")
            add("--dump-scenarios")
            add("--scenario-file")
            add(scenarioFile.toString())
            if (group != null) {
                add("--group")
                add(group)
            }
            if (projectDir != null) {
                add("--project-dir")
                add(projectDir.toString())
            }
            // Scenario names are non-option arguments, so they go last. Gradle Profiler
            // rejects combining them with --group itself, and its message is clearer than
            // one we would write, so it is passed through rather than pre-empted.
            addAll(scenarioNames)
        }

        val invocation = profiler.invoke(arguments)
        if (!invocation.succeeded) {
            return InspectionResult.Rejected(extractProfilerMessage(invocation.combinedOutput))
        }

        val names = parseScenarioNames(invocation.stdout)
        return if (names.isEmpty()) {
            InspectionResult.Rejected(
                "Gradle Profiler resolved no scenarios from $scenarioFile" +
                    (group?.let { " in group '$it'" } ?: "") + ".",
            )
        } else {
            InspectionResult.Resolved(names)
        }
    }

    public companion object {
        private val BLOCK_KEY = Regex("""^(\S+) \{$""")
        private val STACK_FRAME = Regex("""^\s+at \S""")
        private val THROWABLE_PREFIX = Regex("""^[\w.$]+(Exception|Error)[\w.$]*:\s*""")

        /**
         * Scenario names are the top-level block keys of the dump.
         *
         * Nested configuration is indented, so anchoring to column zero avoids mistaking
         * a nested block for a scenario.
         */
        internal fun parseScenarioNames(dump: String): List<String> = dump.lineSequence()
            .mapNotNull { BLOCK_KEY.find(it)?.groupValues?.get(1) }
            .toList()

        /**
         * Pulls the useful sentence out of a profiler failure.
         *
         * The profiler reports errors as an uncaught Java exception, so the message is
         * genuinely actionable ("Available groups are: nightly") but arrives wrapped in a
         * class name and followed by a stack trace that helps nobody.
         */
        /**
         * Summarizes a *benchmark* failure, as opposed to a validation failure.
         *
         * A failed benchmark's output is dominated by progress logging, so taking
         * everything before the first stack frame would print thirty lines of settings
         * dump. Only lines that actually describe the failure are kept, and the profiler's
         * own log is where someone should go for the rest.
         */
        internal fun summarizeBenchmarkFailure(output: String, maxLines: Int = 4): String {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty()) return "Gradle Profiler failed without reporting a reason."

            val salient = lines.filter { line ->
                line.startsWith("ERROR") ||
                    line.startsWith("Caused by:") ||
                    THROWABLE_PREFIX.containsMatchIn(line)
            }

            val chosen = (if (salient.isNotEmpty()) salient else lines).takeLast(maxLines)
            return chosen.joinToString("\n") { it.replaceFirst(THROWABLE_PREFIX, "") }
        }

        internal fun extractProfilerMessage(output: String): String {
            val meaningful = output.lineSequence()
                .takeWhile { !STACK_FRAME.containsMatchIn(it) }
                .filter { it.isNotBlank() }
                .toList()

            if (meaningful.isEmpty()) return "Gradle Profiler failed without reporting a reason."

            return meaningful
                .joinToString("\n")
                .replaceFirst(THROWABLE_PREFIX, "")
                .trim()
        }
    }
}

/** What Gradle Profiler made of a requested selection. */
public sealed interface InspectionResult {
    public data class Resolved(val scenarioNames: List<String>) : InspectionResult

    public data class Rejected(val reason: String) : InspectionResult
}
