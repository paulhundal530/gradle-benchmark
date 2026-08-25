package dev.gradlebenchmark.cli

/**
 * Documented process exit codes.
 *
 * These are a public contract: CI wrappers branch on them, so they must never be inferred
 * by parsing console output, and their numeric values must stay stable.
 *
 * There is deliberately no code meaning "regression". The tool reports what it measured and
 * does not decide whether a difference is acceptable; a team wanting to fail a build on a
 * threshold can read `comparison.json` and apply one they chose.
 */
public enum class ExitCode(public val code: Int) {
    /** The benchmark ran and results were produced. */
    SUCCESS(0),

    /** The benchmark itself failed, was incomplete, or produced unusable output. */
    BENCHMARK_ERROR(1),

    /** Invalid configuration or input, including an unreadable run file. */
    INVALID_INPUT(3),
}
