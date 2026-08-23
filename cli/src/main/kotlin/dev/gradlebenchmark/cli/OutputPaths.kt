package dev.gradlebenchmark.cli

import java.nio.file.Path

/**
 * Resolves the artifact paths a command writes.
 *
 * Gradle Benchmark's own artifacts are distinct from Gradle Profiler's raw output: the
 * raw directory is debug data, while `run.json` and `comparison.json` are the product's
 * machine-readable contract.
 */
public class OutputPaths(public val outputDir: Path) {

    /** Normalized record of one valid benchmark execution. */
    public val runJson: Path get() = outputDir.resolve("run.json")

    /** Interpretation of two compatible results. */
    public val comparisonJson: Path get() = outputDir.resolve("comparison.json")

    /** Human-facing report, rendered from the normalized model. */
    public val reportHtml: Path get() = outputDir.resolve("report.html")

    /** Preserved Gradle Profiler output, kept for debugging only. */
    public val rawDir: Path get() = outputDir.resolve("raw")

    public companion object {
        public val DEFAULT: Path = Path.of("build", "gradle-benchmark")

        /** Falls back to [DEFAULT] when the user did not pass `--output-dir`. */
        public fun resolve(outputDir: Path?): OutputPaths = OutputPaths(outputDir ?: DEFAULT)
    }
}
