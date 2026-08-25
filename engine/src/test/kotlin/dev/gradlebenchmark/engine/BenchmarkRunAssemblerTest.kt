package dev.gradlebenchmark.engine

import dev.gradlebenchmark.core.ExecutionEnvironment
import dev.gradlebenchmark.core.Revision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BenchmarkRunAssemblerTest {

    private val offset = Offset.offset(1e-9)

    private val fixedEnvironment = ExecutionEnvironment(
        operatingSystem = "Mac OS X",
        architecture = "aarch64",
        cpuCores = 10,
        maxMemoryBytes = 2_147_483_648,
    )

    private fun assembler(javaVersion: String? = "21.0.11") = BenchmarkRunAssembler(
        javaVersionResolver = object : JavaVersionResolver {
            override fun versionOf(javaHome: String) = javaVersion
        },
        environment = fixedEnvironment,
    )

    private fun rawScenario(
        name: String = "baseline",
        measured: List<Double> = listOf(100.0, 200.0),
        warmUps: Int = 1,
        unit: String = "ms",
        mutators: List<String> = emptyList(),
        args: List<String> = emptyList(),
        version: String? = "8.14.3",
    ) = RawScenario(
        definition = RawScenarioDefinition(
            name = name,
            title = "Title of $name",
            tasks = "assembleDebug",
            version = version,
            javaHome = "/some/jdk",
            action = "run tasks",
            cleanup = "do nothing",
            invoker = "ToolingApi",
            mutators = mutators,
            args = args,
        ),
        samples = listOf(RawSample(name = "total execution time", unit = unit)),
        iterations = buildList {
            repeat(warmUps) { index ->
                add(
                    RawIteration(
                        phase = "WARM_UP",
                        iteration = index + 1,
                        values = mapOf("total execution time" to 9_999.0),
                    ),
                )
            }
            measured.forEachIndexed { index, value ->
                add(
                    RawIteration(
                        phase = "MEASURE",
                        iteration = index + 1,
                        values = mapOf("total execution time" to value),
                    ),
                )
            }
        },
    )

    private fun rawBenchmark(vararg scenarios: RawScenario) = RawBenchmark(
        environment = RawEnvironment(profilerVersion = "0.25.2", operatingSystem = "mac os x"),
        scenarios = scenarios.toList(),
    )

    private fun assemble(benchmark: RawBenchmark, projectDir: Path? = null, javaVersion: String? = "21.0.11") =
        assembler(javaVersion).assemble(
            benchmark = benchmark,
            runId = "fixed-run-id",
            timestamp = "2026-08-24T00:00:00Z",
            toolVersion = "0.1.0",
            projectDir = projectDir,
        )

    @Test
    fun `the schema is versioned`() {
        val run = assemble(rawBenchmark(rawScenario()))

        assertThat(run.schemaVersion).isEqualTo(1)
        assertThat(run.toolVersion).isEqualTo("0.1.0")
    }

    @Test
    fun `warm-up iterations are excluded from statistics`() {
        // Warm-ups are 9999ms; including them would dominate everything.
        val run = assemble(rawBenchmark(rawScenario(measured = listOf(100.0, 200.0), warmUps = 2)))

        val measurement = run.scenarios.single().measurements.single()
        assertThat(measurement.values).containsExactly(100.0, 200.0)
        assertThat(measurement.statistics.median).isEqualTo(150.0)
        assertThat(measurement.statistics.max).isEqualTo(200.0)
    }

    @Test
    fun `the measurement protocol records both phase counts`() {
        val run = assemble(rawBenchmark(rawScenario(measured = listOf(1.0, 2.0, 3.0), warmUps = 2)))

        val protocol = run.scenarios.single().measurementProtocol
        assertThat(protocol.warmUpCount).isEqualTo(2)
        assertThat(protocol.measuredIterationCount).isEqualTo(3)
        assertThat(protocol.profilerVersion).isEqualTo("0.25.2")
    }

    @Test
    fun `raw values are retained alongside statistics for later statistical methods`() {
        val run = assemble(rawBenchmark(rawScenario(measured = listOf(10.0, 20.0, 30.0))))

        assertThat(run.scenarios.single().measurements.single().values)
            .containsExactly(10.0, 20.0, 30.0)
    }

    @Test
    fun `durations expressed in seconds are normalized to milliseconds`() {
        val run = assemble(rawBenchmark(rawScenario(measured = listOf(1.5, 2.5), unit = "s")))

        val measurement = run.scenarios.single().measurements.single()
        assertThat(measurement.unit).isEqualTo("ms")
        assertThat(measurement.values).containsExactly(1500.0, 2500.0)
        assertThat(measurement.statistics.median).isEqualTo(2000.0, offset)
    }

    @Test
    fun `a non-duration measurement keeps its own unit and values`() {
        // --measure-local-build-cache reports bytes; converting those would be nonsense.
        val run = assemble(rawBenchmark(rawScenario(measured = listOf(4096.0), unit = "bytes")))

        val measurement = run.scenarios.single().measurements.single()
        assertThat(measurement.unit).isEqualTo("bytes")
        assertThat(measurement.values).containsExactly(4096.0)
    }

    @Test
    fun `gradle version is workload configuration, never identity`() {
        val onGradle91 = assemble(rawBenchmark(rawScenario(version = "9.1"))).scenarios.single()
        val onGradle92 = assemble(rawBenchmark(rawScenario(version = "9.2"))).scenarios.single()

        assertThat(onGradle91.workloadConfiguration.gradleVersion).isEqualTo("9.1")
        assertThat(onGradle92.workloadConfiguration.gradleVersion).isEqualTo("9.2")
        assertThat(onGradle91.workloadIdentityHash)
            .describedAs("A Gradle upgrade must stay comparable, not become a different benchmark")
            .isEqualTo(onGradle92.workloadIdentityHash)
    }

    @Test
    fun `the build JVM version is resolved, since the profiler reports only a path`() {
        val run = assemble(rawBenchmark(rawScenario()), javaVersion = "21.0.11")

        assertThat(run.scenarios.single().workloadConfiguration.buildJvmVersion)
            .isEqualTo("21.0.11")
    }

    @Test
    fun `an unresolvable JVM version is recorded as absent rather than failing the run`() {
        val run = assemble(rawBenchmark(rawScenario()), javaVersion = null)

        assertThat(run.scenarios.single().workloadConfiguration.buildJvmVersion).isNull()
    }

    @Test
    fun `changing a build argument does change workload identity`() {
        val plain = assemble(rawBenchmark(rawScenario())).scenarios.single()
        val rerun = assemble(rawBenchmark(rawScenario(args = listOf("--rerun-tasks"))))
            .scenarios.single()

        assertThat(plain.workloadIdentityHash).isNotEqualTo(rerun.workloadIdentityHash)
    }

    @Test
    fun `identity is recorded alongside its hash so a mismatch can be explained`() {
        val run = assemble(rawBenchmark(rawScenario(args = listOf("--offline"))))

        val scenario = run.scenarios.single()
        assertThat(scenario.workloadIdentity.args).containsExactly("--offline")
        assertThat(scenario.workloadIdentity.tasks).isEqualTo("assembleDebug")
    }

    @Test
    fun `assembling the same input twice produces an equal result`() {
        val benchmark = rawBenchmark(rawScenario())

        assertThat(assemble(benchmark)).isEqualTo(assemble(benchmark))
    }

    @Test
    fun `revision metadata is carried through when supplied`() {
        val run = assembler().assemble(
            benchmark = rawBenchmark(rawScenario()),
            runId = "id",
            timestamp = "2026-08-24T00:00:00Z",
            toolVersion = "0.1.0",
            revision = Revision(commit = "abc123", branch = "main", dirty = true),
        )

        assertThat(run.revision).isEqualTo(Revision("abc123", "main", dirty = true))
    }
}

