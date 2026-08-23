package dev.gradlebenchmark.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ComparisonModeTest {

    @Test
    fun `variant comparison does not gate CI by default`() {
        assertThat(ComparisonMode.VARIANT.failOnRegressionDefault).isFalse()
    }

    @Test
    fun `historical comparison gates CI by default`() {
        assertThat(ComparisonMode.HISTORICAL.failOnRegressionDefault).isTrue()
    }

    @Test
    fun `an explicit choice always beats the mode default`() {
        assertThat(ComparisonMode.VARIANT.resolveFailOnRegression(explicit = true)).isTrue()
        assertThat(ComparisonMode.HISTORICAL.resolveFailOnRegression(explicit = false)).isFalse()
    }

    @Test
    fun `an unset choice falls back to the mode default`() {
        assertThat(ComparisonMode.VARIANT.resolveFailOnRegression(explicit = null)).isFalse()
        assertThat(ComparisonMode.HISTORICAL.resolveFailOnRegression(explicit = null)).isTrue()
    }
}
