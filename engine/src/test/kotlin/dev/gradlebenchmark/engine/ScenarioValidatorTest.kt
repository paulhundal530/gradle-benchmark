package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ScenarioValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    private val dumpOfTwo = """
        baseline {
            tasks=[work]
        }
        cc-enabled {
            tasks=[work]
        }
    """.trimIndent()

    private fun validator(profiler: GradleProfiler) = ScenarioValidator(ScenarioInspector(profiler))

    private fun scenarioFile(): Path = tempDir.resolve("build.scenarios").also { it.writeText("baseline { }\n") }

    @Test
    fun `a resolvable selection reports the scenarios it will run`() {
        val file = scenarioFile()

        val outcome = validator(FakeGradleProfiler(0, dumpOfTwo))
            .validate(ValidationRequest(scenarioFile = file))

        assertThat(outcome).isEqualTo(
            ValidationOutcome.Valid(
                ScenarioSelection(file, group = null, scenarioNames = listOf("baseline", "cc-enabled")),
            ),
        )
    }

    @Test
    fun `discovery problems short circuit before the profiler is invoked`() {
        val profiler = FakeGradleProfiler(0, dumpOfTwo)

        val outcome = validator(profiler).validate(
            ValidationRequest(scenarioDir = tempDir.resolve("absent")),
        )

        assertThat(outcome).isInstanceOf(ValidationOutcome.Invalid::class.java)
        assertThat(profiler.lastArguments).isEmpty()
    }

    @Test
    fun `a profiler rejection is reported with its own explanation`() {
        val profiler = FakeGradleProfiler(
            exitCode = 1,
            stderr = "java.lang.IllegalArgumentException: Unknown scenario group 'nope' " +
                "requested. Available groups are: nightly",
        )

        val outcome = validator(profiler).validate(
            ValidationRequest(scenarioFile = scenarioFile(), scenarioGroup = "nope"),
        )

        val problems = (outcome as ValidationOutcome.Invalid).problems
        assertThat(problems).hasSize(1)
        assertThat(problems.single().detail).contains("Available groups are: nightly")
    }

    @Test
    fun `an unknown baseline scenario lists what the selection resolves to`() {
        val outcome = validator(FakeGradleProfiler(0, dumpOfTwo)).validate(
            ValidationRequest(scenarioFile = scenarioFile(), baselineScenario = "basline"),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.summary).contains("'basline' is not part of this selection")
        assertThat(problem.detail).contains("baseline").contains("cc-enabled")
    }

    @Test
    fun `a baseline with no candidates cannot produce a comparison`() {
        val onlyBaseline = "baseline {\n    tasks=[work]\n}"

        val outcome = validator(FakeGradleProfiler(0, onlyBaseline)).validate(
            ValidationRequest(scenarioFile = scenarioFile(), baselineScenario = "baseline"),
        )

        val problem = (outcome as ValidationOutcome.Invalid).problems.single()
        assertThat(problem.summary).contains("only scenario")
        assertThat(problem.detail).contains("at least one candidate")
    }

    @Test
    fun `a valid baseline is accepted when candidates exist`() {
        val outcome = validator(FakeGradleProfiler(0, dumpOfTwo)).validate(
            ValidationRequest(scenarioFile = scenarioFile(), baselineScenario = "baseline"),
        )

        assertThat(outcome).isInstanceOf(ValidationOutcome.Valid::class.java)
    }

    @Test
    fun `omitting a baseline is valid because it simply produces no comparison`() {
        val outcome = validator(FakeGradleProfiler(0, dumpOfTwo)).validate(
            ValidationRequest(scenarioFile = scenarioFile(), baselineScenario = null),
        )

        assertThat(outcome).isInstanceOf(ValidationOutcome.Valid::class.java)
    }
}
