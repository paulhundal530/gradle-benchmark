package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable

/**
 * One valid benchmark execution, normalized.
 *
 * This is a first-class product artifact, persisted as `run.json`, and the stable contract
 * other tools read. Gradle Profiler's own output is source data kept for debugging; nothing
 * downstream should have to parse it, or the console, or the HTML report.
 *
 * It carries enough to understand what ran, reproduce it, and decide whether another run is
 * comparable.
 */
@Serializable
public data class BenchmarkRun(
    val schemaVersion: Int = SCHEMA_VERSION,
    val toolVersion: String,
    val runId: String,
    val timestamp: String,
    val revision: Revision? = null,
    val executionEnvironment: ExecutionEnvironment,
    val scenarios: List<ScenarioRun>,
) {
    public companion object {
        /**
         * Incremented only for a breaking change.
         *
         * The schema is built so the foreseeable additions are not breaking: statistics
         * beyond the median, measurements beyond build execution time, and a statistical
         * regression method are all additive.
         */
        public const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Where the benchmark ran.
 *
 * A difference here invalidates comparison: the same build measured on different hardware
 * produces numbers that cannot be meaningfully subtracted.
 */
@Serializable
public data class ExecutionEnvironment(
    val operatingSystem: String? = null,
    val architecture: String? = null,
    val cpuCores: Int? = null,
    val maxMemoryBytes: Long? = null,
)

/** Which revision of the project was measured. Recorded now, interpreted from Milestone 10. */
@Serializable
public data class Revision(val commit: String? = null, val branch: String? = null, val dirty: Boolean = false)

/**
 * How the measurements were collected.
 *
 * Distinct from both identity and configuration: it describes the experiment rather than
 * the work or the system under test. Comparing a median of ten iterations against a median
 * of three is not invalid so much as less confident, and confidence is something this
 * version cannot express.
 */
@Serializable
public data class MeasurementProtocol(
    val warmUpCount: Int,
    val measuredIterationCount: Int,
    val profilerVersion: String? = null,
)

/** One scenario's normalized result. */
@Serializable
public data class ScenarioRun(
    val name: String,
    val title: String? = null,
    /** Fingerprint of [workloadIdentity]; a mismatch means the runs measure different work. */
    val workloadIdentityHash: String,
    /**
     * Recorded alongside the hash so an incompatible comparison can name the field that
     * differed. A bare "hashes differ" would be true and useless.
     */
    val workloadIdentity: WorkloadIdentity,
    val workloadConfiguration: WorkloadConfiguration,
    val measurementProtocol: MeasurementProtocol,
    val measurements: List<MeasurementResult>,
) {
    public fun measurement(name: String): MeasurementResult? = measurements.firstOrNull { it.name == name }
}

/**
 * One measured quantity across the measured iterations of a scenario.
 *
 * [values] is retained alongside [statistics] because a statistical regression method needs
 * the raw samples, not a summary. Keeping them costs little and cannot be recovered later.
 */
@Serializable
public data class MeasurementResult(
    val name: String,
    val unit: String,
    val statistics: Statistics,
    val values: List<Double>,
    /**
     * Warm-up values, in order, excluded from [statistics].
     *
     * Retained because they are the only evidence that warm-up converged. A series still
     * falling at the last warm-up means the measured window began before the system had
     * settled, which inflates every number after it. Nothing else in the run can show that.
     */
    val warmUpValues: List<Double> = emptyList(),
) {
    /**
     * Whether warm-up appears to have converged.
     *
     * A crude but useful check: if the last warm-up is still more than [tolerance] above the
     * measured median, the system was probably still settling when measurement began.
     */
    public fun warmUpConverged(tolerance: Double = 0.05): Boolean {
        val lastWarmUp = warmUpValues.lastOrNull() ?: return true
        if (statistics.median <= 0.0) return true
        return (lastWarmUp - statistics.median) / statistics.median <= tolerance
    }
}

/**
 * Normalizes measurement units.
 *
 * Durations are converted to milliseconds so nothing downstream has to ask what unit it is
 * looking at. Not every sample is a duration, though: local build cache size is reported in
 * bytes, so unrecognized units pass through with the value untouched and the unit recorded.
 */
public object MeasurementUnits {

    public const val MILLISECONDS: String = "ms"

    private val TO_MILLIS: Map<String, Double> = mapOf(
        "ms" to 1.0,
        "millis" to 1.0,
        "s" to 1_000.0,
        "sec" to 1_000.0,
        "us" to 0.001,
        "µs" to 0.001,
        "ns" to 0.000_001,
    )

    public fun isDuration(unit: String): Boolean = unit.lowercase() in TO_MILLIS

    /** Returns the values converted to milliseconds, or unchanged when not a duration. */
    public fun normalize(values: List<Double>, unit: String): NormalizedValues {
        val factor = TO_MILLIS[unit.lowercase()]
            ?: return NormalizedValues(values, unit)

        return if (factor == 1.0) {
            NormalizedValues(values, MILLISECONDS)
        } else {
            NormalizedValues(values.map { it * factor }, MILLISECONDS)
        }
    }
}

/** Values with the unit they are expressed in after normalization. */
public data class NormalizedValues(val values: List<Double>, val unit: String)
