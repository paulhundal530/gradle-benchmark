package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Payloads here are copied verbatim from gradle-profiler 0.25.2 so the parsers are tested
 * against what the tool actually prints rather than against an idealised format.
 */
class ScenarioInspectorTest {

    private val realDump = """

        # Scenario 1/2 'Baseline'
        baseline {
            iterations=2
            tasks=[
                work
            ]
            title=Baseline
            warm-ups=1
        }

        # Scenario 2/2 'Configuration cache'
        cc-enabled {
            gradle-args=[
                "--configuration-cache"
            ]
            iterations=2
            tasks=[
                work
            ]
            title="Configuration cache"
            warm-ups=1
        }
    """.trimIndent()

    @Test
    fun `scenario names are the top level block keys`() {
        assertThat(ScenarioInspector.parseScenarioNames(realDump))
            .containsExactly("baseline", "cc-enabled")
    }

    @Test
    fun `nested configuration blocks are not mistaken for scenarios`() {
        val dump = """
            outer {
                nested {
                    key=value
                }
            }
        """.trimIndent()

        assertThat(ScenarioInspector.parseScenarioNames(dump)).containsExactly("outer")
    }

    @Test
    fun `an unknown group message keeps the available groups and drops the stack trace`() {
        val output = """
            java.lang.IllegalArgumentException: Unknown scenario group 'nope' requested. Available groups are: nightly
            	at org.gradle.profiler.ScenarioLoader.selectScenariosFromGroup(ScenarioLoader.java:726)
            	at org.gradle.profiler.Main.main(Main.java:37)
        """.trimIndent()

        val message = ScenarioInspector.extractProfilerMessage(output)

        assertThat(message).isEqualTo(
            "Unknown scenario group 'nope' requested. Available groups are: nightly",
        )
        assertThat(message).doesNotContain("at org.gradle.profiler")
        assertThat(message).doesNotContain("IllegalArgumentException")
    }

    @Test
    fun `a config exception with a dollar sign in the class name is still stripped`() {
        val output = """
            com.typesafe.config.ConfigException${'$'}IO: /repo/benchmarks/nope.scenarios: java.io.FileNotFoundException
            	at com.typesafe.config.impl.Parseable.parseValue(Parseable.java:190)
        """.trimIndent()

        assertThat(ScenarioInspector.extractProfilerMessage(output))
            .startsWith("/repo/benchmarks/nope.scenarios")
            .doesNotContain("ConfigException")
    }

    @Test
    fun `a failure with no output still says something`() {
        assertThat(ScenarioInspector.extractProfilerMessage("   \n  \n"))
            .isEqualTo("Gradle Profiler failed without reporting a reason.")
    }

    @Test
    fun `a successful dump resolves its scenario names`() {
        val inspector = ScenarioInspector(FakeGradleProfiler(exitCode = 0, stdout = realDump))

        val result = inspector.inspect(Path.of("build.scenarios"))

        assertThat(result).isEqualTo(InspectionResult.Resolved(listOf("baseline", "cc-enabled")))
    }

    @Test
    fun `a profiler failure is surfaced as a rejection carrying its message`() {
        val profiler = FakeGradleProfiler(
            exitCode = 1,
            stderr = "java.lang.IllegalArgumentException: Unknown scenario 'x' requested. " +
                "Available scenarios are: baseline",
        )

        val result = inspector(profiler).inspect(Path.of("build.scenarios"))

        assertThat(result).isInstanceOf(InspectionResult.Rejected::class.java)
        assertThat((result as InspectionResult.Rejected).reason)
            .contains("Available scenarios are: baseline")
    }

    @Test
    fun `an empty but successful dump is a rejection rather than an empty benchmark`() {
        val result = inspector(FakeGradleProfiler(exitCode = 0, stdout = "")).inspect(
            Path.of("build.scenarios"),
            group = "nightly",
        )

        assertThat(result).isInstanceOf(InspectionResult.Rejected::class.java)
        assertThat((result as InspectionResult.Rejected).reason).contains("nightly")
    }

