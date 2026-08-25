package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ComparisonEngineTest {

    private fun scenario(
        name: String,
        values: List<Double>,
        warmUps: List<Double> = listOf(1.0),
        gradleVersion: String? = "9.3.1",
        buildJvmVersion: String? = "21.0.11",
    ) = ScenarioRun(
        name = name,
        title = "Title of $name",
        workloadIdentityHash = "hash-$name",
        workloadIdentity = WorkloadIdentity(name = name, tasks = "assembleDebug"),
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
                name = ComparisonPolicy.DEFAULT_MEASUREMENT,
                unit = "ms",
                statistics = Statistics.of(values),
                values = values,
                warmUpValues = warmUps,
            ),
        ),
    )

    private fun run(vararg scenarios: ScenarioRun) = BenchmarkRun(
        toolVersion = "0.1.0",
        runId = "run-1",
        timestamp = "2026-08-24T00:00:00Z",
        executionEnvironment = ExecutionEnvironment(),
        scenarios = scenarios.toList(),
    )

    private val steady = listOf(100.0, 101.0, 99.0, 100.0, 100.0, 101.0)

    @Test
    fun `the baseline fans out to every other scenario`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady),
                scenario("cc-enabled", steady.map { it * 0.8 }),
                scenario("cc-isolated", steady.map { it * 0.75 }),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.map { it.name })
            .containsExactly("cc-enabled", "cc-isolated")
        assertThat(comparison.baseline.scenarioName).isEqualTo("baseline")
    }

    @Test
    fun `the baseline never appears as its own candidate`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", steady), scenario("other", steady)),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.map { it.name }).doesNotContain("baseline")
    }

    @Test
    fun `a missing baseline names what was actually found`() {
        assertThatThrownBy {
            ComparisonEngine.compareVariants(
                run(scenario("a", steady), scenario("b", steady)),
                baselineScenario = "typo",
            )
        }.hasMessageContaining("typo").hasMessageContaining("a, b")
    }

    @Test
    fun `one regressing candidate makes the run regress overall`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady),
                scenario("fine", steady),
                scenario("slow", steady.map { it * 1.5 }),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.overallComparisonStatus)
            .isEqualTo(ComparisonStatus.REGRESSION_PRESENT)
    }

    @Test
    fun `an inconclusive scenario does not mask a real regression elsewhere`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady),
                scenario("unresolvable", listOf(100.0, 130.0, 80.0)),
                scenario("slow", steady.map { it * 1.5 }),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.overallComparisonStatus)
            .isEqualTo(ComparisonStatus.REGRESSION_PRESENT)
    }

    @Test
    fun `the policy is recorded on the comparison`() {
        val policy = ComparisonPolicy(regressionThresholdPercent = 12.5)

        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", steady), scenario("other", steady)),
            baselineScenario = "baseline",
            policy = policy,
        )

        assertThat(comparison.comparisonPolicy).isEqualTo(policy)
        assertThat(comparison.mode).isEqualTo(ComparisonMode.VARIANT)
    }

    @Test
    fun `a configuration difference is reported alongside the number`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady, gradleVersion = "9.1"),
                scenario("upgraded", steady.map { it * 1.14 }, gradleVersion = "9.2"),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.single().workloadDelta)
            .containsEntry("gradleVersion", listOf("9.1", "9.2"))
    }

    @Test
    fun `identical configuration produces no workload delta`() {
        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", steady), scenario("other", steady)),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios.single().workloadDelta).isEmpty()
    }

    @Test
    fun `unconverged warm-up is reported as a diagnostic, not a verdict`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady, warmUps = listOf(5000.0, 500.0)),
                scenario("other", steady),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.diagnostics)
            .anySatisfy { assertThat(it).contains("warm-up had not converged") }
        // It describes experiment quality, so it must not become a scenario status.
        assertThat(comparison.scenarios.single().verdict.status)
            .isIn(ScenarioStatus.PASS, ScenarioStatus.INCONCLUSIVE)
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

    @Test
    fun `converged warm-up produces no warm-up diagnostic`() {
        val comparison = ComparisonEngine.compareVariants(
            run(
                scenario("baseline", steady, warmUps = listOf(200.0, 101.0)),
                scenario("other", steady, warmUps = listOf(200.0, 101.0)),
            ),
            baselineScenario = "baseline",
        )

        assertThat(comparison.diagnostics)
            .noneSatisfy { assertThat(it).contains("warm-up had not converged") }
    }

    @Test
    fun `a scenario missing the configured measurement is skipped rather than guessed at`() {
        val withoutMeasurement = scenario("other", steady).copy(measurements = emptyList())

        val comparison = ComparisonEngine.compareVariants(
            run(scenario("baseline", steady), withoutMeasurement),
            baselineScenario = "baseline",
        )

        assertThat(comparison.scenarios).isEmpty()
        // Comparing nothing is a failure to compare, not a pass.
        assertThat(comparison.overallComparisonStatus).isEqualTo(ComparisonStatus.ERROR)
    }
}
