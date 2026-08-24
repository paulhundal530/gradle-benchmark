package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.testing.test
import dev.gradlebenchmark.engine.GradleProfiler
import dev.gradlebenchmark.engine.ProfilerInvocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ValidateCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private val dumpOfTwo = "baseline {\n    tasks=[work]\n}\ncc-enabled {\n    tasks=[work]\n}"

    private fun scenarioFile(): Path = tempDir.resolve("build.scenarios").also { it.writeText("baseline { }\n") }

    private fun fakeProfiler(exitCode: Int, stdout: String = "", stderr: String = "") = { _: String ->
        object : GradleProfiler {
            override fun invoke(arguments: List<String>) = ProfilerInvocation(exitCode, stdout, stderr)
        }
    }

    @Test
    fun `a valid selection succeeds and lists the scenarios that will run`() {
        val result = ValidateCommand(fakeProfiler(0, dumpOfTwo))
            .test(listOf("--scenario-file", scenarioFile().toString()))

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("baseline").contains("cc-enabled")
    }

    @Test
    fun `an invalid selection exits with invalid input and explains why on stderr`() {
        val result = ValidateCommand(fakeProfiler(0, dumpOfTwo))
            .test(listOf("--scenario-dir", tempDir.resolve("absent").toString()))

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("Scenario directory does not exist")
    }

    @Test
    fun `a profiler rejection surfaces the profiler's message without a stack trace`() {
        val profiler = fakeProfiler(
            exitCode = 1,
            stderr = "java.lang.IllegalArgumentException: Unknown scenario group 'nope' " +
                "requested. Available groups are: nightly\n\tat org.gradle.profiler.Main.main",
        )

        val result = ValidateCommand(profiler).test(
            listOf("--scenario-file", scenarioFile().toString(), "--scenario-group", "nope"),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("Available groups are: nightly")
        assertThat(result.stderr).doesNotContain("java.lang.IllegalArgumentException")
    }

    @Test
    fun `a misspelled baseline is caught by validate before any benchmark runs`() {
        val result = ValidateCommand(fakeProfiler(0, dumpOfTwo)).test(
            listOf(
                "--scenario-file",
                scenarioFile().toString(),
                "--baseline-scenario",
                "basline",
            ),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("basline").contains("baseline, cc-enabled")
    }
}

class RunCommandValidationTest {

    @TempDir
    lateinit var tempDir: Path

    private val dumpOfTwo = "baseline {\n    tasks=[work]\n}\ncc-enabled {\n    tasks=[work]\n}"

    private fun scenarioFile(): Path = tempDir.resolve("build.scenarios").also { it.writeText("baseline { }\n") }

    private fun fakeProfiler(exitCode: Int, stdout: String = "") = { _: String ->
        object : GradleProfiler {
            override fun invoke(arguments: List<String>) = ProfilerInvocation(exitCode, stdout, "")
        }
    }

    @Test
    fun `run refuses to proceed when the baseline is not in the selection`() {
        val result = RunCommand(fakeProfiler(0, dumpOfTwo)).test(
            listOf(
                "--scenario-file",
                scenarioFile().toString(),
                "--baseline-scenario",
                "nope",
            ),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.INVALID_INPUT.code)
        assertThat(result.stderr).contains("not part of this selection")
    }

    @Test
    fun `run reports the resolved selection before its configuration`() {
        val result = RunCommand(fakeProfiler(0, dumpOfTwo)).test(
            listOf(
                "--scenario-file",
                scenarioFile().toString(),
                "--baseline-scenario",
                "baseline",
            ),
        )

        assertThat(result.statusCode).isEqualTo(ExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("cc-enabled").contains("enforcement:")
    }
}
