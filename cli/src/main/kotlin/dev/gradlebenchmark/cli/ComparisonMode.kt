package dev.gradlebenchmark.cli

/**
 * How a comparison was invoked.
 *
 * Mode exists only to pick a *default* for `--fail-on-regression`. It must never be
 * branched on inside enforcement logic: there is one enforcement mechanism, and the
 * workflow only decides what it defaults to.
 */
public enum class ComparisonMode(public val failOnRegressionDefault: Boolean) {
    /**
     * `run --baseline-scenario <name>`: comparing variants inside a single benchmark run.
     *
     * Exploratory. A candidate that is slower than the baseline is a useful experimental
     * result, not a broken build, so enforcement is off unless asked for.
     */
    VARIANT(failOnRegressionDefault = false),

    /**
     * `compare --baseline <a> --candidate <b>`: comparing two separate runs.
     *
     * Used for nightly monitoring and revision comparison, whose entire purpose is
     * regression detection, so enforcement is on by default.
     */
    HISTORICAL(failOnRegressionDefault = true),
    ;

    /** Applies the user's explicit choice when given, otherwise this mode's default. */
    public fun resolveFailOnRegression(explicit: Boolean?): Boolean = explicit ?: failOnRegressionDefault
}
