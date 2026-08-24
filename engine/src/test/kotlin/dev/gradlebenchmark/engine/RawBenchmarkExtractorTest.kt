package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Fixtures under `profiler-output/` are real reports produced by gradle-profiler 0.25.2,
 * trimmed to the embedded model. Testing against synthesized HTML would not notice the
 * report format changing.
 */
class RawBenchmarkExtractorTest {

    @TempDir
    lateinit var rawDir: Path

    private fun copyFixture(name: String, to: String = RawBenchmarkExtractor.HTML_FILE_NAME) {
        val text = checkNotNull(javaClass.getResourceAsStream("/profiler-output/$name")) {
            "missing test fixture $name"
        }.bufferedReader().readText()
        rawDir.resolve(to).writeText(text)
    }

    @Test
    fun `extracts the model a real successful run embeds in its report`() {
        copyFixture("successful-benchmark.html")

        val result = RawBenchmarkExtractor.extractFrom(rawDir)

        val benchmark = (result as ExtractionResult.Extracted).benchmark
        assertThat(benchmark.environment.profilerVersion).isEqualTo("0.25.2")
        assertThat(benchmark.scenarios.map { it.definition.name })
            .containsExactly("baseline", "cc-enabled")
    }

    @Test
    fun `a successful run has measured iterations separate from warm ups`() {
        copyFixture("successful-benchmark.html")

        val benchmark = (RawBenchmarkExtractor.extractFrom(rawDir) as ExtractionResult.Extracted).benchmark
        val baseline = benchmark.scenarios.first { it.definition.name == "baseline" }

        assertThat(baseline.warmUpIterations).hasSize(1)
        assertThat(baseline.measuredIterations).hasSize(2)
    }

    @Test
    fun `released profiler output carries no precomputed statistics`() {
        copyFixture("successful-benchmark.html")

        val benchmark = (RawBenchmarkExtractor.extractFrom(rawDir) as ExtractionResult.Extracted).benchmark

        // Documents why statistics are computed from iteration values rather than read.
        assertThat(benchmark.scenarios.flatMap { it.samples }.map { it.stats })
            .isNotEmpty
            .allMatch { it == null }
    }

    @Test
    fun `definition captures the fields scenario identity depends on`() {
        copyFixture("successful-benchmark.html")

        val benchmark = (RawBenchmarkExtractor.extractFrom(rawDir) as ExtractionResult.Extracted).benchmark
        val cc = benchmark.scenarios.first { it.definition.name == "cc-enabled" }

        assertThat(cc.definition.args).contains("--configuration-cache")
        assertThat(cc.definition.tasks).isEqualTo("work")
        assertThat(cc.definition.version).isNotBlank()
        // Machine-specific, captured for debugging but never part of identity.
        assertThat(cc.definition.gradleHome).isNotNull()
        assertThat(cc.definition.javaHome).isNotNull()
    }

    @Test
    fun `a failed scenario yields a scenario with no iterations at all`() {
        copyFixture("failed-scenario-benchmark.html")

        val benchmark = (RawBenchmarkExtractor.extractFrom(rawDir) as ExtractionResult.Extracted).benchmark
        val broken = benchmark.scenarios.single()

        assertThat(broken.definition.name).isEqualTo("broken")
        assertThat(broken.iterations).isEmpty()
        assertThat(broken.measuredIterations).isEmpty()
    }

    @Test
    fun `a real benchmark json file is preferred over the html report`() {
        copyFixture("successful-benchmark.html")
        rawDir.resolve(RawBenchmarkExtractor.JSON_FILE_NAME).writeText(
            """{"environment":{"profilerVersion":"9.9.9"},"scenarios":[]}""",
        )

        val benchmark = (RawBenchmarkExtractor.extractFrom(rawDir) as ExtractionResult.Extracted).benchmark

        assertThat(benchmark.environment.profilerVersion).isEqualTo("9.9.9")
    }

    @Test
    fun `braces inside string values do not truncate the object`() {
        val html = """
            <script>
            const benchmarkResult =
            {"title":"a } brace \" and {more}","scenarios":[]}
            ;</script>
        """.trimIndent()

        val extracted = RawBenchmarkExtractor.embeddedObject(html)

        assertThat(extracted).isEqualTo("""{"title":"a } brace \" and {more}","scenarios":[]}""")
    }

    @Test
    fun `a report without the embedded model explains that the format changed`() {
        rawDir.resolve(RawBenchmarkExtractor.HTML_FILE_NAME).writeText("<html>no model here</html>")

        val result = RawBenchmarkExtractor.extractFrom(rawDir)

        assertThat(result).isInstanceOf(ExtractionResult.Failed::class.java)
        assertThat((result as ExtractionResult.Failed).detail).contains("report format changed")
    }

    @Test
    fun `no output at all names both files it looked for`() {
        val result = RawBenchmarkExtractor.extractFrom(rawDir)

        val failed = result as ExtractionResult.Failed
        assertThat(failed.detail).contains("benchmark.json").contains("benchmark.html")
    }

    @Test
    fun `malformed json is reported rather than silently yielding an empty result`() {
        rawDir.resolve(RawBenchmarkExtractor.JSON_FILE_NAME).writeText("{ not json")

        val result = RawBenchmarkExtractor.extractFrom(rawDir)

        assertThat(result).isInstanceOf(ExtractionResult.Failed::class.java)
        assertThat((result as ExtractionResult.Failed).summary).contains("could not be parsed")
    }
}
