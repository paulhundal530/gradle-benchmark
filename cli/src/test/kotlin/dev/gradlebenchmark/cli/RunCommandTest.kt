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
    fun `naming a baseline produces a comparison`() {
        val result = run(FakeProfiler(), "--baseline-scenario", "baseline")

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(outputDir.resolve("comparison.json").exists()).isTrue()
        assertThat(result.stdout).contains("Compared against baseline")
        assertThat(result.stdout).contains("cc-enabled")
    }

    @Test
    fun `omitting a baseline produces measurements but no comparison`() {
        val result = run(FakeProfiler())

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(outputDir.resolve("run.json").exists()).isTrue()
        assertThat(outputDir.resolve("comparison.json").exists()).isFalse()
    }

    @Test
    fun `a difference the run cannot resolve is described as such`() {
        // The fake produces 2 measured iterations by default, which resolves almost nothing.
        val result = run(FakeProfiler(), "--baseline-scenario", "baseline")

        assertThat(result.stdout).contains("within what this experiment could resolve")
        assertThat(result.stderr).contains("only 2 measured iterations")
    }

    @Test
    fun `a run reports the conditions that produced its numbers`() {
        val result = run(FakeProfiler(), "--baseline-scenario", "baseline")

        assertThat(result.stdout).contains("measured")
    }

    @Test
    fun `a single scenario can be selected without inventing a group`() {
        val profiler = FakeProfiler(scenarioNames = listOf("assemble_incremental"))

        val result = run(profiler, "--scenario", "assemble_incremental")

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("assemble_incremental")
        assertThat(profiler.lastArguments.last()).isEqualTo("assemble_incremental")
    }

    @Test
    fun `several scenarios can be selected by repeating the option`() {
        val profiler = FakeProfiler(scenarioNames = listOf("one", "two"))

        val result = run(profiler, "--scenario", "one", "--scenario", "two")

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(profiler.lastArguments.takeLast(2)).containsExactly("one", "two")
    }
}
