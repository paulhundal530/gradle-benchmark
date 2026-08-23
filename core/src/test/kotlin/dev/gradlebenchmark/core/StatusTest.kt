package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusTest {

    @Test
    fun `precedence order is ERROR INCOMPATIBLE REGRESSION_PRESENT INCONCLUSIVE PASS`() {
        val bySeverityDescending = ComparisonStatus.entries.sortedByDescending { it.severity }

        assertThat(bySeverityDescending).containsExactly(
            ComparisonStatus.ERROR,
            ComparisonStatus.INCOMPATIBLE,
            ComparisonStatus.REGRESSION_PRESENT,
            ComparisonStatus.INCONCLUSIVE,
            ComparisonStatus.PASS,
        )
    }

    @Test
    fun `INCONCLUSIVE outranks PASS but never overwrites a real regression`() {
        assertThat(ComparisonStatus.INCONCLUSIVE.severity)
            .isGreaterThan(ComparisonStatus.PASS.severity)
        assertThat(ComparisonStatus.INCONCLUSIVE.severity)
            .isLessThan(ComparisonStatus.REGRESSION_PRESENT.severity)
    }

    @Test
    fun `worstOf picks the most severe status regardless of order`() {
        val statuses = listOf(
            ComparisonStatus.PASS,
            ComparisonStatus.INCOMPATIBLE,
            ComparisonStatus.REGRESSION_PRESENT,
        )

        assertThat(ComparisonStatus.worstOf(statuses)).isEqualTo(ComparisonStatus.INCOMPATIBLE)
        assertThat(ComparisonStatus.worstOf(statuses.reversed()))
            .isEqualTo(ComparisonStatus.INCOMPATIBLE)
    }

    @Test
    fun `all candidates passing rolls up to PASS`() {
        assertThat(rollUp(listOf(ScenarioStatus.PASS, ScenarioStatus.PASS)))
            .isEqualTo(ComparisonStatus.PASS)
    }

    @Test
    fun `a single regressing candidate rolls up to REGRESSION_PRESENT`() {
        val statuses = listOf(ScenarioStatus.PASS, ScenarioStatus.REGRESSION, ScenarioStatus.PASS)

        assertThat(rollUp(statuses)).isEqualTo(ComparisonStatus.REGRESSION_PRESENT)
    }

    @Test
    fun `comparing no scenarios is an error rather than a pass`() {
        assertThat(rollUp(emptyList())).isEqualTo(ComparisonStatus.ERROR)
    }
}
