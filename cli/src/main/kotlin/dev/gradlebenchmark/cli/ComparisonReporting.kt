package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import dev.gradlebenchmark.core.BenchmarkComparison
import dev.gradlebenchmark.core.ComparisonMode
import dev.gradlebenchmark.core.Difference
import dev.gradlebenchmark.core.Observation

/**
 * Renders a comparison on the console.
 *
 * Reports what was measured and under what conditions. It never says whether a difference
 * is acceptable, because that depends on what the scenario is for, which the tool does not
 * know.
 */
internal fun CliktCommand.reportComparison(comparison: BenchmarkComparison) {
    echo(heading(comparison))
    echo("")

    comparison.scenarios.forEach { scenario ->
        val observation = scenario.observation
        echo(
            "  %-26s %9s  %+7.1f%%".format(
                scenario.name,
                format(observation.candidateValue, observation.unit),
                observation.deltaPercent,
            ),
        )
        echo("    " + resolution(observation))
        echo("    " + effort(observation))

        scenario.differences.takeIf { it.isNotEmpty() }?.let { differences ->
            echo("    differs in:")
            differences.forEach { echo("      " + describe(it)) }
        }
        echo("")
    }

    // Experiment quality, kept apart from the measurements and sent to stderr.
    comparison.diagnostics.forEach { echo("  ! $it", err = true) }
}

private fun heading(comparison: BenchmarkComparison): String = when (comparison.mode) {
    ComparisonMode.VARIANT ->
        "Compared against ${comparison.baseline.scenarioName}:"

    ComparisonMode.RUNS -> {
        val baselineRevision = comparison.baseline.revision
        val candidateRevision = comparison.candidate?.revision

        // Naming the revision twice would be noise when both runs came from the same
        // checkout, which is the normal case when the variable under test is the scenario
        // configuration rather than the code.
        val differentRevisions = baselineRevision != null &&
            candidateRevision != null &&
            baselineRevision != candidateRevision

        if (differentRevisions) {
            "Compared ${baselineRevision.take(SHORT_REVISION)} against " +
                "${candidateRevision.take(SHORT_REVISION)}:"
        } else {
            "Compared two runs" +
                (baselineRevision?.let { " at ${it.take(SHORT_REVISION)}" } ?: "") + ":"
        }
    }
}

/**
 * Whether the difference is larger than the experiment could resolve.
 *
 * A statement about the measurement, not about the change. "Smaller than this experiment
 * could resolve" is not the same as "unchanged", and saying so plainly is the point.
 */
private fun resolution(observation: Observation): String {
    val resolvable = "%.1f%%".format(observation.resolvablePercent)
    return if (observation.distinguishable) {
        "beyond what this experiment could resolve (±$resolvable)"
    } else {
        "within what this experiment could resolve (±$resolvable), so not separable from noise"
    }
}

/** The conditions that produced the number, so someone can reproduce it. */
private fun effort(observation: Observation): String = buildString {
    append(observation.baselineWarmUpCount).append(" warm-ups, ")
    append(observation.baselineSampleSize).append(" measured")
    if (observation.baselineSampleSize != observation.candidateSampleSize ||
        observation.baselineWarmUpCount != observation.candidateWarmUpCount
    ) {
        append(" against ")
        append(observation.candidateWarmUpCount).append(" warm-ups, ")
        append(observation.candidateSampleSize).append(" measured")
    } else {
        append(" iterations per side")
    }
}

private fun describe(difference: Difference): String {
    val marker = if (difference.changesWorkPerformed) " (changes the work performed)" else ""
    val baseline = difference.baseline.ifEmpty { "<unset>" }
    val candidate = difference.candidate.ifEmpty { "<unset>" }
    return "${difference.field}: $baseline -> $candidate$marker"
}

private fun format(value: Double, unit: String): String = if (unit == "ms" && value >= MILLIS_PER_SECOND) {
    "%.2fs".format(value / MILLIS_PER_SECOND)
} else {
    "%.0f%s".format(value, unit)
}

private const val MILLIS_PER_SECOND = 1000.0
private const val SHORT_REVISION = 7
