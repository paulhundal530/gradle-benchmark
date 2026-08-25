package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Summary statistics over the measured iterations of one measurement.
 *
 * Released Gradle Profiler computes no statistics at all, so these are computed here. The
 * definitions deliberately match the ones its HTML report uses, so our numbers and the
 * report a user might open side by side agree rather than differing by a rounding
 * convention:
 *
 * - quantiles use linear interpolation, type R-7
 * - the standard deviation is the *population* deviation, dividing by n rather than n-1
 */
@Serializable
public data class Statistics(
    val mean: Double,
    val min: Double,
    val p25: Double,
    val median: Double,
    val p75: Double,
    val max: Double,
    val stddev: Double,
) {
    public companion object {
        /**
         * @throws IllegalArgumentException if [values] is empty, because a scenario with no
         *   measured iterations is an invalid benchmark and must be caught before here.
         */
        public fun of(values: List<Double>): Statistics {
            require(values.isNotEmpty()) { "Cannot compute statistics over no measurements" }

            val sorted = values.sorted()
            val mean = values.sum() / values.size
            val variance = values.sumOf { value -> (value - mean) * (value - mean) } / values.size

            return Statistics(
                mean = mean,
                min = sorted.first(),
                p25 = quantile(sorted, 0.25),
                median = quantile(sorted, 0.50),
                p75 = quantile(sorted, 0.75),
                max = sorted.last(),
                stddev = sqrt(variance),
            )
        }

        /** Type R-7 quantile: linear interpolation between order statistics. */
        internal fun quantile(sorted: List<Double>, q: Double): Double {
            val position = (sorted.size - 1) * q
            val base = floor(position).toInt()
            val rest = position - base
            return if (base + 1 < sorted.size) {
                sorted[base] + rest * (sorted[base + 1] - sorted[base])
            } else {
                sorted[base]
            }
        }
    }
}

/**
 * Which summary statistic a comparison interprets.
 *
 * A statistic is not a metric: it summarizes a measurement. Keeping the two apart is what
 * lets `mean` or `p75` be supported later without a schema change.
 */
@Serializable
public enum class Statistic {
    MEDIAN,
    MEAN,
    P75,
    ;

    public fun from(statistics: Statistics): Double = when (this) {
        MEDIAN -> statistics.median
        MEAN -> statistics.mean
        P75 -> statistics.p75
    }
}
