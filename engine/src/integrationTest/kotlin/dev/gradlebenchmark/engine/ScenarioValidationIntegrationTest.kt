package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Exercises the real `gradle-profiler` executable against a real Gradle build.
 *
 * The unit tests parse recorded profiler output, which keeps them fast but cannot notice
 * if the tool's actual behavior drifts. These tests exist so that drift fails the build
 * rather than reaching a user.
 */
class ScenarioValidationIntegrationTest {

    private val fixtureDir: Path = Path.of(
        System.getProperty("gradleBenchmark.fixtureDir")
            ?: error("gradleBenchmark.fixtureDir system property is not set"),
    )

    private val benchmarksDir: Path get() = fixtureDir.resolve("benchmarks")
    private val buildScenarios: Path get() = benchmarksDir.resolve("build.scenarios")

    private fun validator() = ScenarioValidator(ScenarioInspector(ProcessGradleProfiler()))

    @Test
    fun `resolves scenario names from a real scenario file`() {
        val outcome = validator().validate(
            ValidationRequest(scenarioFile = buildScenarios, projectDir = fixtureDir),
        )

        assertThat(outcome).isInstanceOf(ValidationOutcome.Valid::class.java)
        assertThat((outcome as ValidationOutcome.Valid).selection.scenarioNames)
            .containsExactly("baseline", "cc-enabled")
    }

    @Test
    fun `honours a real scenario group`() {
        val outcome = validator().validate(
            ValidationRequest(
                scenarioFile = buildScenarios,
                scenarioGroup = "nightly",
                projectDir = fixtureDir,
            ),
        )

        assertThat((outcome as ValidationOutcome.Valid).selection.scenarioNames)
            .containsExactly("baseline", "cc-enabled")
    }

    @Test
    fun `an unknown group is rejected with the profiler's own list of valid groups`() {
        val outcome = validator().validate(
            ValidationRequest(
                scenarioFile = buildScenarios,
                scenarioGroup = "does-not-exist",
                projectDir = fixtureDir,
            ),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.detail)
            .contains("does-not-exist")
            .contains("nightly")
        // The profiler reports failures as uncaught exceptions; users should never see that.
        assertThat(problem.detail).doesNotContain("java.lang.IllegalArgumentException")
        assertThat(problem.detail).doesNotContain("at org.gradle.profiler")
    }

    @Test
    fun `an unknown baseline is caught before any build runs`() {
        val outcome = validator().validate(
            ValidationRequest(
                scenarioFile = buildScenarios,
                projectDir = fixtureDir,
                baselineScenario = "not-a-scenario",
            ),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.summary).contains("not-a-scenario")
        assertThat(problem.detail).contains("baseline").contains("cc-enabled")
    }

    @Test
    fun `an ambiguous directory lists the real candidate files`() {
        val outcome = validator().validate(
            ValidationRequest(scenarioDir = benchmarksDir, projectDir = fixtureDir),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.detail)
            .contains("build.scenarios")
            .contains("configuration.scenarios")
    }

    @Test
    fun `a malformed scenario file is rejected without a stack trace`() {
        val outcome = validator().validate(
            ValidationRequest(
                scenarioFile = benchmarksDir.resolve("absent.scenarios"),
                projectDir = fixtureDir,
            ),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.summary).contains("Scenario file does not exist")
    }

    companion object {
        /**
         * Locally, skip when gradle-profiler is not installed so the suite stays runnable.
         *
         * On CI, fail instead. A green build that silently tested nothing is worse than a
         * red one, and these are the only tests standing between us and a mocked
         * integration that has quietly diverged from the real tool.
         */
        @JvmStatic
        @BeforeAll
        fun requireProfiler() {
            val fixture = System.getProperty("gradleBenchmark.fixtureDir")?.let(Path::of)
            val fixturePresent = fixture != null && fixture.isDirectory()

            val profilerPresent = runCatching {
                ProcessGradleProfiler().invoke(listOf("--version")).succeeded
            }.getOrDefault(false)

            if (isContinuousIntegration) {
                check(fixturePresent) { "Fixture project is missing at $fixture" }
                check(profilerPresent) {
                    "gradle-profiler is not on PATH. CI must install it rather than skip " +
                        "the only tests that exercise the real integration."
                }
            } else {
                assumeTrue(fixturePresent, "fixture project is missing")
                assumeTrue(profilerPresent, "gradle-profiler is not on PATH")
            }
        }

        private val isContinuousIntegration: Boolean
            get() = System.getenv("CI").toBoolean()
    }
}
