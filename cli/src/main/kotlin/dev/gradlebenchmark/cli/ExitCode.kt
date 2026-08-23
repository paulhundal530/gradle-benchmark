package dev.gradlebenchmark.cli

import dev.gradlebenchmark.core.ComparisonStatus

/**
 * Documented process exit codes.
 *
 * These are a public contract: CI wrappers branch on them, so they must never be
 * inferred by parsing console output, and their numeric values must stay stable.
 */
public enum class ExitCode(public val code: Int) {
    /** Benchmark completed and the result is acceptable under the configured policy. */
    SUCCESS(0),

    /** The benchmark itself failed, was incomplete, or produced unusable output. */
    BENCHMARK_ERROR(1),

    /** A regression was detected and enforcement was enabled. */
    REGRESSION(2),

    /** Invalid configuration or input, including an unreadable baseline file. */
    INVALID_INPUT(3),

    /** The two results were not safely comparable. */
    INCOMPATIBLE(4),
}

/**
 * The single place a domain status becomes a process exit code.
 *
 * [failOnRegression] is the only lever mapping a verdict to failure, and it applies to
 * exactly one status. In particular:
 *
 * - [ComparisonStatus.INCOMPATIBLE] always fails, regardless of [failOnRegression]. It is
 *   an inability to conclude, not a regression, so suppressing it would report "no
 *   regression found" when nothing was actually compared.
 * - [ComparisonStatus.ERROR] always fails. An invalid benchmark must never be silently
 *   passable.
 */
public fun exitCodeFor(status: ComparisonStatus, failOnRegression: Boolean): ExitCode = when (status) {
    ComparisonStatus.ERROR -> ExitCode.BENCHMARK_ERROR
    ComparisonStatus.INCOMPATIBLE -> ExitCode.INCOMPATIBLE
    ComparisonStatus.REGRESSION_PRESENT ->
        if (failOnRegression) ExitCode.REGRESSION else ExitCode.SUCCESS
    ComparisonStatus.INCONCLUSIVE -> ExitCode.SUCCESS
    ComparisonStatus.PASS -> ExitCode.SUCCESS
}
