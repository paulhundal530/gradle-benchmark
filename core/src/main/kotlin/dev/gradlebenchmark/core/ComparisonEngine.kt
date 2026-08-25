package dev.gradlebenchmark.core

/**
 * Compares one baseline against many candidates.
 *
 * A single engine serves all three workflows, because they differ only in where the two
 * sides come from:
 *
 * - variant: baseline and candidates are scenarios within one run
 * - historical and revision: baseline and candidates are the same scenario in two runs
 *
 * The baseline is always named explicitly. It is comparison policy, not a property of the
 * measurements, and Gradle Profiler has no reason to know which scenario is the control.
 */
public object ComparisonEngine {

    /**
     * Compares every other scenario in [run] against the one named [baselineScenario].
     *
     * @throws IllegalArgumentException when the baseline is absent, which validation should
     *   already have caught long before a benchmark was run.
     */
    public fun compareVariants(
        run: BenchmarkRun,
        baselineScenario: String,
        policy: ComparisonPolicy = ComparisonPolicy(),
        timestamp: String = run.timestamp,
    ): BenchmarkComparison {
        val baseline = run.scenarios.firstOrNull { it.name == baselineScenario }
            ?: throw IllegalArgumentException(
                "Baseline scenario '$baselineScenario' is not in this run. " +
                    "Found: " + run.scenarios.joinToString(", ") { it.name },
            )

        val candidates = run.scenarios.filter { it.name != baselineScenario }

        val comparisons = candidates.mapNotNull { candidate ->
            compareScenario(baseline, candidate, policy)
        }

        return BenchmarkComparison(
            toolVersion = run.toolVersion,
            timestamp = timestamp,
            mode = ComparisonMode.VARIANT,
            comparisonPolicy = policy,
            baseline = ComparisonSide(
                runId = run.runId,
                scenarioName = baseline.name,
                timestamp = run.timestamp,
            ),
            overallComparisonStatus = rollUp(comparisons.map { it.verdict.status }),
            scenarios = comparisons,
            diagnostics = diagnose(run, policy),
        )
    }

    private fun compareScenario(
        baseline: ScenarioRun,
        candidate: ScenarioRun,
        policy: ComparisonPolicy,
    ): ScenarioComparison? {
        val baselineMeasurement = baseline.measurement(policy.measurement) ?: return null
        val candidateMeasurement = candidate.measurement(policy.measurement) ?: return null

        val observation = Observation.of(
            baseline = baselineMeasurement.values,
            candidate = candidateMeasurement.values,
            unit = candidateMeasurement.unit,
            statistic = policy.statistic,
        )

        return ScenarioComparison(
            name = candidate.name,
            title = candidate.title,
            observation = observation,
            verdict = RegressionPolicy.judge(observation, policy),
            workloadDelta = workloadDelta(baseline, candidate),
        )
    }

    /**
     * Differences in the system under measurement.
     *
     * Never blocks. In variant mode these are usually the point of the experiment; in
     * historical mode they are the most likely explanation for a change.
     */
    internal fun workloadDelta(baseline: ScenarioRun, candidate: ScenarioRun): Map<String, List<String>> = buildMap {
        val before = baseline.workloadConfiguration
        val after = candidate.workloadConfiguration

        if (before.gradleVersion != after.gradleVersion) {
            put("gradleVersion", listOf(before.gradleVersion.orEmpty(), after.gradleVersion.orEmpty()))
        }
        if (before.buildJvmVersion != after.buildJvmVersion) {
            put(
                "buildJvmVersion",
                listOf(before.buildJvmVersion.orEmpty(), after.buildJvmVersion.orEmpty()),
            )
        }
    }

    /**
     * Notes about the quality of the experiment rather than its outcome.
     *
     * Warm-up that never converged inflates every measurement after it, and no amount of
     * careful comparison recovers from that. Saying so is more useful than a confident
     * verdict over bad data.
     */
    internal fun diagnose(run: BenchmarkRun, policy: ComparisonPolicy): List<String> = buildList {
        run.scenarios.forEach { scenario ->
            val measurement = scenario.measurement(policy.measurement) ?: return@forEach

            if (!measurement.warmUpConverged()) {
                val last = measurement.warmUpValues.last()
                add(
                    "%s: warm-up had not converged (last warm-up %.0f%s against a median of %.0f%s). "
                        .format(
                            scenario.name,
                            last,
                            measurement.unit,
                            measurement.statistics.median,
                            measurement.unit,
                        ) +
                        "Measurements are likely inflated; consider more warm-ups.",
                )
            }

            if (measurement.values.size < MINIMUM_USEFUL_ITERATIONS) {
                add(
                    "%s: only %d measured iterations, so small differences cannot be resolved."
                        .format(scenario.name, measurement.values.size),
                )
            }
        }
    }

    /**
     * Below this, a run cannot separate a real change from an unlucky build.
     *
     * Chosen to match the point at which a rank-based test could begin to conclude, so the
     * guidance stays consistent once one is implemented.
     */
    internal const val MINIMUM_USEFUL_ITERATIONS: Int = 4
}
