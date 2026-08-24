package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.parse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Covers option parsing and defaulting.
 *
 * `run` validates its selection and then benchmarks, so these tests supply a resolvable
 * selection and a fake profiler that produces a report. Otherwise every case would fail on
 * execution rather than on the option under test.
 */
class CommandParsingTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scenarioFile(): Path = tempDir.resolve("build.scenarios").also { it.writeText("baseline { }\n") }

    private fun runCommand() = RunCommand(profilerFactoryOf(FakeProfiler()))

    /** Minimal arguments that resolve and benchmark, so option defaults can be observed. */
    private fun selecting(vararg extra: String): Array<String> = arrayOf(
        "--scenario-file",
        scenarioFile().toString(),
        "--output-dir",
        tempDir.resolve("out").toString(),
    ) + extra

    @Test
    fun `run parses scenario selection and threshold`() {
        val command = runCommand()

        command.parse(
            selecting(
                "--scenario-group",
                "configuration-cache",
                "--baseline-scenario",
                "baseline",
                "--regression-threshold-percent",
                "7.5",
            ),
        )

        assertThat(command.scenarioGroup).isEqualTo("configuration-cache")
        assertThat(command.baselineScenario).isEqualTo("baseline")
        assertThat(command.regressionThresholdPercent).isEqualTo(7.5)
    }

    @Test
    fun `run defaults the threshold to five percent`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.regressionThresholdPercent).isEqualTo(DEFAULT_THRESHOLD_PERCENT)
    }

    @Test
    fun `run leaves the baseline unset so no comparison is implied`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.baselineScenario).isNull()
    }

    @Test
    fun `run is variant mode and so does not enforce by default`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.mode).isEqualTo(ComparisonMode.VARIANT)
        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isFalse()
    }

    @Test
    fun `run enforcement can be switched on explicitly`() {
        val command = runCommand()

        command.parse(selecting("--fail-on-regression"))

        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isTrue()
    }

    @Test
    fun `timeout is unbounded unless requested`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.timeoutMinutes).isNull()
    }

    @Test
    fun `the profiler executable defaults to the one on PATH`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.gradleProfilerExecutable).isEqualTo("gradle-profiler")
    }

    @Test
    fun `compare is historical mode and so enforces by default`() {
        val command = CompareCommand()

        command.parse(arrayOf("--baseline", "old.json", "--candidate", "new.json"))

        assertThat(command.mode).isEqualTo(ComparisonMode.HISTORICAL)
        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isTrue()
    }

    @Test
    fun `compare enforcement can be switched off explicitly`() {
        val command = CompareCommand()

        command.parse(
            arrayOf("--baseline", "old.json", "--candidate", "new.json", "--no-fail-on-regression"),
        )

        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isFalse()
    }
}
