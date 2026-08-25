package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable

/**
 * The interpretation of compatible benchmark results, persisted as `comparison.json`.
 *
 * This is the machine-readable artifact other tools ingest. Nothing downstream should have
 * to parse console output or the HTML report.
 */
@Serializable
public data class BenchmarkComparison(
    val schemaVersion: Int = SCHEMA_VERSION,
    val toolVersion: String,
    val timestamp: String,
    val mode: ComparisonMode,
    val comparisonPolicy: ComparisonPolicy,
    val baseline: ComparisonSide,
    val overallComparisonStatus: ComparisonStatus,
    val scenarios: List<ScenarioComparison>,
    /**
     * Run-level notes that are not about any one scenario, such as insufficient warm-up.
     *
     * Kept separate from verdicts because they describe the quality of the experiment
     * rather than its outcome.
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
 * Affects wording and enforcement defaults only. The engine is identical across all three:
 * one baseline, many candidates.
 */
@Serializable
public enum class ComparisonMode {
    /** Scenarios within one run, such as configuration cache on versus off. */
    VARIANT,

    /** Two runs of the same scenarios, separated in time. */
    HISTORICAL,

    /** Two runs of the same scenarios, at different revisions. */
    REVISION,
}

/** Identifies one side of a comparison. */
@Serializable
public data class ComparisonSide(val runId: String, val scenarioName: String? = null, val timestamp: String? = null)

/** One candidate measured against the baseline. */
@Serializable
public data class ScenarioComparison(
    val name: String,
    val title: String? = null,
    /** What was measured. Always present, and independent of any policy. */
    val observation: Observation,
    /** What the configured policy makes of it. */
    val verdict: Verdict,
    /**
     * Differences in the system under measurement, when there are any.
     *
     * A regression that arrives with "Gradle 9.1 to 9.2" attached is far more actionable
     * than the same percentage alone.
     */
    val workloadDelta: Map<String, List<String>> = emptyMap(),
)
