package dev.gradlebenchmark.report

import dev.gradlebenchmark.core.BenchmarkRun
import dev.gradlebenchmark.core.ComparisonEngine
import dev.gradlebenchmark.core.ExecutionEnvironment
import dev.gradlebenchmark.core.MeasurementProtocol
import dev.gradlebenchmark.core.MeasurementResult
import dev.gradlebenchmark.core.Revision
import dev.gradlebenchmark.core.ScenarioRun
import dev.gradlebenchmark.core.Statistics
import dev.gradlebenchmark.core.WorkloadConfiguration
import dev.gradlebenchmark.core.WorkloadIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The report is a major product output, so the tests pin what must not silently disappear
 * from it, per section 23.4 of the brief.
 */
class HtmlReportRendererTest {

    private val steady = listOf(100.0, 101.0, 99.0, 100.0, 100.0, 101.0)

    private fun scenario(
        name: String,
        values: List<Double> = steady,
        warmUps: List<Double> = listOf(220.0, 104.0, 101.0),
        args: List<String> = emptyList(),
        tasks: String = "assembleDebug",
        title: String? = "Title of $name",
    ) = ScenarioRun(
        name = name,
        title = title,
        workloadIdentityHash = "hash-$name",
        workloadIdentity = WorkloadIdentity(name = name, tasks = tasks, args = args),
        workloadConfiguration = WorkloadConfiguration(
            gradleVersion = "9.3.1",
            buildJvmVersion = "21.0.11",
        ),
        measurementProtocol = MeasurementProtocol(warmUps.size, values.size, "0.25.2"),
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

    private fun run(vararg scenarios: ScenarioRun, revision: String? = "a3f21c9") = BenchmarkRun(
        toolVersion = "0.1.0",
        runId = "run-1",
        timestamp = "2026-08-28T12:00:00Z",
        revision = revision?.let { Revision(commit = it) },
        executionEnvironment = ExecutionEnvironment(
            operatingSystem = "Mac OS X",
            architecture = "aarch64",
            cpuCores = 10,
        ),
        scenarios = scenarios.toList(),
    )

    private fun variantReport(vararg scenarios: ScenarioRun): String {
        val benchmarkRun = run(*scenarios)
        return HtmlReportRenderer.render(
            ComparisonEngine.compareVariants(benchmarkRun, baselineScenario = scenarios.first().name),
            benchmarkRun,
        )
    }

    // ---- content that must not disappear ----

    @Test
    fun `the report names every scenario compared`() {
        val html = variantReport(scenario("baseline"), scenario("cc-enabled", steady.map { it * 0.6 }))

        assertThat(html).contains("cc-enabled")
        assertThat(html).contains("Title of cc-enabled")
    }

    @Test
    fun `both measured values and the delta are shown`() {
        val html = variantReport(scenario("baseline"), scenario("cc-enabled", steady.map { it * 0.6 }))

        assertThat(html).contains("-40.0%")
        assertThat(html).contains("100ms")
        assertThat(html).contains("60ms")
    }

    @Test
    fun `resolution is stated for every scenario`() {
        val html = variantReport(scenario("baseline"), scenario("cc-enabled", steady.map { it * 0.6 }))

        assertThat(html).contains("could resolve")
    }

    @Test
    fun `a difference the run could not resolve says so, and says it is not evidence of no change`() {
        val html = variantReport(scenario("baseline"), scenario("same", steady.map { it * 1.001 }))

        assertThat(html).contains("not separable from noise")
        assertThat(html).contains("not evidence of no change")
    }

    @Test
    fun `the conditions that produced the numbers are reported`() {
        val html = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(html).contains("3 warm-ups")
        assertThat(html).contains("6 measured iterations")
    }

    @Test
    fun `full statistics are available lower on the page`() {
        val html = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(html).contains("Full statistics")
        listOf("median", "mean", "min", "p25", "p75", "max", "stddev")
            .forEach { assertThat(html).contains(it) }
    }

    @Test
    fun `environment metadata is included`() {
        val html = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(html).contains("Mac OS X").contains("aarch64")
        assertThat(html).contains("9.3.1").contains("21.0.11").contains("0.25.2")
    }

    @Test
    fun `differences between the sides are shown`() {
        val html = variantReport(
            scenario("baseline"),
            scenario("cc", steady.map { it * 0.6 }, args = listOf("--configuration-cache")),
        )

        assertThat(html).contains("--configuration-cache")
        assertThat(html).contains("args")
    }

    @Test
    fun `a difference that changes the work performed is marked`() {
        val html = variantReport(
            scenario("clean", tasks = "clean assembleDebug"),
            scenario("incremental", steady.map { it * 0.1 }),
        )

        assertThat(html).contains("changes the work performed")
    }

    @Test
    fun `diagnostics appear in the report, not only on the console`() {
        val html = variantReport(
            scenario("baseline", warmUps = listOf(5000.0, 400.0)),
            scenario("other", steady.map { it * 0.6 }),
        )

        assertThat(html).contains("warm-up had not converged")
    }

    // ---- the report carries no verdict ----

    @Test
    fun `no verdict language appears anywhere`() {
        val html = variantReport(
            scenario("baseline"),
            scenario("much-slower", steady.map { it * 3.0 }),
        )

        assertThat(html)
            .doesNotContain("REGRESSION")
            .doesNotContain("threshold")
            .doesNotContain("PASS")
            .doesNotContain("FAIL")
    }

    // ---- the chart ----

    @Test
    fun `the iteration series is charted, warm-ups included`() {
        val html = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(html).contains("<svg")
        assertThat(html).contains("class=\"warmup\"")
        assertThat(html).contains("class=\"measured\"")
        // The chart is what makes an unconverged warm-up visible at a glance.
        assertThat(html).contains("3 warm-up")
    }

    // ---- self-contained and deterministic ----

    @Test
    fun `the report makes no external requests`() {
        val html = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(html)
            .doesNotContain("http://")
            .doesNotContain("https://")
            .doesNotContain("<script")
            .doesNotContain("<link")
    }

    @Test
    fun `the same input always renders the same bytes`() {
        val first = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))
        val second = variantReport(scenario("baseline"), scenario("other", steady.map { it * 0.6 }))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `scenario names from user files cannot inject markup`() {
        val html = variantReport(
            scenario("baseline"),
            scenario("<script>alert(1)</script>", steady.map { it * 0.6 }, title = "a & b"),
        )

        assertThat(html).doesNotContain("<script>alert")
        assertThat(html).contains("&lt;script&gt;")
        assertThat(html).contains("a &amp; b")
    }

