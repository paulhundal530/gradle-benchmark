package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import dev.gradlebenchmark.engine.BenchmarkExecutionResult
import dev.gradlebenchmark.engine.BenchmarkExecutor
import dev.gradlebenchmark.engine.BenchmarkRequest
import dev.gradlebenchmark.engine.GradleProfiler
import dev.gradlebenchmark.engine.ProcessGradleProfiler
import dev.gradlebenchmark.engine.ScenarioInspector
import dev.gradlebenchmark.engine.ScenarioValidator
import dev.gradlebenchmark.engine.ValidationRequest
import java.nio.file.Path

private const val MILESTONE_NOTICE =
    "Benchmark execution is not wired up yet; selection has been validated but nothing was measured."

private const val COMPARISON_NOTICE =
    "A baseline scenario was given, but comparison arrives in a later milestone; " +
        "no comparison.json or report.html was produced."

/** Root command. Holds no behavior of its own beyond dispatching to a subcommand. */
public class GradleBenchmarkCommand : CliktCommand(name = "gradle-benchmark") {
    override fun help(context: Context): String = "Regression testing for Gradle build performance."

    override fun run(): Unit = Unit
}

/** Options shared by every subcommand that writes artifacts. */
public abstract class ArtifactWritingCommand(
    name: String,
    /**
     * How to obtain a profiler for a given executable path.
     *
     * Injected so command behavior can be tested without spawning real processes, while
     * production still gets the real thing by default.
     */
    private val profilerFactory: (String) -> GradleProfiler = { ProcessGradleProfiler(it) },
) : CliktCommand(name = name) {
    internal val outputDir: Path? by option(
        "--output-dir",
        help = "Directory for generated artifacts (default: build/gradle-benchmark).",
    ).path()

    internal val paths: OutputPaths get() = OutputPaths.resolve(outputDir)

    internal val gradleProfilerExecutable: String by option(
        "--gradle-profiler",
        help = "Path to the gradle-profiler executable.",
    ).default(ProcessGradleProfiler.DEFAULT_EXECUTABLE)

    internal fun profiler(): GradleProfiler = profilerFactory(gradleProfilerExecutable)

    internal fun validator(): ScenarioValidator = ScenarioValidator(ScenarioInspector(profiler()))
}

/**
 * Validates scenario selection and the surrounding environment without benchmarking.
 *
 * Cheap to run, so it can catch a bad scenario name before a build spends minutes
 * measuring the wrong thing.
 */
public class ValidateCommand(profilerFactory: (String) -> GradleProfiler = { ProcessGradleProfiler(it) }) :
    ArtifactWritingCommand(name = "validate", profilerFactory = profilerFactory) {
    override fun help(context: Context): String = "Validate scenario selection, configuration, and required tooling."

    internal val scenarioDir: Path? by option(
        "--scenario-dir",
        help = "Directory searched for .scenarios files.",
    ).path()

    internal val scenarioFile: Path? by option(
        "--scenario-file",
        help = "Scenario file to select, relative to --scenario-dir when both are given.",
    ).path()

    internal val scenarioGroup: String? by option(
        "--scenario-group",
        help = "Gradle Profiler scenario group to narrow the selection to.",
    )

    internal val projectDir: Path? by option(
        "--project-dir",
        help = "Directory containing the build under test.",
    ).path()

    internal val baselineScenario: String? by option(
        "--baseline-scenario",
        help = "Check that this scenario is one the selection will actually run.",
    )

    override fun run() {
        val selection = reportOrExit(
            validator().validate(
                ValidationRequest(
                    scenarioDir = scenarioDir,
                    scenarioFile = scenarioFile,
                    scenarioGroup = scenarioGroup,
                    projectDir = projectDir,
                    baselineScenario = baselineScenario,
                ),
            ),
        )
        describe(selection)
    }
}

