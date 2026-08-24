package dev.gradlebenchmark.report

import dev.gradlebenchmark.core.BenchmarkRun
import dev.gradlebenchmark.core.ExecutionEnvironment
import dev.gradlebenchmark.core.MeasurementProtocol
import dev.gradlebenchmark.core.MeasurementResult
import dev.gradlebenchmark.core.Revision
import dev.gradlebenchmark.core.ScenarioRun
import dev.gradlebenchmark.core.Statistics
import dev.gradlebenchmark.core.WorkloadConfiguration
import dev.gradlebenchmark.core.WorkloadIdentity
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class RunJsonWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private val run = BenchmarkRun(
        toolVersion = "0.1.0",
        runId = "11111111-2222-3333-4444-555555555555",
        timestamp = "2026-08-24T12:00:00Z",
        revision = Revision(commit = "abc123", branch = "main", dirty = false),
        executionEnvironment = ExecutionEnvironment(
            operatingSystem = "Mac OS X",
            architecture = "aarch64",
            cpuCores = 10,
            maxMemoryBytes = 2_147_483_648,
        ),
        scenarios = listOf(
            ScenarioRun(
                name = "assemble_incremental",
                title = "assemble (incremental, ABI change)",
                workloadIdentityHash = "deadbeef",
                workloadIdentity = WorkloadIdentity(
                    name = "assemble_incremental",
                    tasks = "assembleDebug",
                    mutators = listOf("ApplyAbiChangeToSourceFileMutator(app/src/main/Repo.kt)"),
                ),
                workloadConfiguration = WorkloadConfiguration(
                    gradleVersion = "9.3.1",
                    buildJvmVersion = "21.0.11",
                ),
                measurementProtocol = MeasurementProtocol(
                    warmUpCount = 2,
                    measuredIterationCount = 3,
                    profilerVersion = "0.25.2",
                ),
                measurements = listOf(
                    MeasurementResult(
                        name = "total execution time",
                        unit = "ms",
                        statistics = Statistics.of(listOf(482.80, 443.81, 438.17)),
                        values = listOf(482.80, 443.81, 438.17),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `the same run always serializes to the same bytes`() {
        assertThat(RunJsonWriter.render(run)).isEqualTo(RunJsonWriter.render(run))
    }

    @Test
    fun `it round-trips without loss`() {
        val restored = Json.decodeFromString<BenchmarkRun>(RunJsonWriter.render(run))

        assertThat(restored).isEqualTo(run)
    }

    @Test
    fun `it writes to disk and creates missing directories`() {
        val destination = tempDir.resolve("nested/out/run.json")

        RunJsonWriter.write(run, destination)

        assertThat(destination.exists()).isTrue()
        assertThat(Json.decodeFromString<BenchmarkRun>(destination.readText())).isEqualTo(run)
    }

    /**
     * `run.json` is the contract other tools read, so the field names are pinned. Renaming
     * one is a breaking change that must go through schemaVersion, not slip out in a
     * refactor.
     */
    @Test
    fun `the published schema keys are stable`() {
        val rendered = RunJsonWriter.render(run)

        assertThat(rendered).contains(
            "\"schemaVersion\"",
            "\"toolVersion\"",
            "\"runId\"",
            "\"timestamp\"",
            "\"revision\"",
            "\"executionEnvironment\"",
            "\"scenarios\"",
            "\"workloadIdentityHash\"",
            "\"workloadIdentity\"",
            "\"workloadConfiguration\"",
            "\"measurementProtocol\"",
            "\"measurements\"",
        )
    }

    @Test
    fun `identity and configuration stay separate in the output`() {
        val rendered = RunJsonWriter.render(run)
        val identityBlock = rendered.substringAfter("\"workloadIdentity\":")
            .substringBefore("\"workloadConfiguration\"")

        // A Gradle version inside the identity block would mean upgrades break comparability.
        assertThat(identityBlock).doesNotContain("9.3.1")
        assertThat(rendered).contains("\"gradleVersion\": \"9.3.1\"")
    }

    @Test
    fun `raw measured values survive serialization for later statistical methods`() {
        val restored = Json.decodeFromString<BenchmarkRun>(RunJsonWriter.render(run))

        assertThat(restored.scenarios.single().measurements.single().values)
            .containsExactly(482.80, 443.81, 438.17)
    }

    @Test
    fun `defaults are written rather than omitted, so the shape never varies`() {
        val minimal = run.copy(revision = null, scenarios = emptyList())

        val rendered = RunJsonWriter.render(minimal)

        assertThat(rendered).contains("\"schemaVersion\": 1")
        assertThat(rendered).contains("\"cpuCores\"")
    }
}
