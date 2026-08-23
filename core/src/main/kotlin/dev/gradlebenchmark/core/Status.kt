package dev.gradlebenchmark.core

/**
 * Outcome of comparing one candidate scenario against the baseline.
 *
 * Deliberately narrower than [ComparisonStatus]: a single scenario either stayed within
 * the configured tolerance or it did not. Conditions such as incompatibility or execution
 * failure are properties of the comparison as a whole, not of one candidate.
 */
public enum class ScenarioStatus {
    PASS,
    REGRESSION,
}

/**
 * Outcome of an entire comparison.
 *
 * Drawn from a different vocabulary than [ScenarioStatus] on purpose. `REGRESSION_PRESENT`
 * reads as "at least one candidate regressed", which is what a one-baseline-to-many-candidates
 * comparison can actually assert.
 *
 * [severity] encodes the rollup precedence required by the spec:
 *
 * ```
 * ERROR > INCOMPATIBLE > REGRESSION_PRESENT > INCONCLUSIVE > PASS
 * ```
 */
public enum class ComparisonStatus(public val severity: Int) {
    PASS(0),

    /**
     * Reserved. Never emitted in V1.
     *
     * Its first intended use is measurement-protocol mismatch, which V1 reports as
     * [INCOMPATIBLE] because it has no way to express "valid but less confident".
     * Present in the enum so introducing confidence analysis is not a breaking change.
     */
    INCONCLUSIVE(1),

    REGRESSION_PRESENT(2),
    INCOMPATIBLE(3),
    ERROR(4),
    ;

    public companion object {
        /** Returns the most severe of [statuses], or [PASS] when empty. */
        public fun worstOf(statuses: Iterable<ComparisonStatus>): ComparisonStatus =
            statuses.maxByOrNull { it.severity } ?: PASS
    }
}

/** Widens a per-scenario outcome into the comparison-level vocabulary. */
public fun ScenarioStatus.toComparisonStatus(): ComparisonStatus = when (this) {
    ScenarioStatus.PASS -> ComparisonStatus.PASS
    ScenarioStatus.REGRESSION -> ComparisonStatus.REGRESSION_PRESENT
}

/**
 * Rolls per-scenario outcomes up into a single comparison status, worst case wins.
 *
 * An empty input is [ComparisonStatus.ERROR], not [ComparisonStatus.PASS]: comparing
 * nothing is a failure to compare, and reporting it as a pass would let an empty or
 * fully-filtered result set look healthy.
 */
public fun rollUp(scenarioStatuses: Iterable<ScenarioStatus>): ComparisonStatus {
    val mapped = scenarioStatuses.map { it.toComparisonStatus() }
    return if (mapped.isEmpty()) ComparisonStatus.ERROR else ComparisonStatus.worstOf(mapped)
}