/** Executes a benchmark, and compares variants when a baseline scenario is named. */
public class RunCommand(profilerFactory: (String) -> GradleProfiler = { ProcessGradleProfiler(it) }) :
    ArtifactWritingCommand(name = "run", profilerFactory = profilerFactory) {
    override fun help(context: Context): String =
        "Run a benchmark and write run.json, plus a comparison when variants are compared."

    internal val scenarioDir: Path? by option(
        "--scenario-dir",
        help = "Directory searched for .scenarios files.",
    ).path()

    internal val scenarioFile: Path? by option(
        "--scenario-file",
        help = "Scenario file to select, relative to --scenario-dir when both are given.",
    ).path()

    internal val scenarioGroup: String? by option(
        "--scenario-group",
        help = "Gradle Profiler scenario group to narrow the selection to.",
    )
    internal val projectDir: Path? by option(
        "--project-dir",
        help = "Directory containing the build under test.",
    ).path()

    internal val baselineScenario: String? by option(
        "--baseline-scenario",
        help = "Name of the scenario treated as the control. Every other scenario is " +
            "compared against it. Without this, no comparison is produced.",
    )

    internal val regressionThresholdPercent: Double by option(
        "--regression-threshold-percent",
        help = "A candidate exceeding the baseline by more than this percentage regresses.",
    ).double().default(DEFAULT_THRESHOLD_PERCENT)

    /** Tri-state: unset means "use the mode default", so an explicit choice always wins. */
    internal val failOnRegressionFlag: Boolean? by option(
        help = "Exit non-zero when a regression is detected. Defaults to false for " +
            "variant comparison, which is exploratory.",
    ).switch(
        "--fail-on-regression" to true,
        "--no-fail-on-regression" to false,
    )

    internal val gradleUserHome: Path? by option(
        "--gradle-user-home",
        help = "Gradle user home for the builds under measurement. Defaults to a directory " +
            "under --output-dir; point it somewhere stable to avoid re-downloading Gradle.",
    ).path()

    /**
     * Timeout is opt-in and unbounded by default: benchmarks on large repositories
     * legitimately run for a long time, and CI already provides job-level limits.
     */
    internal val timeoutMinutes: Double? by option(
        "--timeout-minutes",
        help = "Abort the benchmark after this many minutes. Unbounded when unset.",
    ).double()

    internal val mode: ComparisonMode get() = ComparisonMode.VARIANT

    override fun run() {
        // Validate before anything expensive. A misspelled baseline should cost a tenth of
        // a second, not the minutes it takes to measure the wrong thing and discard it.
        val selection = reportOrExit(
            validator().validate(
                ValidationRequest(
                    scenarioDir = scenarioDir,
                    scenarioFile = scenarioFile,
                    scenarioGroup = scenarioGroup,
                    projectDir = projectDir,
                    baselineScenario = baselineScenario,
                ),
            ),
        )
        describe(selection)

        val executor = BenchmarkExecutor(profiler())
        val execution = executor.execute(
            BenchmarkRequest(
                scenarioFile = selection.scenarioFile,
                outputDir = paths.outputDir,
                scenarioGroup = selection.group,
                projectDir = projectDir,
                gradleUserHome = gradleUserHome,
            ),
        )

        when (execution) {
            is BenchmarkExecutionResult.Failed -> {
                echo(execution.summary, err = true)
                execution.detail?.lines()?.forEach { echo("  $it", err = true) }
                echo("", err = true)
                // Raw output is kept even on failure; it is usually the only way to find
                // out why the build under test did not complete.
                echo("Full Gradle Profiler log:", err = true)
                echo("  ${execution.logFile}", err = true)
                exitWith(ExitCode.BENCHMARK_ERROR)
            }

            is BenchmarkExecutionResult.Completed -> {
                val measured = execution.benchmark.scenarios.sumOf { it.measuredIterations.size }
                echo("")
                echo(
                    "Benchmark completed: ${execution.benchmark.scenarios.size} scenarios, " +
                        "$measured measured iterations",
                )
                echo("")
                echo("Raw result:")
                echo("  ${execution.rawBenchmarkJson}")
                if (baselineScenario != null) {
                    echo("", err = true)
                    echo(COMPARISON_NOTICE, err = true)
                }
            }
        }
    }
}

/** Compares two normalized run files. Both are required; history is not its concern. */
public class CompareCommand(profilerFactory: (String) -> GradleProfiler = { ProcessGradleProfiler(it) }) :
    ArtifactWritingCommand(name = "compare", profilerFactory = profilerFactory) {
    override fun help(context: Context): String = "Compare two compatible run.json files and write comparison.json."

    internal val baseline: Path by option(
        "--baseline",
        help = "run.json treated as the baseline.",
    ).path().required()

    internal val candidate: Path by option(
        "--candidate",
        help = "run.json treated as the candidate.",
    ).path().required()

    internal val regressionThresholdPercent: Double by option(
        "--regression-threshold-percent",
        help = "A candidate exceeding the baseline by more than this percentage regresses.",
    ).double().default(DEFAULT_THRESHOLD_PERCENT)

    /** Tri-state: unset means "use the mode default", so an explicit choice always wins. */
    internal val failOnRegressionFlag: Boolean? by option(
        help = "Exit non-zero when a regression is detected. Defaults to true here, " +
            "because historical and revision comparison exist to gate on regressions.",
    ).switch(
        "--fail-on-regression" to true,
        "--no-fail-on-regression" to false,
    )

    internal val mode: ComparisonMode get() = ComparisonMode.HISTORICAL

    override fun run() {
        echo("baseline:        $baseline")
        echo("candidate:       $candidate")
        echo("threshold:       $regressionThresholdPercent%")
        echo("fail-on-regression: ${mode.resolveFailOnRegression(failOnRegressionFlag)}")
        echo(MILESTONE_NOTICE, err = true)
    }
}

/**
 * Conventional `help` subcommand, so `gradle-benchmark help run` works alongside
 * `gradle-benchmark run --help`.
 *
 * Implemented by re-parsing against a fresh command tree rather than reaching into
 * Clikt's context internals, which keeps it robust across Clikt versions.
 */
public class HelpCommand : CliktCommand(name = "help") {
    override fun help(context: Context): String = "Show usage for the tool or a command."

    internal val commandPath: List<String> by argument(
        name = "command",
        help = "Command to show usage for. Shows top-level usage when omitted.",
    ).multiple()

    override fun run() {
        echo(helpTextFor(commandPath))
    }
}

internal const val DEFAULT_THRESHOLD_PERCENT: Double = 5.0

/**
 * Renders the help text Clikt would print for [commandPath].
 *
 * The path is resolved explicitly first. Delegating straight to `--help` would silently
 * fall back to top-level usage for an unknown command, because Clikt handles the eager
 * `--help` option before it resolves the subcommand.
 */
internal fun helpTextFor(commandPath: List<String>): String {
    val tree = buildCommandTree()

    var current: com.github.ajalt.clikt.core.BaseCliktCommand<*> = tree
    for (segment in commandPath) {
        current = current.registeredSubcommands().find { it.commandName == segment }
            ?: throw UsageError("no such command: ${commandPath.joinToString(" ")}")
    }

    return try {
        tree.parse(commandPath + "--help")
        error("Clikt did not raise a help message for --help")
    } catch (help: PrintHelpMessage) {
        help.context?.command?.getFormattedHelp().orEmpty()
    }
}

/** Assembles the command tree. */
public fun buildCommandTree(): CliktCommand = GradleBenchmarkCommand().subcommands(
    ValidateCommand(),
    RunCommand(),
    CompareCommand(),
    HelpCommand(),
)
