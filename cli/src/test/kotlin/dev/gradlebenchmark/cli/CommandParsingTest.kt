package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.parse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CommandParsingTest {

    @Test
    fun `run parses scenario selection and threshold`() {
        val command = RunCommand()

        command.parse(
            arrayOf(
                "--scenario-dir", "benchmarks",
                "--scenario-file", "build.scenarios",
                "--scenario-group", "configuration-cache",
                "--baseline-scenario", "baseline",
                "--regression-threshold-percent", "7.5",
            ),
        )

        assertThat(command.scenarioDir).isEqualTo(Path.of("benchmarks"))
        assertThat(command.scenarioFile).isEqualTo(Path.of("build.scenarios"))
        assertThat(command.scenarioGroup).isEqualTo("configuration-cache")
        assertThat(command.baselineScenario).isEqualTo("baseline")
        assertThat(command.regressionThresholdPercent).isEqualTo(7.5)
    }

    @Test
    fun `run defaults the threshold to five percent`() {
        val command = RunCommand()

        command.parse(emptyArray())

        assertThat(command.regressionThresholdPercent).isEqualTo(DEFAULT_THRESHOLD_PERCENT)
    }

    @Test
    fun `run leaves the baseline unset so no comparison is implied`() {
        val command = RunCommand()

        command.parse(emptyArray())

        assertThat(command.baselineScenario).isNull()
    }

    @Test
    fun `run is variant mode and so does not enforce by default`() {
        val command = RunCommand()

        command.parse(emptyArray())

        assertThat(command.mode).isEqualTo(ComparisonMode.VARIANT)
        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isFalse()
    }

    @Test
    fun `run enforcement can be switched on explicitly`() {
        val command = RunCommand()

        command.parse(arrayOf("--fail-on-regression"))

        assertThat(command.mode.resolveFailOnRegression(command.failOnRegressionFlag)).isTrue()
    }

    @Test
    fun `timeout is unbounded unless requested`() {
        val command = RunCommand()

        command.parse(emptyArray())

        assertThat(command.timeoutMinutes).isNull()
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
