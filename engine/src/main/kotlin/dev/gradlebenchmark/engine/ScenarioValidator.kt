package dev.gradlebenchmark.engine

import java.nio.file.Path

/**
 * Validates a requested scenario selection before anything expensive happens.
 *
 * Every check here is cheap relative to a benchmark, which is the point: discovering that
 * a baseline scenario name is misspelled should cost a tenth of a second, not the minutes
 * it takes to measure the wrong thing and then throw the result away.
 */
public class ScenarioValidator(private val inspector: ScenarioInspector) {

    public fun validate(request: ValidationRequest): ValidationOutcome {
        val selection = ScenarioDiscovery.selectScenarioFile(request.scenarioDir, request.scenarioFile)
        val scenarioFile = when (selection) {
            is ScenarioFileSelection.Failed -> return ValidationOutcome.Invalid(listOf(selection.problem))
            is ScenarioFileSelection.Selected -> selection.scenarioFile
        }

        val inspection = inspector.inspect(
            scenarioFile = scenarioFile,
            group = request.scenarioGroup,
            projectDir = request.projectDir,
        )
        val scenarioNames = when (inspection) {
            is InspectionResult.Rejected -> return ValidationOutcome.Invalid(
                listOf(
                    ValidationProblem(
                        "Gradle Profiler rejected the scenario selection.",
                        inspection.reason,
                    ),
                ),
            )

            is InspectionResult.Resolved -> inspection.scenarioNames
        }

        // Checked here rather than after the benchmark: the baseline is comparison policy,
        // and a policy that names a scenario the run will not produce can never be applied.
        request.baselineScenario?.let { baseline ->
            if (baseline !in scenarioNames) {
                return ValidationOutcome.Invalid(
                    listOf(
                        ValidationProblem(
                            "Baseline scenario '$baseline' is not part of this selection.",
                            "Selection resolves to: " + scenarioNames.joinToString(", "),
                        ),
                    ),
                )
            }
            if (scenarioNames.size == 1) {
                return ValidationOutcome.Invalid(
                    listOf(
                        ValidationProblem(
                            "Baseline scenario '$baseline' is the only scenario in this selection.",
                            "A comparison needs at least one candidate to compare against the baseline.",
                        ),
                    ),
                )
            }
        }

        return ValidationOutcome.Valid(
            ScenarioSelection(
                scenarioFile = scenarioFile,
                group = request.scenarioGroup,
                scenarioNames = scenarioNames,
            ),
        )
    }
}

/** Everything the user asked for that affects which scenarios will run. */
public data class ValidationRequest(
    val scenarioDir: Path? = null,
    val scenarioFile: Path? = null,
    val scenarioGroup: String? = null,
    val projectDir: Path? = null,
    val baselineScenario: String? = null,
)
