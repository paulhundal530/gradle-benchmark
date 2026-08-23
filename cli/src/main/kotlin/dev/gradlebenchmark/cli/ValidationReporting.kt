package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import dev.gradlebenchmark.engine.ScenarioSelection
import dev.gradlebenchmark.engine.ValidationOutcome

/** Terminates the command with a documented exit code. */
internal fun exitWith(code: ExitCode): Nothing = throw ProgramResult(code.code)

/**
 * Prints a validation outcome and stops the command when it failed.
 *
 * Problems go to stderr so a caller redirecting stdout still sees why the run stopped,
 * and each carries the evidence the validator collected rather than a bare "invalid".
 */
internal fun CliktCommand.reportOrExit(outcome: ValidationOutcome): ScenarioSelection = when (outcome) {
    is ValidationOutcome.Valid -> outcome.selection

    is ValidationOutcome.Invalid -> {
        outcome.problems.forEach { problem ->
            echo(problem.summary, err = true)
            problem.detail?.lines()?.forEach { echo("  $it", err = true) }
        }
        exitWith(ExitCode.INVALID_INPUT)
    }
}

/** Renders what a selection will actually run, which is the useful half of validation. */
internal fun CliktCommand.describe(selection: ScenarioSelection) {
    echo("scenario file: ${selection.scenarioFile}")
    selection.group?.let { echo("group:         $it") }
    echo("scenarios:     ${selection.scenarioNames.size}")
    selection.scenarioNames.forEach { echo("  - $it") }
}
