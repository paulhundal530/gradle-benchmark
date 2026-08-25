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

    /**
     * The observed difference is smaller than the run could resolve.
     *
     * Not "no data", and not a pass. The numbers are still reported; what is withheld is the
     * claim that nothing changed. Reporting PASS here would assert something the experiment
     * did not establish.
     */
    INCONCLUSIVE,

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
     * At least one comparison could not be resolved, and none regressed.
     *
     * Ranks above [PASS] because it is weaker: a pass asserts the change was tolerable,
     * this asserts only that the experiment could not tell. Ranks below
     * [REGRESSION_PRESENT] so an unresolvable scenario never masks a real regression
     * elsewhere in the same run.
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
    ScenarioStatus.INCONCLUSIVE -> ComparisonStatus.INCONCLUSIVE
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
