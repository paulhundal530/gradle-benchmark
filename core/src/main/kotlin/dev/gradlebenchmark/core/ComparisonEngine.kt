package dev.gradlebenchmark.core

/**
 * Compares one baseline against one or more candidates.
 *
 * A single engine serves both workflows, because they differ only in where the two sides
 * come from:
 *
 * - variant: baseline and candidates are scenarios within one run
 * - runs: the same scenario measured in two separate runs, on two branches or commits
 *
 * The baseline is always named explicitly. It is the user's choice of control, not a
 * property of the measurements.
 */
public object ComparisonEngine {

    /** Compares every other scenario in [run] against the one named [baselineScenario]. */
    public fun compareVariants(
        run: BenchmarkRun,
        baselineScenario: String,
        measurement: String = DEFAULT_MEASUREMENT,
        statistic: Statistic = Statistic.MEDIAN,
    ): BenchmarkComparison {
        val baseline = run.scenarios.firstOrNull { it.name == baselineScenario }
            ?: throw IllegalArgumentException(
                "Baseline scenario '$baselineScenario' is not in this run. " +
                    "Found: " + run.scenarios.joinToString(", ") { it.name },
            )

        val comparisons = run.scenarios
            .filter { it.name != baselineScenario }
            .mapNotNull { candidate -> compare(baseline, candidate, measurement, statistic) }

        return BenchmarkComparison(
            toolVersion = run.toolVersion,
            timestamp = run.timestamp,
            mode = ComparisonMode.VARIANT,
            measurement = measurement,
            statistic = statistic,
            baseline = ComparisonSide(
                runId = run.runId,
                scenarioName = baseline.name,
                timestamp = run.timestamp,
                revision = run.revision?.commit,
            ),
            scenarios = comparisons,
            diagnostics = diagnose(run.scenarios, measurement),
        )
    }

    /**
     * Compares scenarios shared by two runs, matched by name.
     *
     * The case this tool mainly exists for: the same scenario measured on two branches or
     * commits. Scenarios present in only one run are skipped and noted, rather than treated
     * as an error, since a branch may legitimately add or remove one.
     */
    public fun compareRuns(
        baselineRun: BenchmarkRun,
        candidateRun: BenchmarkRun,
        measurement: String = DEFAULT_MEASUREMENT,
        statistic: Statistic = Statistic.MEDIAN,
    ): BenchmarkComparison {
        val baselineByName = baselineRun.scenarios.associateBy { it.name }
        val shared = candidateRun.scenarios.filter { it.name in baselineByName }

        val comparisons = shared.mapNotNull { candidate ->
            compare(baselineByName.getValue(candidate.name), candidate, measurement, statistic)
        }

        val onlyInOne = buildList {
            (candidateRun.scenarios.map { it.name } - baselineByName.keys).forEach {
                add("Scenario '$it' is only in the candidate run, so it was not compared.")
            }
            (baselineByName.keys - candidateRun.scenarios.map { it.name }.toSet()).forEach {
                add("Scenario '$it' is only in the baseline run, so it was not compared.")
            }
        }

        return BenchmarkComparison(
            toolVersion = candidateRun.toolVersion,
            timestamp = candidateRun.timestamp,
            mode = ComparisonMode.RUNS,
            measurement = measurement,
            statistic = statistic,
            baseline = ComparisonSide(
                runId = baselineRun.runId,
                timestamp = baselineRun.timestamp,
                revision = baselineRun.revision?.commit,
            ),
            candidate = ComparisonSide(
                runId = candidateRun.runId,
                timestamp = candidateRun.timestamp,
                revision = candidateRun.revision?.commit,
            ),
            scenarios = comparisons,
            diagnostics = environmentDiagnostics(baselineRun, candidateRun) +
                onlyInOne +
                diagnose(baselineRun.scenarios + candidateRun.scenarios, measurement),
        )
    }

    private fun compare(
        baseline: ScenarioRun,
        candidate: ScenarioRun,
        measurement: String,
        statistic: Statistic,
    ): ScenarioComparison? {
        val baselineMeasurement = baseline.measurement(measurement) ?: return null
        val candidateMeasurement = candidate.measurement(measurement) ?: return null

        return ScenarioComparison(
            name = candidate.name,
            title = candidate.title,
            observation = Observation.of(
                baseline = baselineMeasurement.values,
                candidate = candidateMeasurement.values,
                unit = candidateMeasurement.unit,
                statistic = statistic,
                baselineWarmUps = baselineMeasurement.warmUpValues.size,
                candidateWarmUps = candidateMeasurement.warmUpValues.size,
            ),
            differences = differences(baseline, candidate),
        )
    }

