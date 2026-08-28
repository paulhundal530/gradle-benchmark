package dev.gradlebenchmark.report

import dev.gradlebenchmark.core.BenchmarkComparison
import dev.gradlebenchmark.core.BenchmarkRun
import dev.gradlebenchmark.core.ComparisonMode
import dev.gradlebenchmark.core.Difference
import dev.gradlebenchmark.core.MeasurementResult
import dev.gradlebenchmark.core.Observation
import dev.gradlebenchmark.core.ScenarioComparison
import dev.gradlebenchmark.core.ScenarioRun
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * Renders the human-facing report.
 *
 * Built from the normalized model rather than from Gradle Profiler's output, so what a
 * reader sees and what `comparison.json` says can never disagree.
 *
 * The report reports. It carries no verdict, because the tool does not have one: the
 * resolution of the experiment is presented alongside every difference, at nearly the same
 * weight as the number itself, so a difference the run could not resolve cannot be read as
 * a finding.
 *
 * Self-contained by construction: inline styles, inline SVG, no scripts and no external
 * requests, because a report is usually opened straight out of a CI artifact.
 */
public object HtmlReportRenderer {

    /** Renders a comparison. [baselineRun] and [candidateRun] supply the iteration series. */
    public fun render(
        comparison: BenchmarkComparison,
        baselineRun: BenchmarkRun,
        candidateRun: BenchmarkRun = baselineRun,
    ): String {
        val body = buildString {
            append(header(comparison))
            comparison.scenarios.forEach { scenario ->
                append(
                    scenarioCard(
                        scenario = scenario,
                        comparison = comparison,
                        baseline = baselineScenarioFor(scenario, comparison, baselineRun),
                        candidate = candidateRun.scenarios.firstOrNull { it.name == scenario.name },
                        measurement = comparison.measurement,
                    ),
                )
            }
            append(diagnostics(comparison.diagnostics))
            append(metadata(candidateRun, comparison))
        }
        return document(title(comparison), body)
    }

    /**
     * Renders measurements with nothing to compare them against.
     *
     * A run without a named baseline still produced numbers worth reading; it simply has no
     * comparison to make, and says so rather than implying one.
     */
    public fun render(run: BenchmarkRun, measurement: String): String {
        val body = buildString {
            append("<h1>Benchmark measurements</h1>")
            append("""<p class="subtitle">${escape(run.timestamp)}</p>""")
            append(
                """<p class="note">No baseline scenario was named, so nothing was compared. """ +
                    """These are the measurements on their own.</p>""",
            )
            run.scenarios.forEach { scenario ->
                val result = scenario.measurement(measurement) ?: return@forEach
                append("""<section class="scenario">""")
                append("""<p class="scenario-name">${escape(scenario.name)}</p>""")
                scenario.title?.let { append("""<p class="scenario-title">${escape(it)}</p>""") }
                append("""<div class="headline">""")
                append(
                    """<span class="delta flat">""" +
                        "${formatValue(result.statistics.median, result.unit)}</span>",
                )
                append("""<span class="values">median</span></div>""")
                append("""<p class="effort">${effort(result)}</p>""")
                append(IterationChart.render(result))
                append(statisticsTable(mapOf(scenario.name to result)))
                append("</section>")
            }
            append(diagnostics(emptyList()))
            append(metadata(run, null))
        }
        return document("Benchmark measurements", body)
    }

    public fun write(content: String, destination: Path): Path {
        destination.createParentDirectories()
        destination.writeText(content)
        return destination
    }

    // ---- structure ----

