package dev.gradlebenchmark.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HelpCommandTest {

    @Test
    fun `no arguments yields top level usage listing every command`() {
        val text = helpTextFor(emptyList())

        assertThat(text)
            .contains("Usage: gradle-benchmark")
            .contains("validate", "run", "compare", "help")
    }

    @Test
    fun `a named command yields that command's usage`() {
        val text = helpTextFor(listOf("run"))

        assertThat(text)
            .contains("Usage: gradle-benchmark run")
            .contains("--baseline-scenario")
    }

    @Test
    fun `an unknown command is rejected rather than falling back to top level usage`() {
        assertThatThrownBy { helpTextFor(listOf("nonsense")) }
            .hasMessageContaining("no such command: nonsense")
    }
}
