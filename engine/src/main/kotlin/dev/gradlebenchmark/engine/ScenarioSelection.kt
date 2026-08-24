package dev.gradlebenchmark.engine

import java.nio.file.Path

/**
 * A scenario file, optionally narrowed to a group, together with the scenario names that
 * selection actually resolves to.
 *
 * The names come from Gradle Profiler's own resolution rather than from parsing the
 * scenario DSL ourselves, so grouping semantics stay owned by the profiler.
 */
public data class ScenarioSelection(val scenarioFile: Path, val group: String?, val scenarioNames: List<String>)

/**
 * A reason the requested selection cannot be used.
 *
 * [summary] states what is wrong, and [detail] carries the evidence: the path that was
 * looked at, or what was found instead. Errors must be actionable, so a problem that
 * cannot say what it inspected is not worth reporting.
 */
public data class ValidationProblem(val summary: String, val detail: String? = null) {
    override fun toString(): String = if (detail == null) summary else "$summary\n  $detail"
}

/** Outcome of validating a requested scenario selection. */
public sealed interface ValidationOutcome {
    public data class Valid(val selection: ScenarioSelection) : ValidationOutcome

    public data class Invalid(val problems: List<ValidationProblem>) : ValidationOutcome {
        init {
            require(problems.isNotEmpty()) { "An invalid outcome must carry at least one problem" }
        }
    }
}
