package dev.gradlebenchmark.cli

import dev.gradlebenchmark.engine.GradleProfiler
import dev.gradlebenchmark.engine.ProfilerInvocation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Stands in for the real executable, answering both calls the CLI makes: resolving
 * scenarios with `--dump-scenarios`, and benchmarking with `--benchmark`.
 *
 * A benchmark run writes a report shaped like the one gradle-profiler 0.25.2 embeds in its
 * HTML, because that is what the extractor reads.
 */
class FakeProfiler(
    private val scenarioNames: List<String> = listOf("baseline", "cc-enabled"),
    private val benchmarkExitCode: Int = 0,
    private val measuredIterations: Int = 2,
    private val writesReport: Boolean = true,
    /** When set, this scenario measures markedly slower so a regression can be exercised. */
    private val slowScenario: String? = null,
) : GradleProfiler {

    var lastArguments: List<String> = emptyList()
        private set

    override fun invoke(arguments: List<String>): ProfilerInvocation {
        lastArguments = arguments

        if ("--dump-scenarios" in arguments) {
            val dump = scenarioNames.joinToString("\n") { "$it {\n    tasks=[work]\n}" }
            return ProfilerInvocation(0, dump, "")
        }

        if (writesReport) {
            val rawDir = Path.of(arguments[arguments.indexOf("--output-dir") + 1])
            rawDir.createDirectories()
            rawDir.resolve("benchmark.html").writeText(report())
        }
        return ProfilerInvocation(
            benchmarkExitCode,
            "",
            if (benchmarkExitCode == 0) "" else "java.lang.RuntimeException: build failed",
        )
    }

    private fun report(): String {
        val scenarios = scenarioNames.joinToString(",") { name ->
            val base = if (name == slowScenario) 30.0 else 10.0
            val iterations = (1..measuredIterations).joinToString(",") { index ->
                // A little variation, so the interval is not degenerate.
                val value = base + (index % 2) * 0.2
                """{"phase":"MEASURE","iteration":$index,"values":{"total execution time":$value}}"""
            }
            """
            {"definition":{"name":"$name","tasks":"work","version":"8.14.3","args":[]},
             "samples":[{"name":"total execution time","unit":"ms"}],
             "iterations":[$iterations]}
            """.trimIndent()
        }
        return """
            <script>
            const benchmarkResult =
            {"environment":{"profilerVersion":"0.25.2"},"scenarios":[$scenarios]}
            ;</script>
        """.trimIndent()
    }
}

/** Factory shape the commands take, ignoring the configured executable path. */
fun profilerFactoryOf(profiler: GradleProfiler): (String) -> GradleProfiler = { profiler }