    /**
     * Every field that differs between the two sides.
     *
     * Reported, never a refusal. Differences that change the work being done are marked, so
     * a clean build compared against an incremental one reads as more than a footnote,
     * without the tool refusing to answer.
     */
    internal fun differences(baseline: ScenarioRun, candidate: ScenarioRun): List<Difference> = buildList {
        fun compareField(field: String, before: Any?, after: Any?, changesWork: Boolean = false) {
            if (before != after) {
                add(Difference(field, render(before), render(after), changesWork))
            }
        }

        val a = baseline.workloadIdentity
        val b = candidate.workloadIdentity

        // These change what is actually being measured.
        compareField("tasks", a.tasks, b.tasks, changesWork = true)
        compareField("action", a.action, b.action, changesWork = true)
        compareField("cleanup", a.cleanup, b.cleanup, changesWork = true)
        compareField("mutators", a.mutators, b.mutators, changesWork = true)
        compareField("invoker", a.invoker, b.invoker, changesWork = true)

        // These are usually the independent variable of the experiment.
        compareField("args", a.args, b.args)
        compareField("jvmArgs", a.jvmArgs, b.jvmArgs)
        compareField("systemProperties", a.systemProperties, b.systemProperties)
        compareField(
            "gradleVersion",
            baseline.workloadConfiguration.gradleVersion,
            candidate.workloadConfiguration.gradleVersion,
        )
        compareField(
            "buildJvmVersion",
            baseline.workloadConfiguration.buildJvmVersion,
            candidate.workloadConfiguration.buildJvmVersion,
        )
    }

    private fun render(value: Any?): String = when (value) {
        null -> ""
        is List<*> -> value.joinToString(", ", prefix = "[", postfix = "]")
        is Map<*, *> -> value.entries.joinToString(", ", prefix = "{", postfix = "}")
        else -> value.toString()
    }

    /** Machine differences, which make two sets of numbers hard to compare meaningfully. */
    internal fun environmentDiagnostics(baselineRun: BenchmarkRun, candidateRun: BenchmarkRun): List<String> =
        buildList {
            val a = baselineRun.executionEnvironment
            val b = candidateRun.executionEnvironment

            if (a.operatingSystem != b.operatingSystem || a.architecture != b.architecture) {
                add(
                    "These runs were measured on different machines " +
                        "(${a.operatingSystem} ${a.architecture} against " +
                        "${b.operatingSystem} ${b.architecture}). " +
                        "Absolute values are not comparable across hardware.",
                )
            } else if (a.cpuCores != b.cpuCores) {
                add(
                    "These runs were measured on machines with different core counts " +
                        "(${a.cpuCores} against ${b.cpuCores}).",
                )
            }
        }

    /** Whether the experiment itself was well formed, independent of what it showed. */
    internal fun diagnose(scenarios: List<ScenarioRun>, measurement: String): List<String> =
        scenarios.distinctBy { it.name to it.measurementProtocol }.flatMap { scenario ->
            val result = scenario.measurement(measurement) ?: return@flatMap emptyList()

            buildList {
                if (!result.warmUpConverged()) {
                    add(
                        "%s: warm-up had not converged (last warm-up %.0f%s against a median of %.0f%s). "
                            .format(
                                scenario.name,
                                result.warmUpValues.last(),
                                result.unit,
                                result.statistics.median,
                                result.unit,
                            ) + "Measurements are likely inflated; consider more warm-ups.",
                    )
                }
                if (result.values.size < MINIMUM_USEFUL_ITERATIONS) {
                    add(
                        "%s: only %d measured iterations, so only large differences can be resolved."
                            .format(scenario.name, result.values.size),
                    )
                }
            }
        }

    public const val DEFAULT_MEASUREMENT: String = "total execution time"

    /** Below this, a run cannot separate a real difference from an unlucky build. */
    internal const val MINIMUM_USEFUL_ITERATIONS: Int = 4
}
