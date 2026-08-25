package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable

/** How a verdict is reached from an observation. */
@Serializable
public enum class ComparisonMethod {
    /** A candidate exceeding the baseline by more than a percentage regresses. */
    PERCENT,

    /**
     * Reserved. Mann-Whitney U, matching the test Gradle Profiler's own report computes.
     *
     * Not implemented: it cannot reach significance below four measured iterations per side
     * whatever the effect size, so it needs the iteration guidance to land first.
     */
    MANN_WHITNEY_U,
}

/**
 * The policy applied to an observation.
 *
 * Recorded in the output so a result can be reinterpreted later, and so two comparisons made
 * under different settings are not mistaken for each other.
 */
@Serializable
public data class ComparisonPolicy(
    val measurement: String = DEFAULT_MEASUREMENT,
    val statistic: Statistic = Statistic.MEDIAN,
    val method: ComparisonMethod = ComparisonMethod.PERCENT,
    val regressionThresholdPercent: Double = DEFAULT_THRESHOLD_PERCENT,
) {
    public companion object {
        public const val DEFAULT_MEASUREMENT: String = "total execution time"
        public const val DEFAULT_THRESHOLD_PERCENT: Double = 5.0
    }
}

/** Outcome of applying a [ComparisonPolicy] to an [Observation]. */
@Serializable
public data class Verdict(
    val status: ScenarioStatus,
    val policy: ComparisonPolicy,
    /** Why this status, in a sentence a human can act on. */
    val explanation: String,
)

/**
 * Applies a policy to an observation.
 *
 * The rule is deliberately three-way rather than a straight threshold comparison, because a
 * threshold alone asserts things the data may not support:
 *
 * ```
 * delta > threshold and distinguishable  -> REGRESSION
 * delta + resolvable <= threshold        -> PASS      (even the worst case is tolerable)
 * otherwise                              -> INCONCLUSIVE
 * ```
 *
 * The middle case is the important one. Passing requires that the *whole* interval sits
 * within tolerance, not merely the point estimate. A 1% delta on a run that can only resolve
 * 8.8% is not a pass against a 5% threshold: the true difference could be 8%.
 *
 * Equally, a delta above the threshold that the run cannot distinguish is not a regression.
 * Reporting one would be asserting a finding the experiment did not support.
 */
public object RegressionPolicy {

    public fun judge(observation: Observation, policy: ComparisonPolicy): Verdict {
        val delta = observation.deltaPercent
        val resolvable = observation.resolvablePercent
        val threshold = policy.regressionThresholdPercent

        // Comparison uses the unrounded value throughout; rounding is presentation only, so
        // 5.001% regresses against a 5% threshold even though it displays as 5.00%.
        val exceedsThreshold = delta > threshold

        // Passing asserts the change was tolerable, which requires trusting how small the
        // interval is. At two or three iterations that interval is optimistic, so a pass
        // there would rest on the one number known to be unreliable. A clear regression is
        // still reportable: too little data to rule a change out is not too little data to
        // notice a large one.
        val worstCaseWithinThreshold =
            observation.resolutionReliable && delta + resolvable <= threshold

        return when {
            exceedsThreshold && observation.distinguishable -> Verdict(
                status = ScenarioStatus.REGRESSION,
                policy = policy,
                explanation = "%.1f%% slower, beyond the %.1f%% threshold and larger than the "
                    .format(delta, threshold) +
                    "%.1f%% this run could resolve.".format(resolvable),
            )

            worstCaseWithinThreshold -> Verdict(
                status = ScenarioStatus.PASS,
                policy = policy,
                explanation = "%+.1f%%, within the %.1f%% threshold even allowing for the "
                    .format(delta, threshold) +
                    "%.1f%% this run could resolve.".format(resolvable),
            )

            else -> Verdict(
                status = ScenarioStatus.INCONCLUSIVE,
                policy = policy,
                explanation = if (observation.resolutionReliable) {
                    "%+.1f%% observed, but this run could only resolve %.1f%%. "
                        .format(delta, resolvable) +
                        "That is not evidence of no change; it is too few measured " +
                        "iterations (%d vs %d) to tell."
                            .format(observation.baselineSampleSize, observation.candidateSampleSize)
                } else {
                    "%+.1f%% observed, with only %d and %d measured iterations. "
                        .format(delta, observation.baselineSampleSize, observation.candidateSampleSize) +
                        "That is too few to estimate how noisy the build is, so no " +
                        "conclusion can be drawn either way."
                },
            )
        }
    }
}
