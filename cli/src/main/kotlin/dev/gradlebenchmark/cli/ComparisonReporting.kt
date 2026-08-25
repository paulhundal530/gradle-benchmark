package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import dev.gradlebenchmark.core.BenchmarkComparison
import dev.gradlebenchmark.core.ComparisonMode
import dev.gradlebenchmark.core.ComparisonStatus
import dev.gradlebenchmark.core.Direction
import dev.gradlebenchmark.core.ScenarioStatus

/**
 * Renders a comparison on the console.
 *
 * Deliberately a summary. The JSON is the canonical artifact and the HTML report is the
 * human one; console text exists to say what happened and where to look.
 */
internal fun CliktCommand.reportComparison(comparison: BenchmarkComparison) {
    val baselineName = comparison.baseline.scenarioName ?: "baseline"

    echo("Compared against $baselineName:")
    echo("")

    comparison.scenarios.forEach { scenario ->
        val observation = scenario.observation
        echo(
            "  %-28s %8.0f%s  %+7.1f%%  %s".format(
                scenario.name,
                observation.candidateValue,
                observation.unit,
                observation.deltaPercent,
                label(scenario.verdict.status, observation.direction, comparison.mode),
            ),
        )
    }

    echo("")
    echo(conclusion(comparison))

    // Diagnostics describe the quality of the experiment rather than its outcome, so they
    // are kept apart from the verdicts and sent to stderr.
    comparison.diagnostics.forEach { echo("  ! $it", err = true) }
}

/**
 * Wording depends on the mode, because the same number means different things.
 *
 * Deliberately benchmarking a slower configuration is an experimental result, not a fault,
 * so variant mode says "slower" where nightly monitoring says "regression". The status in
 * the JSON is identical either way; only the presentation differs.
 */
private fun label(status: ScenarioStatus, direction: Direction, mode: ComparisonMode): String = when (status) {
    ScenarioStatus.INCONCLUSIVE -> "inconclusive"

    ScenarioStatus.REGRESSION -> when (mode) {
        ComparisonMode.VARIANT -> "slower"
        else -> "REGRESSION"
    }

    ScenarioStatus.PASS -> when (direction) {
        Direction.FASTER -> "faster"
        Direction.SLOWER -> "slower, within tolerance"
        Direction.INDISTINGUISHABLE -> "no measurable change"
    }
}

private fun conclusion(comparison: BenchmarkComparison): String {
    val scenarios = comparison.scenarios
    val regressed = scenarios.count { it.verdict.status == ScenarioStatus.REGRESSION }
    val inconclusive = scenarios.count { it.verdict.status == ScenarioStatus.INCONCLUSIVE }

    return when (comparison.overallComparisonStatus) {
        ComparisonStatus.ERROR ->
            "Nothing could be compared."

        ComparisonStatus.INCOMPATIBLE ->
            "Results were not comparable."

        ComparisonStatus.REGRESSION_PRESENT -> {
            val threshold = comparison.comparisonPolicy.regressionThresholdPercent
            "$regressed of ${scenarios.size} exceeded the %.1f%% threshold.".format(threshold)
        }

        ComparisonStatus.INCONCLUSIVE ->
            "$inconclusive of ${scenarios.size} could not be resolved. " +
                "That is not evidence of no change; add measured iterations to tell."

        ComparisonStatus.PASS ->
            "All ${scenarios.size} within the configured threshold."
    }
}