    @Test
    fun `group and project directory are passed through to the profiler`() {
        val profiler = FakeGradleProfiler(exitCode = 0, stdout = realDump)

        inspector(profiler).inspect(
            scenarioFile = Path.of("build.scenarios"),
            group = "nightly",
            projectDir = Path.of("/repo"),
        )

        assertThat(profiler.lastArguments).containsSequence("--group", "nightly")
        assertThat(profiler.lastArguments).containsSequence("--project-dir", "/repo")
        assertThat(profiler.lastArguments).contains("--dump-scenarios")
    }

    @Test
    fun `validation never asks the profiler to run builds`() {
        val profiler = FakeGradleProfiler(exitCode = 0, stdout = realDump)

        inspector(profiler).inspect(Path.of("build.scenarios"))

        assertThat(profiler.lastArguments).doesNotContain("--dry-run")
    }

    private fun inspector(profiler: GradleProfiler) = ScenarioInspector(profiler)
}

class BenchmarkFailureSummaryTest {

    /** Verbatim shape of a real failed benchmark: pages of progress, one real error. */
    private val realFailureOutput = """
        * Writing results to /tmp/out/raw
        * Settings
        Project dir: /repo
        Output dir: /tmp/out/raw
        Profiler: none
        Benchmark: true
        Gradle User Home: /repo/gradle-user-home
        * Inspecting the build using its default Gradle version
        * Stopping daemons
        * Scenarios
        Scenario: Broken using Gradle 8.14.3
          Run: run tasks noSuchTaskExistsHere
          Warm-ups: 1
          Builds: 2
        * Running scenario Broken using Gradle 8.14.3 (scenario 1/1)
        * Running warm-up build #1
        ERROR: failed to run build. See log file for details.
        * Stopping daemons
        org.gradle.tooling.BuildException: Could not execute build using connection to Gradle installation '/repo/gradle'
    """.trimIndent()

    @Test
    fun `only the lines describing the failure are kept`() {
        val summary = ScenarioInspector.summarizeBenchmarkFailure(realFailureOutput)

        assertThat(summary).contains("failed to run build")
        assertThat(summary).contains("Could not execute build")
    }

    @Test
    fun `progress logging and settings dumps are discarded`() {
        val summary = ScenarioInspector.summarizeBenchmarkFailure(realFailureOutput)

        assertThat(summary).doesNotContain("Gradle User Home")
        assertThat(summary).doesNotContain("Inspecting the build")
        assertThat(summary).doesNotContain("Stopping daemons")
    }

    @Test
    fun `the summary stays short enough to belong on a console`() {
        val summary = ScenarioInspector.summarizeBenchmarkFailure(realFailureOutput)

        assertThat(summary.lines()).hasSizeLessThanOrEqualTo(4)
    }

    @Test
    fun `exception class names are stripped from the summary`() {
        val summary = ScenarioInspector.summarizeBenchmarkFailure(realFailureOutput)

        assertThat(summary).doesNotContain("org.gradle.tooling.BuildException:")
    }

    @Test
    fun `output with no recognisable error falls back to the tail`() {
        val summary = ScenarioInspector.summarizeBenchmarkFailure("one\ntwo\nthree\nfour\nfive")

        assertThat(summary).contains("five")
    }

    @Test
    fun `empty output still says something`() {
        assertThat(ScenarioInspector.summarizeBenchmarkFailure("  \n \n"))
            .isEqualTo("Gradle Profiler failed without reporting a reason.")
    }
}

/** Records what it was asked to run and replays a canned result. */
class FakeGradleProfiler(private val exitCode: Int, private val stdout: String = "", private val stderr: String = "") :
    GradleProfiler {
    var lastArguments: List<String> = emptyList()
        private set

    override fun invoke(arguments: List<String>): ProfilerInvocation {
        lastArguments = arguments
        return ProfilerInvocation(exitCode, stdout, stderr)
    }
}