    // ---- two runs ----

    @Test
    fun `a two-run comparison names both revisions`() {
        val before = run(scenario("configuration"), revision = "a3f21c9")
        val after = run(scenario("configuration", steady.map { it * 0.5 }), revision = "8b04e7d")

        val html = HtmlReportRenderer.render(
            ComparisonEngine.compareRuns(before, after),
            before,
            after,
        )

        assertThat(html).contains("a3f21c9").contains("8b04e7d")
    }

    // ---- measurements with nothing to compare ----

    @Test
    fun `a run without a baseline reports measurements and says nothing was compared`() {
        val html = HtmlReportRenderer.render(
            run(scenario("assemble")),
            ComparisonEngine.DEFAULT_MEASUREMENT,
        )

        assertThat(html).contains("No baseline scenario was named")
        assertThat(html).contains("assemble")
        assertThat(html).contains("<svg")
        assertThat(html).doesNotContain("could resolve")
    }

    @Test
    fun `a two-run comparison labels the statistics rows by revision, not by role alone`() {
        val before = run(scenario("configuration"), revision = "a3f21c9")
        val after = run(scenario("configuration", steady.map { it * 0.5 }), revision = "8b04e7d")

        val html = HtmlReportRenderer.render(ComparisonEngine.compareRuns(before, after), before, after)

        // Both sides share a scenario name, so the revision is what tells the rows apart.
        assertThat(html).contains("baseline (a3f21c9)")
        assertThat(html).contains("candidate (8b04e7d)")
    }

    @Test
    fun `a variant comparison labels the statistics rows by scenario name`() {
        val html = variantReport(scenario("baseline"), scenario("cc-enabled", steady.map { it * 0.6 }))

        assertThat(html).contains(">baseline<")
        assertThat(html).contains(">cc-enabled<")
    }

    @Test
    fun `an enormous first warm-up does not flatten the measured series`() {
        // From a real run: warm-ups 3887, 83, 84 against measured values of 43 to 68.
        // Scaling to 3887 collapsed the measured line to a single pixel of movement.
        val html = variantReport(
            scenario("baseline"),
            scenario(
                "cc",
                values = listOf(68.0, 68.0, 66.0, 43.0, 51.0),
                warmUps = listOf(3887.0, 83.0, 84.0),
            ),
        )

        val svg = Regex("<svg.*?</svg>", RegexOption.DOT_MATCHES_ALL).findAll(html).last().value
        val measured = Regex("""class="measured" points="([^"]+)"""").find(svg)!!.groupValues[1]
        val ys = measured.split(" ").map { it.split(",")[1].toDouble() }

        assertThat(ys.max() - ys.min())
            .describedAs("The measured series must occupy real vertical space")
            .isGreaterThan(20.0)
    }

    @Test
    fun `a warm-up too large to plot is marked with its value`() {
        val html = variantReport(
            scenario("baseline"),
            scenario(
                "cc",
                values = listOf(68.0, 66.0, 43.0, 51.0),
                warmUps = listOf(3887.0, 83.0),
            ),
        )

        // A line pinned to the top edge must never read as a measurement.
        assertThat(html).contains("offscale")
        assertThat(html).contains(">3887<")
    }

    @Test
    fun `a series whose values barely differ still renders`() {
        val html = variantReport(
            scenario("baseline"),
            scenario("flat", values = listOf(100.0, 100.0, 100.0, 100.0), warmUps = listOf(100.0)),
        )

        assertThat(html).contains("<svg")
    }
}
