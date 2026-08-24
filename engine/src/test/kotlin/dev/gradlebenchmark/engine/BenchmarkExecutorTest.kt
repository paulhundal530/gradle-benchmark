package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BenchmarkExecutorTest {

    @TempDir
    lateinit var outputDir: Path

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/profiler-output/$name")).bufferedReader().readText()

    /** Writes the report a real profiler run would leave behind, then reports [exitCode]. */
    private fun profilerWriting(fixtureName: String?, exitCode: Int = 0) = object : GradleProfiler {
        var arguments: List<String> = emptyList()

        override fun invoke(arguments: List<String>): ProfilerInvocation {
            this.arguments = arguments
            val rawDir = Path.of(arguments[arguments.indexOf("--output-dir") + 1])
            rawDir.createDirectories()
            fixtureName?.let {
                rawDir.resolve(RawBenchmarkExtractor.HTML_FILE_NAME).writeText(fixture(it))
            }
            return ProfilerInvocation(exitCode, "", if (exitCode == 0) "" else "boom")
        }
    }

    private fun request() = BenchmarkRequest(
        scenarioFile = Path.of("benchmarks/build.scenarios"),
        outputDir = outputDir,
        projectDir = Path.of("."),
    )

    @Test
    fun `a successful run materializes benchmark json from the embedded model`() {
        val result = BenchmarkExecutor(profilerWriting("successful-benchmark.html")).execute(request())

        val completed = result as BenchmarkExecutionResult.Completed
        assertThat(completed.rawBenchmarkJson.exists()).isTrue()
        assertThat(completed.rawBenchmarkJson.readText()).contains("cc-enabled")
        assertThat(completed.benchmark.scenarios).hasSize(2)
    }

    @Test
    fun `raw output is preserved under a raw subdirectory`() {
        val result = BenchmarkExecutor(profilerWriting("successful-benchmark.html")).execute(request())

        val completed = result as BenchmarkExecutionResult.Completed
        assertThat(completed.rawDir).isEqualTo(outputDir.resolve(BenchmarkExecutor.RAW_DIRECTORY))
        assertThat(completed.rawDir.resolve("benchmark.html").exists()).isTrue()
    }

    @Test
    fun `a non zero exit fails even though the profiler still wrote a report`() {
        val result = BenchmarkExecutor(profilerWriting("successful-benchmark.html", exitCode = 1))
            .execute(request())

        assertThat(result).isInstanceOf(BenchmarkExecutionResult.Failed::class.java)
        assertThat((result as BenchmarkExecutionResult.Failed).summary)
            .contains("failed to run the benchmark")
    }

    @Test
    fun `a scenario with no measured iterations is an incomplete benchmark`() {
        val result = BenchmarkExecutor(profilerWriting("failed-scenario-benchmark.html"))
            .execute(request())

        val failed = result as BenchmarkExecutionResult.Failed
        assertThat(failed.summary).contains("incomplete")
        assertThat(failed.detail).contains("broken")
    }

    @Test
    fun `missing profiler output is a failure rather than an empty benchmark`() {
        val result = BenchmarkExecutor(profilerWriting(fixtureName = null)).execute(request())

        assertThat(result).isInstanceOf(BenchmarkExecutionResult.Failed::class.java)
    }

    @Test
    fun `the profiler is asked to benchmark into the raw directory`() {
        val profiler = profilerWriting("successful-benchmark.html")

        BenchmarkExecutor(profiler).execute(
            request().copy(scenarioGroup = "nightly"),
        )

        assertThat(profiler.arguments).contains("--benchmark")
        assertThat(profiler.arguments).containsSequence("--group", "nightly")
        assertThat(profiler.arguments).containsSequence(
            "--output-dir",
            outputDir.resolve(BenchmarkExecutor.RAW_DIRECTORY).toString(),
        )
    }

    @Test
    fun `an incomplete benchmark never writes benchmark json`() {
        BenchmarkExecutor(profilerWriting("failed-scenario-benchmark.html")).execute(request())

        val benchmarkJson = outputDir
            .resolve(BenchmarkExecutor.RAW_DIRECTORY)
            .resolve(RawBenchmarkExtractor.JSON_FILE_NAME)

        // Downstream milestones read this file; it must never exist for an invalid run.
        assertThat(benchmarkJson.exists()).isFalse()
    }
}
