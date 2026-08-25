package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/**
 * Which way a candidate moved relative to the baseline.
 *
 * Deliberately neutral. Comparing configuration-cache-enabled against disabled, "slower" is
 * an experimental result rather than a fault, and the word "regression" would be wrong. The
 * judgement lives in [Verdict]; this only reports direction.
 */
@Serializable
public enum class Direction {
    FASTER,
    SLOWER,

    /** The difference is smaller than the run could resolve. */
    INDISTINGUISHABLE,
}

/**
 * What was measured.
 *
 * The whole of what this tool asserts. Everything here is a fact about the experiment
 * rather than a judgement about the result, so it stays true regardless of what anyone
 * considers acceptable.
 *
 * [resolvablePercent] is the smallest difference these sample sizes could distinguish,
 * derived from the measured values rather than assumed. [distinguishable] says whether the
 * observed difference exceeds it. That is a statement about the measurement: it does not
 * say the change is good, bad, or worth acting on.
 */
@Serializable
public data class Observation(
    val baselineValue: Double,
    val candidateValue: Double,
    val unit: String,
    val deltaPercent: Double,
    val direction: Direction,
    val resolvablePercent: Double,
    val distinguishable: Boolean,
    /** Measured iterations on each side, because resolution follows directly from them. */
    val baselineSampleSize: Int,
    val candidateSampleSize: Int,
    /**
     * Warm-ups on each side.
     *
     * Reported because "24.3% faster, from 3 warm-ups and 10 measured iterations" is a
     * claim someone can reproduce, where the percentage alone is not.
     */
    val baselineWarmUpCount: Int = 0,
    val candidateWarmUpCount: Int = 0,
    /**
     * Whether [resolvablePercent] can be trusted.
     *
     * Resampling two measurements can only produce three distinct medians, so the interval
     * comes out narrow regardless of how noisy the underlying build actually is. Observed on
     * real data: a two-iteration run reported 33.6% resolvable where the same scenario at
     * twelve iterations reported 41.0%. The extra data did not make resolution worse; the
     * small sample had simply been unable to see how noisy it was.
     *
     * False here means the estimate is optimistic, so no conclusion may rest on it being
     * small.
     */
    val resolutionReliable: Boolean,
) {
    public companion object {

        /**
         * Resamples used to estimate the interval around a median.
         *
         * The median has no closed-form interval that is reliable at small n, and an
         * order-statistic interval collapses to the full range once n drops below about
         * five, which is exactly where real benchmarks live.
         */
        internal const val RESAMPLES: Int = 2_000

        /**
         * Fixed seed, so the same input always produces the same interval.
         *
         * `run.json` and `comparison.json` are compared byte for byte in tests and diffed by
         * users; a verdict that changed between two runs of the same data would be
         * indefensible.
         */
        internal const val SEED: Long = 0x6E6F697365L // "noise"

        /**
         * Below this, the interval estimate is not trustworthy in either direction.
         *
         * Matches the point at which a rank-based test could begin to conclude, so the
         * guidance stays consistent once one is implemented.
         */
        internal const val MINIMUM_RELIABLE_SAMPLES: Int = 4

        /**
         * How far a difference must exceed an untrustworthy interval to count.
         *
         * Below [MINIMUM_RELIABLE_SAMPLES] the interval is optimistic, so a difference only
         * slightly larger than it proves nothing. A difference several times larger is
         * beyond anything that optimism explains.
         */
        internal const val SMALL_SAMPLE_MARGIN: Double = 3.0

        public fun of(
            baseline: List<Double>,
            candidate: List<Double>,
            unit: String,
            statistic: Statistic = Statistic.MEDIAN,
            baselineWarmUps: Int = 0,
            candidateWarmUps: Int = 0,
        ): Observation {
            require(baseline.isNotEmpty() && candidate.isNotEmpty()) {
                "Cannot compare without measurements on both sides"
            }

            val baselineValue = statistic.from(Statistics.of(baseline))
            val candidateValue = statistic.from(Statistics.of(candidate))

            val deltaPercent = if (baselineValue == 0.0) {
                0.0
            } else {
                (candidateValue - baselineValue) / baselineValue * 100.0
            }

            // Each side carries its own uncertainty, so the smallest difference that can be
            // told apart is bounded by both.
            val baselineHalfWidth = halfWidth(baseline, statistic)
            val candidateHalfWidth = halfWidth(candidate, statistic)
            val resolvable = baselineHalfWidth + candidateHalfWidth
            val resolvablePercent = if (baselineValue == 0.0) {
                0.0
            } else {
                resolvable / baselineValue * 100.0
            }

            val reliable = baseline.size >= MINIMUM_RELIABLE_SAMPLES &&
                candidate.size >= MINIMUM_RELIABLE_SAMPLES

            // When the interval itself cannot be trusted, demand a much wider margin before
            // treating a difference as established. A five-fold difference is obvious no
            // matter how few iterations produced it; a difference merely twice an
            // untrustworthy interval is not.
            val margin = if (reliable) 1.0 else SMALL_SAMPLE_MARGIN
            val distinguishable = abs(candidateValue - baselineValue) > resolvable * margin

            return Observation(
                baselineValue = baselineValue,
                candidateValue = candidateValue,
                unit = unit,
                deltaPercent = deltaPercent,
                direction = when {
                    !distinguishable -> Direction.INDISTINGUISHABLE
                    candidateValue > baselineValue -> Direction.SLOWER
                    else -> Direction.FASTER
                },
                resolvablePercent = resolvablePercent,
                distinguishable = distinguishable,
                baselineSampleSize = baseline.size,
                candidateSampleSize = candidate.size,
                baselineWarmUpCount = baselineWarmUps,
                candidateWarmUpCount = candidateWarmUps,
                resolutionReliable = reliable,
            )
        }

        /**
         * Half-width of a 95% bootstrap interval around [statistic].
         *
         * A single measurement has no spread to estimate from, so it contributes no
         * uncertainty; that case is caught by the sample-size warnings rather than pretended
         * away here.
         */
        internal fun halfWidth(values: List<Double>, statistic: Statistic): Double {
            if (values.size < 2) return 0.0

            val random = Random(SEED)
            val estimates = DoubleArray(RESAMPLES) {
                val resample = List(values.size) { values[random.nextInt(values.size)] }
                statistic.from(Statistics.of(resample))
            }
            estimates.sort()

            val low = estimates[(RESAMPLES * 0.025).toInt()]
            val high = estimates[(RESAMPLES * 0.975).toInt()]
            return (high - low) / 2.0
        }
    }
}