    private fun document(title: String, body: String): String = buildString {
        append("<!DOCTYPE html>\n")
        append("""<html lang="en"><head><meta charset="utf-8">""")
        append("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
        append("<title>${escape(title)}</title>")
        append("<style>\n$REPORT_STYLES\n</style>")
        append("</head><body><main>")
        append(body)
        append("</main></body></html>\n")
    }

    private fun title(comparison: BenchmarkComparison): String = when (comparison.mode) {
        ComparisonMode.VARIANT ->
            "Benchmark comparison against ${comparison.baseline.scenarioName}"
        ComparisonMode.RUNS -> "Benchmark comparison"
    }

    private fun header(comparison: BenchmarkComparison): String = buildString {
        append("<h1>${escape(title(comparison))}</h1>")
        append("""<p class="subtitle">""")
        append(escape(subtitle(comparison)))
        append("</p>")
    }

    private fun subtitle(comparison: BenchmarkComparison): String {
        val baselineRevision = comparison.baseline.revision
        val candidateRevision = comparison.candidate?.revision
        val revisions = when {
            baselineRevision != null &&
                candidateRevision != null &&
                baselineRevision != candidateRevision ->
                "${baselineRevision.take(SHORT_REVISION)} → ${candidateRevision.take(SHORT_REVISION)}"

            baselineRevision != null -> "at ${baselineRevision.take(SHORT_REVISION)}"
            else -> null
        }
        return listOfNotNull(revisions, comparison.measurement, comparison.timestamp)
            .joinToString("  ·  ")
    }

    private fun baselineScenarioFor(
        scenario: ScenarioComparison,
        comparison: BenchmarkComparison,
        baselineRun: BenchmarkRun,
    ): ScenarioRun? = when (comparison.mode) {
        // In a variant comparison the baseline is a different scenario in the same run.
        ComparisonMode.VARIANT ->
            baselineRun.scenarios.firstOrNull { it.name == comparison.baseline.scenarioName }
        ComparisonMode.RUNS ->
            baselineRun.scenarios.firstOrNull { it.name == scenario.name }
    }

    private fun scenarioCard(
        scenario: ScenarioComparison,
        comparison: BenchmarkComparison,
        baseline: ScenarioRun?,
        candidate: ScenarioRun?,
        measurement: String,
    ): String = buildString {
        val observation = scenario.observation
        append("""<section class="scenario">""")
        append("""<p class="scenario-name">${escape(scenario.name)}</p>""")
        scenario.title?.let { append("""<p class="scenario-title">${escape(it)}</p>""") }

        append("""<div class="headline">""")
        append("""<span class="delta ${directionClass(observation)}">""")
        append(formatDelta(observation.deltaPercent))
        append("</span>")
        append("""<span class="values">""")
        append(formatValue(observation.baselineValue, observation.unit))
        append(" → ")
        append(formatValue(observation.candidateValue, observation.unit))
        append("</span></div>")

        // Directly beneath the number, so it cannot be read without it.
        append("""<p class="resolution ${resolutionClass(observation)}">""")
        append(escape(resolutionText(observation)))
        append("</p>")
        append("""<p class="effort">${escape(effort(observation))}</p>""")

        candidate?.measurement(measurement)?.let { append(IterationChart.render(it)) }
        append(differences(scenario.differences))

        // Label the two rows by whatever actually distinguishes them. In a variant
        // comparison that is the scenario name; across two runs both sides share a name, so
        // the revision is what tells them apart.
        val series = buildMap {
            val (baselineLabel, candidateLabel) = when (comparison.mode) {
                ComparisonMode.VARIANT ->
                    (comparison.baseline.scenarioName ?: "baseline") to scenario.name

                ComparisonMode.RUNS -> {
                    val from = comparison.baseline.revision?.take(SHORT_REVISION)
                    val to = comparison.candidate?.revision?.take(SHORT_REVISION)
                    (from?.let { "baseline ($it)" } ?: "baseline") to
                        (to?.let { "candidate ($it)" } ?: "candidate")
                }
            }
            baseline?.measurement(measurement)?.let { put(baselineLabel, it) }
            candidate?.measurement(measurement)?.let { put(candidateLabel, it) }
        }
        if (series.isNotEmpty()) {
            append("<details><summary>Full statistics</summary>")
            append(statisticsTable(series))
            append("</details>")
        }
        append("</section>")
    }

    private fun differences(differences: List<Difference>): String {
        if (differences.isEmpty()) return ""
        return buildString {
            append("""<dl class="differences">""")
            differences.forEach { difference ->
                val marker = if (difference.changesWorkPerformed) {
                    """ <span class="changes-work">(changes the work performed)</span>"""
                } else {
                    ""
                }
                append("<dt>${escape(difference.field)}$marker</dt>")
                append("<dd>")
                append(escape(difference.baseline.ifEmpty { "<unset>" }))
                append(" → ")
                append(escape(difference.candidate.ifEmpty { "<unset>" }))
                append("</dd>")
            }
            append("</dl>")
        }
    }

    private fun statisticsTable(series: Map<String, MeasurementResult>): String = buildString {
        append("<table><thead><tr><th>scenario</th>")
        listOf("median", "mean", "min", "p25", "p75", "max", "stddev")
            .forEach { append("<th>$it</th>") }
        append("</tr></thead><tbody>")
        series.forEach { (name, result) ->
            val statistics = result.statistics
            append("<tr><td>${escape(name)}</td>")
            listOf(
                statistics.median,
                statistics.mean,
                statistics.min,
                statistics.p25,
                statistics.p75,
                statistics.max,
                statistics.stddev,
            ).forEach { append("<td>${formatNumber(it)}</td>") }
            append("</tr>")
        }
        append("</tbody></table>")
    }

    private fun diagnostics(diagnostics: List<String>): String {
        if (diagnostics.isEmpty()) return ""
        return buildString {
            append("<h2>About this experiment</h2>")
            append("""<div class="diagnostics">""")
            diagnostics.forEach { append("<p>${escape(it)}</p>") }
            append("</div>")
        }
    }

    private fun metadata(run: BenchmarkRun, comparison: BenchmarkComparison?): String = buildString {
        append("<h2>Environment</h2>")
        append("""<div class="meta">""")
        val environment = run.executionEnvironment
        append(line("os", listOfNotNull(environment.operatingSystem, environment.architecture).joinToString(" ")))
        environment.cpuCores?.let { append(line("cpu cores", it.toString())) }
        run.scenarios.firstOrNull()?.let { scenario ->
            scenario.workloadConfiguration.gradleVersion?.let { append(line("gradle", it)) }
            scenario.workloadConfiguration.buildJvmVersion?.let { append(line("build jvm", it)) }
            scenario.measurementProtocol.profilerVersion?.let { append(line("gradle-profiler", it)) }
        }
        comparison?.let { append(line("statistic", it.statistic.name.lowercase())) }
        append(line("gradle-benchmark", run.toolVersion))
        append("</div>")
    }

    private fun line(label: String, value: String): String = "<div>${escape(label)}: ${escape(value)}</div>"

    // ---- presentation ----

    private fun directionClass(observation: Observation): String = when {
        !observation.distinguishable -> "flat"
        observation.candidateValue < observation.baselineValue -> "faster"
        else -> "slower"
    }

    private fun resolutionClass(observation: Observation): String =
        if (observation.distinguishable) "resolved" else "unresolved"

    private fun resolutionText(observation: Observation): String {
        val resolvable = "%.1f%%".format(observation.resolvablePercent)
        return if (observation.distinguishable) {
            "Beyond what this experiment could resolve (±$resolvable)."
        } else {
            "Within what this experiment could resolve (±$resolvable), " +
                "so not separable from noise. That is not evidence of no change."
        }
    }

    private fun effort(observation: Observation): String =
        if (observation.baselineWarmUpCount == observation.candidateWarmUpCount &&
            observation.baselineSampleSize == observation.candidateSampleSize
        ) {
            "${observation.baselineWarmUpCount} warm-ups, " +
                "${observation.baselineSampleSize} measured iterations per side"
        } else {
            "${observation.baselineWarmUpCount} warm-ups and " +
                "${observation.baselineSampleSize} measured against " +
                "${observation.candidateWarmUpCount} warm-ups and " +
                "${observation.candidateSampleSize} measured"
        }

    private fun effort(result: MeasurementResult): String =
        "${result.warmUpValues.size} warm-ups, ${result.values.size} measured iterations"

    private fun formatDelta(percent: Double): String = "%+.1f%%".format(percent)

    private fun formatValue(value: Double, unit: String): String = if (unit == "ms" && value >= MILLIS_PER_SECOND) {
        "%.2fs".format(value / MILLIS_PER_SECOND)
    } else {
        "%.0f%s".format(value, unit)
    }

    private fun formatNumber(value: Double): String = "%.1f".format(value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val MILLIS_PER_SECOND = 1000.0
    private const val SHORT_REVISION = 7
}