/**
 * The invariant that motivated this milestone's mutator handling, using the mutator string
 * a real Android project produced.
 */
class MutatorIdentityAcrossMachinesTest {

    private fun assembler() = BenchmarkRunAssembler(
        javaVersionResolver = object : JavaVersionResolver {
            override fun versionOf(javaHome: String) = "21.0.11"
        },
        environment = ExecutionEnvironment(),
    )

    private fun benchmarkWith(mutator: String) = RawBenchmark(
        environment = RawEnvironment(profilerVersion = "0.25.2"),
        scenarios = listOf(
            RawScenario(
                definition = RawScenarioDefinition(
                    name = "assemble_incremental",
                    tasks = "assembleDebug",
                    mutators = listOf(mutator),
                ),
                samples = listOf(RawSample(name = "total execution time", unit = "ms")),
                iterations = listOf(
                    RawIteration(phase = "MEASURE", values = mapOf("total execution time" to 1.0)),
                ),
            ),
        ),
    )

    private fun hashFor(mutator: String, projectDir: Path) = assembler().assemble(
        benchmark = benchmarkWith(mutator),
        runId = "id",
        timestamp = "t",
        toolVersion = "0.1.0",
        projectDir = projectDir,
    ).scenarios.single().workloadIdentityHash

    @Test
    fun `an ABI-change scenario hashes identically on a laptop and on CI`() {
        val laptop = hashFor(
            "ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/./app/src/main/" +
                "java/com/example/data/Repo.kt)",
            Path.of("/Users/someone/Projects/App"),
        )
        val ci = hashFor(
            "ApplyAbiChangeToSourceFileMutator(/home/runner/work/App/App/app/src/main/" +
                "java/com/example/data/Repo.kt)",
            Path.of("/home/runner/work/App/App"),
        )

        assertThat(laptop)
            .describedAs("Without this, every nightly comparison reports an incompatible baseline")
            .isEqualTo(ci)
    }

    @Test
    fun `mutating a different file is still a different benchmark`() {
        val projectDir = Path.of("/Users/someone/Projects/App")

        val repo = hashFor(
            "ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/app/src/Repo.kt)",
            projectDir,
        )
        val other = hashFor(
            "ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/app/src/Other.kt)",
            projectDir,
        )

        assertThat(repo).isNotEqualTo(other)
    }
}
