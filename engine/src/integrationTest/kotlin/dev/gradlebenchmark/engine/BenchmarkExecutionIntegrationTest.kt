package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Runs real benchmarks with a real `gradle-profiler` against a real Gradle build.
 *
 * The unit tests parse captured reports, which is fast but blind to the profiler changing.
 * These tests are what would notice.
 */
class BenchmarkExecutionIntegrationTest {

    @TempDir
    lateinit var outputDir: Path

    private val fixtureDir: Path = Path.of(
        System.getProperty("gradleBenchmark.fixtureDir")
            ?: error("gradleBenchmark.fixtureDir system property is not set"),
    )

    private fun executor() = BenchmarkExecutor(ProcessGradleProfiler())

    private fun request(scenarioFile: String) = BenchmarkRequest(
        scenarioFile = fixtureDir.resolve("benchmarks/$scenarioFile"),
        outputDir = outputDir,
        projectDir = fixtureDir,
    )

    @Test
    fun `a real benchmark produces measurements and a materialized benchmark json`() {
        val result = executor().execute(request("build.scenarios"))

        val completed = result as BenchmarkExecutionResult.Completed
        assertThat(completed.rawBenchmarkJson.exists()).isTrue()
        assertThat(completed.benchmark.scenarios.map { it.definition.name })
            .containsExactlyInAnyOrder("baseline", "cc-enabled")
        assertThat(completed.benchmark.scenarios)
            .allSatisfy { assertThat(it.measuredIterations).isNotEmpty() }
    }

    @Test
    fun `the profiler's own raw output is preserved for debugging`() {
        val result = executor().execute(request("build.scenarios"))

        val rawDir = (result as BenchmarkExecutionResult.Completed).rawDir
        assertThat(rawDir.resolve("benchmark.html").exists()).isTrue()
        assertThat(rawDir.resolve("benchmark.csv").exists()).isTrue()
    }

    @Test
    fun `measured iterations exclude warm ups`() {
        val result = executor().execute(request("build.scenarios"))

        val baseline = (result as BenchmarkExecutionResult.Completed)
            .benchmark.scenarios.first { it.definition.name == "baseline" }

        // The fixture declares warm-ups = 1, iterations = 2.
        assertThat(baseline.warmUpIterations).hasSize(1)
        assertThat(baseline.measuredIterations).hasSize(2)
    }

    @Test
    fun `released gradle-profiler still writes no benchmark json of its own`() {
        val result = executor().execute(request("build.scenarios"))
        val completed = result as BenchmarkExecutionResult.Completed

        // Guards the reason this project extracts the model from the HTML report. If a
        // future profiler writes the file itself, this fails and the workaround can go.
        assertThat(completed.benchmark.environment.profilerVersion).isEqualTo("0.25.2")
    }

    @Test
    fun `a scenario whose build fails aborts instead of yielding an empty benchmark`() {
        val result = executor().execute(request("broken.scenarios"))

        val failed = result as BenchmarkExecutionResult.Failed
        assertThat(failed.rawDir.exists()).isTrue()
        assertThat(failed.detail).doesNotContain("at org.gradle.profiler")
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
