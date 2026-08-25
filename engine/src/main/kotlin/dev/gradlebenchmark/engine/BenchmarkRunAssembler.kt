package dev.gradlebenchmark.engine

import dev.gradlebenchmark.core.BenchmarkRun
import dev.gradlebenchmark.core.ExecutionEnvironment
import dev.gradlebenchmark.core.MeasurementProtocol
import dev.gradlebenchmark.core.MeasurementResult
import dev.gradlebenchmark.core.MeasurementUnits
import dev.gradlebenchmark.core.Revision
import dev.gradlebenchmark.core.ScenarioRun
import dev.gradlebenchmark.core.Statistics
import dev.gradlebenchmark.core.WorkloadConfiguration
import dev.gradlebenchmark.core.WorkloadIdentity
import java.nio.file.Path

/**
 * Turns Gradle Profiler's raw result into the normalized [BenchmarkRun].
 *
 * Statistics are computed here rather than read: released Gradle Profiler emits none. The
 * definitions match the ones its HTML report uses, so a user comparing our `run.json`
 * against the report it sits beside sees the same numbers.
 *
 * [runId] and [timestamp] are supplied rather than generated, so the same input produces
 * byte-identical output and golden-file tests are meaningful.
 */
public class BenchmarkRunAssembler(
    private val javaVersionResolver: JavaVersionResolver = ProcessJavaVersionResolver(),
    private val environment: ExecutionEnvironment = detectExecutionEnvironment(),
) {

    public fun assemble(
        benchmark: RawBenchmark,
        runId: String,
        timestamp: String,
        toolVersion: String,
        projectDir: Path? = null,
        revision: Revision? = null,
    ): BenchmarkRun = BenchmarkRun(
        toolVersion = toolVersion,
        runId = runId,
        timestamp = timestamp,
        revision = revision,
        executionEnvironment = environment,
        scenarios = benchmark.scenarios.map { scenario ->
            toScenarioRun(scenario, benchmark, projectDir)
        },
    )

    private fun toScenarioRun(scenario: RawScenario, benchmark: RawBenchmark, projectDir: Path?): ScenarioRun {
        val definition = scenario.definition

        val identity = WorkloadIdentity(
            name = definition.name,
            tasks = definition.tasks,
            action = definition.action,
            cleanup = definition.cleanup,
            invoker = definition.invoker,
            // Mutator descriptions embed absolute paths. Left raw, the same benchmark would
            // fingerprint differently on a laptop and on CI.
            mutators = definition.mutators.map { WorkloadIdentity.normalizeMutator(it, projectDir) },
            args = definition.args,
            jvmArgs = definition.jvmArgs,
            systemProperties = definition.systemProperties,
        )

        return ScenarioRun(
            name = definition.name,
            title = definition.title,
            workloadIdentityHash = identity.hash(),
            workloadIdentity = identity,
            workloadConfiguration = WorkloadConfiguration(
                gradleVersion = definition.version,
                // The profiler reports javaHome as a path and never a version, so the JDK
                // is asked directly.
                buildJvmVersion = definition.javaHome?.let(javaVersionResolver::versionOf),
                usesScanPlugin = definition.usesScanPlugin,
            ),
            measurementProtocol = MeasurementProtocol(
                warmUpCount = scenario.warmUpIterations.size,
                measuredIterationCount = scenario.measuredIterations.size,
                profilerVersion = benchmark.environment.profilerVersion,
            ),
            measurements = scenario.samples.mapNotNull { sample ->
                toMeasurement(sample, scenario)
            },
        )
    }

    private fun toMeasurement(sample: RawSample, scenario: RawScenario): MeasurementResult? {
        // Warm-ups are excluded: including them would inflate the mean several times over.
        val raw = scenario.measuredIterations.mapNotNull { it.values[sample.name] }
        if (raw.isEmpty()) return null

        val normalized = MeasurementUnits.normalize(raw, sample.unit)
        val warmUps = scenario.warmUpIterations.mapNotNull { it.values[sample.name] }
        val normalizedWarmUps = MeasurementUnits.normalize(warmUps, sample.unit)

        return MeasurementResult(
            name = sample.name,
            unit = normalized.unit,
            statistics = Statistics.of(normalized.values),
            values = normalized.values,
            warmUpValues = normalizedWarmUps.values,
        )
    }
}

/** Resolves the version of a JDK given its home directory. */
public interface JavaVersionResolver {
    public fun versionOf(javaHome: String): String?
}

/**
 * Asks a JDK for its version by running it.
 *
 * `java -version` writes to stderr, and the format varies by vendor, so the version is
 * pulled from the first quoted token rather than by position.
 */
public class ProcessJavaVersionResolver : JavaVersionResolver {

    override fun versionOf(javaHome: String): String? = runCatching {
        val java = Path.of(javaHome, "bin", "java")
        val process = ProcessBuilder(java.toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        parseVersion(output)
    }.getOrNull()

    public companion object {
        private val QUOTED_VERSION = Regex("\"([^\"]+)\"")

        /**
         * Extracts the version from `java -version` output.
         *
         * Handles the vendor variations:
         * ```
         * openjdk version "21.0.11" 2026-04-21 LTS
         * java version "1.8.0_402"
         * ```
         */
        internal fun parseVersion(output: String): String? = QUOTED_VERSION.find(output)?.groupValues?.get(1)
    }
}

/**
 * Detects the machine the benchmark ran on.
 *
 * A plain function rather than an extension on a companion: `@Serializable` happens to
 * generate one, so an extension there would compile today and break confusingly the moment
 * the annotation moved.
 */
public fun detectExecutionEnvironment(): ExecutionEnvironment = ExecutionEnvironment(
    operatingSystem = System.getProperty("os.name"),
    architecture = System.getProperty("os.arch"),
    cpuCores = Runtime.getRuntime().availableProcessors(),
    maxMemoryBytes = Runtime.getRuntime().maxMemory(),
)
