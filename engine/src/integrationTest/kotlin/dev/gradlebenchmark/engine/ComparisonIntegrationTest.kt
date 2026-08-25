package dev.gradlebenchmark.engine

import dev.gradlebenchmark.core.ComparisonEngine
import dev.gradlebenchmark.core.ComparisonStatus
import dev.gradlebenchmark.core.Direction
import dev.gradlebenchmark.core.ScenarioStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Compares real measurements from real builds.
 *
 * The fixture's `slow` scenario is deliberately about 250ms slower than `fast`, which is
 * far beyond anything machine noise produces, so this asserts a resolvable difference
 * rather than hoping one appears.
 */
class ComparisonIntegrationTest {

    @TempDir
    lateinit var outputDir: Path

    private val fixtureDir: Path = Path.of(
        System.getProperty("gradleBenchmark.fixtureDir")
            ?: error("gradleBenchmark.fixtureDir system property is not set"),
    )

    private fun comparisonRun() = BenchmarkRunAssembler().assemble(
        benchmark = (
            BenchmarkExecutor(ProcessGradleProfiler()).execute(
                BenchmarkRequest(
                    scenarioFile = fixtureDir.resolve("benchmarks/comparison.scenarios"),
                    outputDir = outputDir,
                    projectDir = fixtureDir,
                ),
            ) as BenchmarkExecutionResult.Completed
            ).benchmark,
        runId = "integration",
        timestamp = "2026-01-01T00:00:00Z",
        toolVersion = "test",
        projectDir = fixtureDir,
    )

    @Test
    fun `a real difference well beyond the noise floor is resolved and reported`() {
        val comparison = ComparisonEngine.compareVariants(comparisonRun(), baselineScenario = "fast")

        val slow = comparison.scenarios.single { it.name == "slow" }

        assertThat(slow.observation.distinguishable)
            .describedAs("A 250ms difference must be resolvable on any machine")
            .isTrue()
        assertThat(slow.observation.direction).isEqualTo(Direction.SLOWER)
        assertThat(slow.observation.deltaPercent).isGreaterThan(100.0)
        assertThat(slow.verdict.status).isEqualTo(ScenarioStatus.REGRESSION)
        assertThat(comparison.overallComparisonStatus).isEqualTo(ComparisonStatus.REGRESSION_PRESENT)
    }

    @Test
    fun `resolution is trustworthy once the scenario declares enough iterations`() {
        val comparison = ComparisonEngine.compareVariants(comparisonRun(), baselineScenario = "fast")

        // The fixture declares six iterations, past the point where noise can be estimated.
        assertThat(comparison.scenarios.single().observation.resolutionReliable).isTrue()
        assertThat(comparison.diagnostics)
            .noneSatisfy { assertThat(it).contains("measured iterations") }
    }

    @Test
    fun `comparing a real run against itself never invents a difference`() {
        val run = comparisonRun()

        // An A/A comparison: the same measurements on both sides must never look changed.
        val self = dev.gradlebenchmark.core.Observation.of(
            baseline = run.scenarios.first().measurements.single().values,
            candidate = run.scenarios.first().measurements.single().values,
            unit = "ms",
        )

        assertThat(self.deltaPercent).isEqualTo(0.0)
        assertThat(self.distinguishable).isFalse()
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
