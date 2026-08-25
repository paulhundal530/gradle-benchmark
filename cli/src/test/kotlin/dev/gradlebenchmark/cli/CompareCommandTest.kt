package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.testing.test
import dev.gradlebenchmark.core.BenchmarkRun
import dev.gradlebenchmark.core.ExecutionEnvironment
import dev.gradlebenchmark.core.MeasurementProtocol
import dev.gradlebenchmark.core.MeasurementResult
import dev.gradlebenchmark.core.Revision
import dev.gradlebenchmark.core.ScenarioRun
import dev.gradlebenchmark.core.Statistics
import dev.gradlebenchmark.core.WorkloadConfiguration
import dev.gradlebenchmark.core.WorkloadIdentity
import dev.gradlebenchmark.report.RunJsonWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The comparison this tool mainly exists for: the same scenario measured twice, on two
 * branches or commits.
 */
class CompareCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val steady = listOf(100.0, 101.0, 99.0, 100.0, 100.0, 101.0)

    private fun writeRun(
        name: String,
        values: List<Double> = steady,
        args: List<String> = emptyList(),
        revision: String? = null,
        scenarioName: String = "configuration",
    ): Path {
        val run = BenchmarkRun(
            toolVersion = "0.1.0",
            runId = name,
            timestamp = "2026-08-24T00:00:00Z",
            revision = revision?.let { Revision(commit = it) },
            executionEnvironment = ExecutionEnvironment(operatingSystem = "Mac OS X"),
            scenarios = listOf(
                ScenarioRun(
                    name = scenarioName,
                    workloadIdentityHash = "hash",
                    workloadIdentity = WorkloadIdentity(
                        name = scenarioName,
                        tasks = "assembleDebug",
                        args = args,
                    ),
                    workloadConfiguration = WorkloadConfiguration(gradleVersion = "9.3.1"),
                    measurementProtocol = MeasurementProtocol(3, values.size, "0.25.2"),
                    measurements = listOf(
                        MeasurementResult(
                            name = "total execution time",
                            unit = "ms",
                            statistics = Statistics.of(values),
                            values = values,
                            warmUpValues = listOf(140.0, 105.0, 101.0),
                        ),
                    ),
                ),
            ),
        )
        return tempDir.resolve("$name.json").also { it.writeText(RunJsonWriter.render(run)) }
    }

    private fun compare(baseline: Path, candidate: Path, vararg extra: String) = CompareCommand().test(
        listOf(
            "--baseline",
            baseline.toString(),
            "--candidate",
            candidate.toString(),
            "--output-dir",
            tempDir.resolve("out").toString(),
        ) + extra,
    )

    @Test
    fun `two runs of the same scenario are compared and written out`() {
        val result = compare(
            writeRun("before", revision = "a3f21c9"),
            writeRun("after", steady.map { it * 0.75 }, revision = "8b04e7d"),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(tempDir.resolve("out/comparison.json").exists()).isTrue()
        assertThat(result.stdout).contains("a3f21c9").contains("8b04e7d")
    }

    @Test
    fun `the difference is reported with the conditions that produced it`() {
        val result = compare(writeRun("before"), writeRun("after", steady.map { it * 0.75 }))

        assertThat(result.stdout).contains("-25.")
        assertThat(result.stdout).contains("warm-ups")
        assertThat(result.stdout).contains("measured")
    }

    @Test
    fun `no verdict is offered, only what was measured`() {
        val result = compare(writeRun("before"), writeRun("after", steady.map { it * 0.75 }))

        assertThat(result.output)
            .doesNotContain("REGRESSION")
            .doesNotContain("PASS")
            .doesNotContain("threshold")
    }

    @Test
    fun `the branch experiment reports the argument under test rather than refusing`() {
        val result = compare(
            writeRun("cc-disabled"),
            writeRun("cc-enabled", steady.map { it * 0.75 }, args = listOf("--configuration-cache")),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("differs in:")
        assertThat(result.stdout).contains("--configuration-cache")
    }

    @Test
    fun `comparing unlike work is allowed but marked as changing the work`() {
        val result = compare(
            writeRun("clean", scenarioName = "shared"),
            writeRun(
                "incremental",
                steady.map { it * 0.05 },
                scenarioName = "shared",
            ).also {
                // Rewrite the candidate so its task list differs.
                it.writeText(it.toFile().readText().replace("assembleDebug", "clean assembleDebug"))
            },
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("changes the work performed")
    }

    @Test
    fun `a difference within the resolvable interval is described as such`() {
        val result = compare(writeRun("before"), writeRun("after", steady.map { it * 1.002 }))

        assertThat(result.stdout).contains("within what this experiment could resolve")
        assertThat(result.stdout).contains("not separable from noise")
    }

    @Test
    fun `runs sharing no scenarios are an input error rather than an empty answer`() {
        val result = compare(
            writeRun("before", scenarioName = "one"),
            writeRun("after", scenarioName = "two"),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("No scenarios were shared")
    }

    @Test
    fun `a missing run file names the path it looked for`() {
        val result = compare(tempDir.resolve("absent.json"), writeRun("after"))

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("Run file does not exist")
    }

    @Test
    fun `a file that is not a run is rejected with an explanation`() {
        val notARun = tempDir.resolve("nonsense.json").also { it.writeText("{\"nope\":1}") }

        val result = compare(notARun, writeRun("after"))

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("Could not read")
    }

    @Test
    fun `comparing a run against itself reports no difference`() {
        val run = writeRun("same")

        val result = compare(run, run)

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("+0.0%")
    }

    @Test
    fun `runs from the same checkout do not name the revision twice`() {
        val result = compare(
            writeRun("before", revision = "a3f21c9"),
            writeRun("after", steady.map { it * 0.75 }, revision = "a3f21c9"),
        )

        assertThat(result.stdout).contains("Compared two runs at a3f21c9")
        assertThat(result.stdout).doesNotContain("a3f21c9 against a3f21c9")
    }
}
