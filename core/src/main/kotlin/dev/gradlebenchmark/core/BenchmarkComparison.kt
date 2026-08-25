package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable

/**
 * What two sets of measurements show, persisted as `comparison.json`.
 *
 * Reports; does not judge. There is no status, no threshold and no notion of an acceptable
 * result, because deciding what a difference means requires knowing what the scenario is
 * for, and the tool does not.
 *
 * A team wanting a gate can write one over this file, with a threshold they chose for a
 * scenario they understand.
 */
@Serializable
public data class BenchmarkComparison(
    val schemaVersion: Int = SCHEMA_VERSION,
    val toolVersion: String,
    val timestamp: String,
    val mode: ComparisonMode,
    /** Which measured quantity was compared, and how it was summarized. */
    val measurement: String,
    val statistic: Statistic,
    val baseline: ComparisonSide,
    val candidate: ComparisonSide? = null,
    val scenarios: List<ScenarioComparison>,
    /**
     * Notes about the quality of the experiment rather than its outcome.
     *
     * Warm-up that never converged inflates everything after it, and no comparison recovers
     * from that. Saying so is more useful than reporting a confident number over bad data.
     */
    val diagnostics: List<String> = emptyList(),
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Which comparison was performed.
 *
 * Affects wording only. The engine is identical: one baseline, one or more candidates.
 */
@Serializable
public enum class ComparisonMode {
    /** Scenarios within one run, such as configuration cache on versus off. */
    VARIANT,

    /** The same scenarios measured in two separate runs. */
    RUNS,
}

/** Identifies one side of a comparison. */
@Serializable
public data class ComparisonSide(
    val runId: String,
    val scenarioName: String? = null,
    val timestamp: String? = null,
    val revision: String? = null,
)

/** One candidate measured against the baseline. */
@Serializable
public data class ScenarioComparison(
    val name: String,
    val title: String? = null,
    val observation: Observation,
    /**
     * Everything that differed between the two sides, field by field.
     *
     * Never a reason to refuse. Comparing configuration-cache-enabled against disabled is
     * precisely a comparison where arguments differ, and that difference is the point of
     * the experiment. Reporting them prominently is what lets a user notice when a
     * comparison is not meaningful, rather than the tool deciding for them.
     */
    val differences: List<Difference> = emptyList(),
)

/** One field that differs between the compared sides. */
@Serializable
public data class Difference(
    val field: String,
    val baseline: String,
    val candidate: String,
    /**
     * Whether this difference changes the work being done rather than how it is configured.
     *
     * A different task list means the two sides are not the same experiment, which is worth
     * saying more loudly than a Gradle version bump. Still never a refusal.
     */
    val changesWorkPerformed: Boolean = false,
)
