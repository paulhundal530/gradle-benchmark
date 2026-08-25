package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Assembles a normalized run from a real benchmark, so the model is exercised against what
 * Gradle Profiler actually produces rather than against hand-built input.
 */
class BenchmarkRunIntegrationTest {

    @TempDir
    lateinit var outputDir: Path

    private val fixtureDir: Path = Path.of(
        System.getProperty("gradleBenchmark.fixtureDir")
            ?: error("gradleBenchmark.fixtureDir system property is not set"),
    )

    private fun completedRun(): BenchmarkExecutionResult.Completed {
        val result = BenchmarkExecutor(ProcessGradleProfiler()).execute(
            BenchmarkRequest(
                scenarioFile = fixtureDir.resolve("benchmarks/build.scenarios"),
                outputDir = outputDir,
                projectDir = fixtureDir,
            ),
        )
        return result as BenchmarkExecutionResult.Completed
    }

    @Test
    fun `a real benchmark assembles into a normalized run with computed statistics`() {
        val run = BenchmarkRunAssembler().assemble(
            benchmark = completedRun().benchmark,
            runId = "integration",
            timestamp = "2026-01-01T00:00:00Z",
            toolVersion = "test",
            projectDir = fixtureDir,
        )

        assertThat(run.schemaVersion).isEqualTo(1)
        assertThat(run.scenarios.map { it.name }).containsExactlyInAnyOrder("baseline", "cc-enabled")

        run.scenarios.forEach { scenario ->
            val measurement = scenario.measurements.single()

            assertThat(measurement.unit).isEqualTo("ms")
            // Released Gradle Profiler computes no statistics, so these came from us.
            assertThat(measurement.statistics.median).isGreaterThan(0.0)
            assertThat(measurement.statistics.min)
                .isLessThanOrEqualTo(measurement.statistics.median)
            assertThat(measurement.statistics.max)
                .isGreaterThanOrEqualTo(measurement.statistics.median)
            assertThat(measurement.values)
                .hasSize(scenario.measurementProtocol.measuredIterationCount)
        }
    }

    @Test
    fun `warm-ups are excluded from the values that statistics are computed over`() {
        val completed = completedRun()
        val run = BenchmarkRunAssembler().assemble(
            benchmark = completed.benchmark,
            runId = "integration",
            timestamp = "2026-01-01T00:00:00Z",
            toolVersion = "test",
            projectDir = fixtureDir,
        )

        val scenario = run.scenarios.first { it.name == "baseline" }
        val raw = completed.benchmark.scenarios.first { it.definition.name == "baseline" }

        // The fixture declares warm-ups = 1, iterations = 2.
        assertThat(scenario.measurementProtocol.warmUpCount).isEqualTo(1)
        assertThat(scenario.measurementProtocol.measuredIterationCount).isEqualTo(2)
        assertThat(scenario.measurements.single().values)
            .hasSize(2)
            .doesNotContainAnyElementsOf(
                raw.warmUpIterations.mapNotNull { it.values["total execution time"] },
            )
    }

    @Test
    fun `the real build JVM version is resolved from the profiler's javaHome path`() {
        val run = BenchmarkRunAssembler().assemble(
            benchmark = completedRun().benchmark,
            runId = "integration",
            timestamp = "2026-01-01T00:00:00Z",
            toolVersion = "test",
            projectDir = fixtureDir,
        )

        assertThat(run.scenarios.first().workloadConfiguration.buildJvmVersion)
            .describedAs("Resolved by executing the JDK, since the profiler reports only a path")
            .isNotBlank()
    }

    @Test
    fun `the configuration-cache variant differs in identity from the baseline`() {
        val run = BenchmarkRunAssembler().assemble(
            benchmark = completedRun().benchmark,
            runId = "integration",
            timestamp = "2026-01-01T00:00:00Z",
            toolVersion = "test",
            projectDir = fixtureDir,
        )

        val baseline = run.scenarios.first { it.name == "baseline" }
        val ccEnabled = run.scenarios.first { it.name == "cc-enabled" }

        // They differ by a Gradle argument, which is treated as identity.
        assertThat(ccEnabled.workloadIdentity.args).contains("--configuration-cache")
        assertThat(baseline.workloadIdentityHash).isNotEqualTo(ccEnabled.workloadIdentityHash)
    }

    @Test
    fun `no absolute path from this machine reaches the workload identity`() {
        val run = BenchmarkRunAssembler().assemble(
            benchmark = completedRun().benchmark,
            runId = "integration",
            timestamp = "2026-01-01T00:00:00Z",
            toolVersion = "test",
            projectDir = fixtureDir,
        )

        val identityText = run.scenarios.joinToString(" ") { it.workloadIdentity.toString() }

        // Anything machine-specific here makes every cross-machine comparison incompatible.
        assertThat(identityText).doesNotContain(fixtureDir.toAbsolutePath().toString())
        assertThat(identityText).doesNotContain(System.getProperty("user.home"))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireProfiler() {
            val fixture = System.getProperty("gradleBenchmark.fixtureDir")?.let(Path::of)
            val fixturePresent = fixture != null && fixture.isDirectory()
            val profilerPresent = runCatching {
                ProcessGradleProfiler().invoke(listOf("--version")).succeeded
            }.getOrDefault(false)

            if (System.getenv("CI").toBoolean()) {
                check(fixturePresent) { "Fixture project is missing at $fixture" }
                check(profilerPresent) { "gradle-profiler is not on PATH; CI must install it." }
            } else {
                assumeTrue(fixturePresent, "fixture project is missing")
                assumeTrue(profilerPresent, "gradle-profiler is not on PATH")
            }
        }
    }
}
