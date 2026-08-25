package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ComparisonEngineTest {

    private val steady = listOf(100.0, 101.0, 99.0, 100.0, 100.0, 101.0)

    private fun scenario(
        name: String,
        values: List<Double> = steady,
        warmUps: List<Double> = listOf(101.0),
        tasks: String = "assembleDebug",
        args: List<String> = emptyList(),
        gradleVersion: String? = "9.3.1",
        buildJvmVersion: String? = "21.0.11",
    ) = ScenarioRun(
        name = name,
        title = "Title of $name",
        workloadIdentityHash = "hash-$name",
        workloadIdentity = WorkloadIdentity(name = name, tasks = tasks, args = args),
        workloadConfiguration = WorkloadConfiguration(
            gradleVersion = gradleVersion,
            buildJvmVersion = buildJvmVersion,
        ),
        measurementProtocol = MeasurementProtocol(
            warmUpCount = warmUps.size,
            measuredIterationCount = values.size,
            profilerVersion = "0.25.2",
        ),
        measurements = listOf(
            MeasurementResult(
                name = ComparisonEngine.DEFAULT_MEASUREMENT,
                unit = "ms",
                statistics = Statistics.of(values),
                values = values,
                warmUpValues = warmUps,
            ),
        ),
    )

    private fun run(
        vararg scenarios: ScenarioRun,
        runId: String = "run-1",
        revision: String? = null,
        environment: ExecutionEnvironment = ExecutionEnvironment(
            operatingSystem = "Mac OS X",
            architecture = "aarch64",
            cpuCores = 10,
        ),
    ) = BenchmarkRun(
        toolVersion = "0.1.0",
        runId = runId,
        timestamp = "2026-08-24T00:00:00Z",
        revision = revision?.let { Revision(commit = it) },
        executionEnvironment = environment,
        scenarios = scenarios.toList(),
    )

    // ---- variants within one run ----

    @Test
    fun `the baseline fans out to every other scenario`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline"),
                scenario("cc-enabled", steady.map { it * 0.8 }),
                scenario("cc-isolated", steady.map { it * 0.75 }),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.map { it.name }).containsExactly("cc-enabled", "cc-isolated")
        assertThat(comparison.baseline.scenarioName).isEqualTo("baseline")
        assertThat(comparison.mode).isEqualTo(ComparisonMode.VARIANT)
    }

    @Test
    fun `a missing baseline names what was actually found`() {
        assertThatThrownBy {
            ComparisonEngine.compareVariants(
                run(scenario("a"), scenario("b")),
                baselineScenario = "typo",
            )
        }.hasMessageContaining("typo").hasMessageContaining("a, b")
    }

    @Test
    fun `the comparison reports a difference without judging it`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline"), scenario("faster", steady.map { it * 0.5 })),
            baselineScenario = "baseline",
        )

        val observation = comparison.scenarios.single().observation
        assertThat(observation.deltaPercent).isLessThan(-40.0)
        assertThat(observation.direction).isEqualTo(Direction.FASTER)
        assertThat(observation.distinguishable).isTrue()
    }

    @Test
    fun `the conditions that produced the numbers are reported`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", warmUps = listOf(120.0, 101.0)),
                scenario("other", warmUps = listOf(120.0, 101.0)),
            ),
            baselineScenario = "baseline",
        )

        val observation = comparison.scenarios.single().observation
        assertThat(observation.baselineWarmUpCount).isEqualTo(2)
        assertThat(observation.baselineSampleSize).isEqualTo(steady.size)
    }

    // ---- differences are reported, never refused ----

    @Test
    fun `a configuration difference is reported and does not change the work`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline"), scenario("cc", args = listOf("--configuration-cache"))),
            baselineScenario = "baseline",
        )

        val difference = comparison.scenarios.single().differences.single { it.field == "args" }
        assertThat(difference.candidate).contains("--configuration-cache")
        assertThat(difference.changesWorkPerformed)
            .describedAs("Arguments are usually the independent variable of the experiment")
            .isFalse()
    }

    @Test
    fun `comparing unlike work is allowed but marked`() {
        // Comparing a clean build against an incremental one is legal and nearly always
        // meaningless. The tool says so rather than refusing.
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("clean", tasks = "clean assembleDebug"), scenario("incremental")),
            baselineScenario = "clean",
        )

        val difference = comparison.scenarios.single().differences.single { it.field == "tasks" }
        assertThat(difference.changesWorkPerformed).isTrue()
    }

    @Test
    fun `identical scenarios produce no differences`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline"), scenario("other")),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.single().differences).isEmpty()
    }

    @Test
    fun `a Gradle version difference is reported`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", gradleVersion = "9.1"),
                scenario("upgraded", gradleVersion = "9.2"),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.single().differences)
            .anySatisfy {
                assertThat(it.field).isEqualTo("gradleVersion")
                assertThat(it.baseline).isEqualTo("9.1")
                assertThat(it.candidate).isEqualTo("9.2")
            }
    }

    // ---- diagnostics ----

    @Test
    fun `unconverged warm-up is reported as a diagnostic`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", warmUps = listOf(5000.0, 500.0)), scenario("other")),
            baselineScenario = "baseline",
        )

        assertThat(comparison.diagnostics)
            .anySatisfy { assertThat(it).contains("warm-up had not converged") }
    }

    @Test
    fun `too few iterations is reported as a diagnostic`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", listOf(100.0, 101.0)), scenario("other", listOf(100.0, 101.0))),
            baselineScenario = "baseline",
        )

        assertThat(comparison.diagnostics)
            .anySatisfy { assertThat(it).contains("only 2 measured iterations") }
    }

    // ---- two runs ----

    @Test
    fun `scenarios shared by two runs are matched by name`() {
        val comparison = ComparisonEngine.compareRuns(
            baselineRun = run(scenario("configuration"), runId = "before", revision = "a3f21c9"),
            candidateRun = run(
                scenario("configuration", steady.map { it * 0.75 }),
                runId = "after",
                revision = "8b04e7d",
            ),
        )

        assertThat(comparison.mode).isEqualTo(ComparisonMode.RUNS)
        assertThat(comparison.scenarios.map { it.name }).containsExactly("configuration")
        assertThat(comparison.baseline.revision).isEqualTo("a3f21c9")
        assertThat(comparison.candidate?.revision).isEqualTo("8b04e7d")
    }

    @Test
    fun `the branch experiment reports the argument that differs`() {
        // The case this tool exists for: configuration cache on one branch, off on the other.
        val comparison = ComparisonEngine.compareRuns(
            baselineRun = run(scenario("configuration")),
            candidateRun = run(
                scenario(
                    "configuration",
                    steady.map { it * 0.75 },
                    args = listOf("--configuration-cache"),
                ),
            ),
        )

        val scenario = comparison.scenarios.single()
        assertThat(scenario.observation.deltaPercent).isLessThan(-20.0)
        assertThat(scenario.differences)
            .describedAs("The argument under test must be reported, never a reason to refuse")
            .anySatisfy { assertThat(it.field).isEqualTo("args") }
    }

    @Test
    fun `a scenario present in only one run is noted rather than failing`() {
        val comparison = ComparisonEngine.compareRuns(
            baselineRun = run(scenario("shared"), scenario("only-before")),
            candidateRun = run(scenario("shared"), scenario("only-after")),
        )

        assertThat(comparison.scenarios.map { it.name }).containsExactly("shared")
        assertThat(comparison.diagnostics)
            .anySatisfy { assertThat(it).contains("only-after").contains("candidate run") }
            .anySatisfy { assertThat(it).contains("only-before").contains("baseline run") }
    }

    @Test
    fun `runs measured on different machines are flagged`() {
        val comparison = ComparisonEngine.compareRuns(
            baselineRun = run(
                scenario("shared"),
                environment = ExecutionEnvironment(operatingSystem = "Mac OS X", architecture = "aarch64"),
            ),
            candidateRun = run(
                scenario("shared"),
                environment = ExecutionEnvironment(operatingSystem = "Linux", architecture = "amd64"),
            ),
        )

        assertThat(comparison.diagnostics)
            .anySatisfy { assertThat(it).contains("different machines") }
        // Flagged, not refused.
        assertThat(comparison.scenarios).hasSize(1)
    }

    @Test
    fun `comparing a run against itself reports no difference`() {
        val identical = run(scenario("configuration"))

        val comparison = ComparisonEngine.compareRuns(identical, identical)

        val observation = comparison.scenarios.single().observation
        assertThat(observation.deltaPercent).isEqualTo(0.0)
        assertThat(observation.distinguishable).isFalse()
        assertThat(comparison.scenarios.single().differences).isEmpty()
    }
}
