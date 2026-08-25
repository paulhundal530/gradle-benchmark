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
    fun `run parses scenario selection`() {
        val command = runCommand()

        command.parse(selecting("--scenario-group", "nightly", "--baseline-scenario", "baseline"))

        assertThat(command.scenarioGroup).isEqualTo("nightly")
        assertThat(command.baselineScenario).isEqualTo("baseline")
    }

    @Test
    fun `run leaves the baseline unset so no comparison is implied`() {
        val command = runCommand()

        command.parse(selecting())

        assertThat(command.baselineScenario).isNull()
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
    fun `individual scenarios can be selected`() {
        val command = runCommand()

        command.parse(selecting("--scenario", "one", "--scenario", "two"))

        assertThat(command.scenarioNames).containsExactly("one", "two")
    }

    @Test
    fun `compare requires both sides`() {
        assertThat(runCli(arrayOf("compare", "--candidate", "new.json")))
            .isEqualTo(ExitCode.INVALID_INPUT)
        assertThat(runCli(arrayOf("compare", "--baseline", "old.json")))
            .isEqualTo(ExitCode.INVALID_INPUT)
    }
}
