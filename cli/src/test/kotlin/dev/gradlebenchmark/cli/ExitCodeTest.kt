package dev.gradlebenchmark.cli

import dev.gradlebenchmark.core.ComparisonStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExitCodeTest {

    @Test
    fun `documented numeric values are stable`() {
        assertThat(ExitCode.SUCCESS.code).isEqualTo(0)
        assertThat(ExitCode.BENCHMARK_ERROR.code).isEqualTo(1)
        assertThat(ExitCode.REGRESSION.code).isEqualTo(2)
        assertThat(ExitCode.INVALID_INPUT.code).isEqualTo(3)
        assertThat(ExitCode.INCOMPATIBLE.code).isEqualTo(4)
    }

    @Test
    fun `passing comparison succeeds under either enforcement setting`() {
        assertThat(exitCodeFor(ComparisonStatus.PASS, failOnRegression = true))
            .isEqualTo(ExitCode.SUCCESS)
        assertThat(exitCodeFor(ComparisonStatus.PASS, failOnRegression = false))
            .isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `regression fails only when enforcement is enabled`() {
        assertThat(exitCodeFor(ComparisonStatus.REGRESSION_PRESENT, failOnRegression = true))
            .isEqualTo(ExitCode.REGRESSION)
        assertThat(exitCodeFor(ComparisonStatus.REGRESSION_PRESENT, failOnRegression = false))
            .isEqualTo(ExitCode.SUCCESS)
    }

    @Test
    fun `incompatible always fails even when regression enforcement is disabled`() {
        assertThat(exitCodeFor(ComparisonStatus.INCOMPATIBLE, failOnRegression = false))
            .isEqualTo(ExitCode.INCOMPATIBLE)
        assertThat(exitCodeFor(ComparisonStatus.INCOMPATIBLE, failOnRegression = true))
            .isEqualTo(ExitCode.INCOMPATIBLE)
    }

    @Test
    fun `benchmark error always fails and is never silently passable`() {
        assertThat(exitCodeFor(ComparisonStatus.ERROR, failOnRegression = false))
            .isEqualTo(ExitCode.BENCHMARK_ERROR)
        assertThat(exitCodeFor(ComparisonStatus.ERROR, failOnRegression = true))
            .isEqualTo(ExitCode.BENCHMARK_ERROR)
    }

    @Test
    fun `reserved INCONCLUSIVE does not fail the build in V1`() {
        assertThat(exitCodeFor(ComparisonStatus.INCONCLUSIVE, failOnRegression = true))
            .isEqualTo(ExitCode.SUCCESS)
    }
}
