package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.testing.test
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class RunCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val outputDir: Path get() = tempDir.resolve("out")

    private fun scenarioFile(): Path = tempDir.resolve("build.scenarios").also { it.writeText("baseline { }\n") }

    private fun run(profiler: FakeProfiler, vararg extra: String) = RunCommand(profilerFactoryOf(profiler)).test(
        listOf(
            "--scenario-file",
            scenarioFile().toString(),
            "--output-dir",
            outputDir.toString(),
        ) + extra,
    )

    @Test
    fun `a successful benchmark reports what it measured and where the result is`() {
        val result = run(FakeProfiler())

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("Benchmark completed")
        assertThat(result.stdout).contains("raw/benchmark.json")
    }

    @Test
    fun `the materialized raw result is written where the output says it is`() {
        run(FakeProfiler())

        assertThat(outputDir.resolve("raw/benchmark.json").exists()).isTrue()
    }

    @Test
    fun `a profiler failure exits as a benchmark error, not invalid input`() {
        val result = run(FakeProfiler(benchmarkExitCode = 1))

        assertThat(result.statusCode).isEqualTo(ExitCode.BENCHMARK_ERROR.code)
        assertThat(result.stderr).contains("failed to run the benchmark")
    }

    @Test
    fun `a benchmark with no measured iterations is an error rather than an empty result`() {
        val result = run(FakeProfiler(measuredIterations = 0))

        assertThat(result.statusCode).isEqualTo(ExitCode.BENCHMARK_ERROR.code)
        assertThat(result.stderr).contains("incomplete")
    }

    @Test
    fun `a failed run points at the profiler log rather than dumping it`() {
        val result = run(FakeProfiler(benchmarkExitCode = 1))

        assertThat(result.stderr).contains("Full Gradle Profiler log:")
        assertThat(result.stderr).contains("raw/profile.log")
        // Console output stays a summary; the log is where the detail lives.
        assertThat(result.stderr.lines()).hasSizeLessThan(12)
    }

    @Test
    fun `missing profiler output is a benchmark error`() {
        val result = run(FakeProfiler(writesReport = false))

        assertThat(result.statusCode).isEqualTo(ExitCode.BENCHMARK_ERROR.code)
    }

    @Test
    fun `an invalid baseline is rejected before the benchmark runs`() {
        val profiler = FakeProfiler()

        val result = run(profiler, "--baseline-scenario", "nope")

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        // Validation only ever asks the profiler to dump scenarios, never to benchmark.
        assertThat(profiler.lastArguments).contains("--dump-scenarios")
        assertThat(outputDir.resolve("raw").exists()).isFalse()
    }

    @Test
    fun `naming a baseline says plainly that comparison is not implemented yet`() {
        val result = run(FakeProfiler(), "--baseline-scenario", "baseline")

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stderr).contains("comparison arrives in a later milestone")
        assertThat(outputDir.resolve("comparison.json").exists()).isFalse()
        assertThat(outputDir.resolve("report.html").exists()).isFalse()
    }
}
