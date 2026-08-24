package dev.gradlebenchmark.engine

import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Runs a benchmark and verifies it actually produced measurements.
 *
 * An invalid or incomplete benchmark must abort the workflow rather than flow into a
 * comparison, so every check here is a hard failure. There is deliberately no option to
 * treat a failed benchmark as passable; that lever exists only for regressions.
 */
public class BenchmarkExecutor(private val profiler: GradleProfiler) {

    public fun execute(request: BenchmarkRequest): BenchmarkExecutionResult {
        val rawDir = request.outputDir.resolve(RAW_DIRECTORY)
        rawDir.createDirectories()

        val invocation = profiler.invoke(argumentsFor(request, rawDir))

        // The profiler writes benchmark.html and benchmark.csv even when a scenario fails,
        // so artifact presence proves nothing and the exit code has to gate first.
        if (!invocation.succeeded) {
            return BenchmarkExecutionResult.Failed(
                summary = "Gradle Profiler failed to run the benchmark.",
                detail = ScenarioInspector.summarizeBenchmarkFailure(invocation.combinedOutput),
                rawDir = rawDir,
                logFile = rawDir.resolve(PROFILER_LOG),
            )
        }

        val benchmark = when (val extraction = RawBenchmarkExtractor.extractFrom(rawDir)) {
            is ExtractionResult.Failed -> return BenchmarkExecutionResult.Failed(
                summary = extraction.summary,
                detail = extraction.detail,
                rawDir = rawDir,
                logFile = rawDir.resolve(PROFILER_LOG),
            )

            is ExtractionResult.Extracted -> extraction.benchmark
        }

        incompleteScenarioProblem(benchmark)?.let { problem ->
            return BenchmarkExecutionResult.Failed(
                problem.first,
                problem.second,
                rawDir,
                rawDir.resolve(PROFILER_LOG),
            )
        }

        // Materialize the model the profiler only embeds in its HTML report, so downstream
        // milestones and anyone debugging have a real file to read.
        val benchmarkJson = rawDir.resolve(RawBenchmarkExtractor.JSON_FILE_NAME)
        benchmarkJson.writeText(RawBenchmarkExtractor.prettyJson.encodeToString(benchmark))

        return BenchmarkExecutionResult.Completed(
            benchmark = benchmark,
            rawDir = rawDir,
            rawBenchmarkJson = benchmarkJson,
        )
    }

    private fun argumentsFor(request: BenchmarkRequest, rawDir: Path): List<String> = buildList {
        add("--benchmark")
        add("--output-dir")
        add(rawDir.toString())
        // Gradle Profiler defaults this to "gradle-user-home" in the working directory,
        // which drops hundreds of megabytes of Gradle distributions into whatever project
        // is being benchmarked. Keeping it under the output directory means everything the
        // tool creates lives in one place the user already chose. It stays an isolated
        // home either way, which benchmark hygiene depends on.
        add("--gradle-user-home")
        add(request.gradleUserHome?.toString() ?: defaultGradleUserHome(request).toString())
        add("--scenario-file")
        add(request.scenarioFile.toString())
        request.scenarioGroup?.let {
            add("--group")
            add(it)
        }
        request.projectDir?.let {
            add("--project-dir")
            add(it.toString())
        }
    }

    /**
     * A scenario with no measured iterations produced no data.
     *
     * Released Gradle Profiler computes no statistics, so this counts MEASURE-phase
     * iterations rather than looking for a `stats` block. Warm-ups do not count: a run that
     * warmed up and then failed has measured nothing.
     */
    private fun incompleteScenarioProblem(benchmark: RawBenchmark): Pair<String, String>? {
        if (benchmark.scenarios.isEmpty()) {
            return "The benchmark produced no scenarios." to
                "Gradle Profiler reported success but its result contains no scenarios."
        }

        val incomplete = benchmark.scenarios.filter { it.measuredIterations.isEmpty() }
        if (incomplete.isEmpty()) return null

        val names = incomplete.joinToString(", ") { it.definition.name.ifEmpty { "<unnamed>" } }
        return "The benchmark is incomplete and cannot be interpreted." to
            "No measured iterations for: $names. " +
            "A scenario that warmed up but never completed a measured build has produced " +
            "no data, so comparing it would be meaningless."
    }

    private fun defaultGradleUserHome(request: BenchmarkRequest): Path =
        request.outputDir.resolve(GRADLE_USER_HOME_DIRECTORY)

    public companion object {
        public const val RAW_DIRECTORY: String = "raw"

        /**
         * Where Gradle distributions and caches land when the user does not choose.
         *
         * Sits beside `raw/` rather than inside it, because it is a working directory
         * rather than a result worth preserving.
         */
        public const val GRADLE_USER_HOME_DIRECTORY: String = "gradle-user-home"

        /** Gradle Profiler's own log, which holds the full build failure. */
        public const val PROFILER_LOG: String = "profile.log"
    }
}

/** What to benchmark and where to put the results. */
public data class BenchmarkRequest(
    val scenarioFile: Path,
    val outputDir: Path,
    val scenarioGroup: String? = null,
    val projectDir: Path? = null,
    /**
     * Gradle user home for the builds under measurement.
     *
     * Defaults to a directory under [outputDir]. Point it somewhere stable to avoid
     * re-downloading Gradle on every run.
     */
    val gradleUserHome: Path? = null,
)

/** Outcome of running a benchmark. */
public sealed interface BenchmarkExecutionResult {
    public data class Completed(val benchmark: RawBenchmark, val rawDir: Path, val rawBenchmarkJson: Path) :
        BenchmarkExecutionResult

    public data class Failed(
        val summary: String,
        val detail: String?,
        val rawDir: Path,
        /** Gradle Profiler's log, which carries the full build failure. */
        val logFile: Path,
    ) : BenchmarkExecutionResult
}
