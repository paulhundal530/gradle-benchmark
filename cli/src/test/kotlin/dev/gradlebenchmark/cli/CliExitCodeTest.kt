package dev.gradlebenchmark.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Exercises the CLI end to end, since exit codes are a contract CI branches on. */
class CliExitCodeTest {

    @Test
    fun `help is a successful request for output`() {
        assertThat(runCli(arrayOf("--help"))).isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `an unknown subcommand is invalid input`() {
        assertThat(runCli(arrayOf("frobnicate"))).isEqualTo(ExitCode.INVALID_INPUT)
    }

    @Test
    fun `an unknown option is invalid input`() {
        assertThat(runCli(arrayOf("run", "--nope"))).isEqualTo(ExitCode.INVALID_INPUT)
    }

    @Test
    fun `compare without a baseline is invalid input rather than a special case`() {
        assertThat(runCli(arrayOf("compare", "--candidate", "new.json")))
            .isEqualTo(ExitCode.INVALID_INPUT)
    }

    @Test
    fun `compare without a candidate is invalid input`() {
        assertThat(runCli(arrayOf("compare", "--baseline", "old.json")))
            .isEqualTo(ExitCode.INVALID_INPUT)
    }

    @Test
    fun `bare invocation surfaces usage instead of failing`() {
        assertThat(runCli(emptyArray())).isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `help subcommand succeeds`() {
        assertThat(runCli(arrayOf("help"))).isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `help for a known command succeeds`() {
        assertThat(runCli(arrayOf("help", "run"))).isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `help for an unknown command is invalid input rather than silent fallback`() {
        assertThat(runCli(arrayOf("help", "nonsense"))).isEqualTo(ExitCode.INVALID_INPUT)
    }
}
