# Gradle Benchmark — Implementation Instructions

## 1. Purpose

Build a CLI-first tool named **Gradle Benchmark** with a thin GitHub Action wrapper.

The CLI executable should be:

```bash
gradle-benchmark
```

The GitHub Action should be named:

```text
gradle-benchmark-action
```

The product is:

> **Regression testing for Gradle build performance.**

Gradle Benchmark uses **Gradle Profiler** as the underlying benchmark engine, then adds the missing CI and interpretation layer around it.

The product should:
- run Gradle Profiler scenarios,
- normalize the raw output into a stable machine-readable format,
- compare compatible benchmark results,
- interpret whether a meaningful regression occurred,
- generate a human-friendly HTML report,
- expose machine-readable JSON artifacts,
- integrate cleanly with CI,
- optionally fail CI when a configured performance regression is detected,
- support historical/nightly comparisons,
- eventually support revision/commit comparisons.

Gradle Profiler provides measurements.

Gradle Benchmark provides the interpretation, workflow, reporting, and CI integration.

---

# 2. Core Product Principle

The core product concept is:

> A benchmark is a scenario executed against a revision or configuration. A comparison is an interpretation of two compatible benchmark results.

All supported workflows should reduce to this model.

Do not build unrelated workflow-specific comparison logic.

The same result and comparison model should support:
- scenario variant comparison,
- historical/nightly comparison,
- revision comparison.

---

# 3. Product Workflows

V1 must eventually support all three workflows below.

Implementation order should be:

1. Scenario / variant comparison
2. Nightly / historical comparison
3. Revision / commit comparison

Revision comparison is intentionally last because it has more environmental and orchestration nuance.

---

## 3.1 Workflow One — Scenario / Variant Comparison

### User question

> Which Gradle configuration performs better?

Examples:
- configuration cache enabled vs disabled,
- different Gradle JVM arguments,
- different compiler settings,
- different Gradle properties,
- KAPT vs KSP where both can be represented by scenario configuration,
- build option experimentation.

### User experience

The repository contains benchmark scenario files, for example:

```text
benchmarks/
├── build.scenarios
├── configuration.scenarios
└── dependency-resolution.scenarios
```

The user runs a selected scenario file or scenario group.

Example CLI shape:

```bash
gradle-benchmark run \
  --scenario-dir benchmarks \
  --scenario-file build.scenarios \
  --scenario-group configuration-cache \
  --regression-threshold-percent 5
```

The benchmark engine runs the relevant Gradle Profiler scenarios/variants.

Gradle Benchmark then:
1. validates the benchmark output,
2. converts the Gradle Profiler result into the internal result model,
3. compares the relevant variants,
4. applies configured regression policy,
5. generates JSON output,
6. generates an HTML report,
7. exits with the appropriate code.

### Desired outcome example

```text
Configuration Cache Benchmark

baseline median: 81.4s
enabled median: 63.2s
change: -22.4%

Conclusion:
The enabled configuration improves median build time by 22.4%.

Status: PASS
```

### Enforcement default

Variant comparison is exploratory. A candidate that is slower than the baseline is a
useful experimental outcome, not a broken build.

`fail-on-regression` therefore defaults to `false` in this workflow.

The comparison still reports `REGRESSION` for the affected candidate and
`REGRESSION_PRESENT` overall. Only the exit code is affected.

See section 8 for the full enforcement model.

### Baseline selection

The baseline is comparison policy, not a property of the measurements.

Gradle Profiler writes every scenario of a run into a single `benchmark.json` and has no
reason to know which of them is the control. The user must name it explicitly:

```bash
gradle-benchmark run \
  --scenario-file build.scenarios \
  --baseline-scenario baseline
```

One baseline fans out to every other scenario in the run:

```text
baseline
    |
    +-- compare --> configuration-cache
    +-- compare --> configuration-cache-isolated
```

Do not infer the baseline from ordering, naming convention, or the contents of
`benchmark.json`.

The terminal should only provide a concise summary and links/paths to generated artifacts.

Do not treat terminal text as the canonical output.

---

## 3.2 Workflow Two — Nightly / Historical Comparison

### User question

> Did build performance regress since the previous benchmark run?

This workflow is intended for infrastructure/build teams monitoring the health of the main branch.

Typical usage:
- a nightly scheduled CI job,
- a fixed regression suite,
- compare the current valid run against the most recent compatible benchmark accepted as healthy.

### Important baseline behavior

The baseline must be:

> the most recent compatible benchmark result that was accepted as healthy

It must **not** simply mean “yesterday,” and it must **not** mean “the previous run that
executed successfully.” A run can execute perfectly and still be a regression; such a run
is valid but not an eligible baseline. See section 19.

Example:

```text
Monday     valid
Tuesday    benchmark infrastructure failure
Wednesday  valid
```

Wednesday should compare against Monday.

A failed or incomplete benchmark must never become a baseline.

### First-run behavior

If no previous compatible benchmark exists:
- run the benchmark,
- generate the result,
- persist/publish it,
- report that no historical baseline exists,
- do not report a regression.

Example:

```text
No compatible previous benchmark result was found.
Current result has been stored as the initial baseline.
```

### Historical storage in V1

Do not build a database.

For GitHub Actions:
- use GitHub Actions artifact storage,
- upload the generated benchmark output,
- on the next scheduled run, locate the most recent successful compatible benchmark artifact,
- download the previous result,
- invoke the CLI comparison logic.

The CLI must not contain GitHub-specific storage logic.

GitHub-specific artifact discovery and retrieval belongs in the GitHub Action wrapper/orchestration layer.

---

## 3.3 Workflow Three — Revision / Commit Comparison

### User question

> Did this code/build-system change make Gradle builds slower?

Examples:
- Gradle upgrade,
- AGP upgrade,
- DI framework migration,
- build logic rewrite,
- modularization change,
- compiler migration,
- dependency change that cannot reasonably be represented as a scenario variable.

Conceptually:

```text
revision A
vs
revision B
```

using the same benchmark definition and comparable execution environment.

This workflow must be part of V1, but should be implemented after the first two workflows are stable.

### Important design principle

The comparison engine must not care how the results were produced.

The revision comparison flow should ultimately be:

```text
run benchmark against revision A
run benchmark against revision B
compare run A with run B
```

Reuse the same comparison engine used by historical comparisons.

Avoid adding a separate revision-comparison result model.

---

# 4. CLI-First Architecture

The CLI is the product.

The GitHub Action is the first CI integration.

Do not make GitHub Actions the core architecture.

The intended model is:

```text
GitHub Action
    ↓
Gradle Benchmark CLI
    ↓
Benchmark Engine
    ↓
Gradle Profiler
    ↓
Raw Results
    ↓
Normalized Result Model
    ↓
Comparison / Interpretation
    ↓
JSON + HTML
```

The CLI should be usable locally without GitHub Actions.

Example:

```bash
gradle-benchmark run ...
```

The same core behavior should be available in local execution and CI execution.

---

# 5. Architectural Boundaries

Keep the following layers separate.

## 5.1 GitHub Action Adapter

Responsibilities:
- read GitHub Action inputs,
- map GitHub inputs to CLI arguments,
- invoke the CLI,
- locate/download previous GitHub artifacts when needed,
- upload generated results,
- publish GitHub job summaries,
- expose GitHub outputs,
- translate CLI exit codes into appropriate Action results.

Must not contain benchmark domain logic.

---

## 5.2 CLI Layer

Responsibilities:
- parse CLI arguments,
- load configuration,
- validate user inputs,
- invoke the appropriate core use case,
- render a concise terminal summary,
- return documented exit codes.

The CLI should not embed GitHub-specific concepts.

Avoid commands such as:

```text
github-nightly
github-pr
```

Prefer benchmark-domain commands such as:

```bash
gradle-benchmark run
gradle-benchmark compare
gradle-benchmark validate
```

---

## 5.3 Benchmark Engine

Responsibilities:
- discover scenario files,
- validate scenario selection,
- invoke Gradle Profiler,
- detect execution failure,
- verify expected raw output exists,
- parse Gradle Profiler output,
- create normalized BenchmarkRun objects.

---

## 5.4 Comparison / Interpretation Engine

Responsibilities:
- verify compatibility between results,
- calculate deltas,
- apply regression thresholds,
- classify outcomes,
- generate comparison-level conclusions.

No CI-specific behavior belongs here.

---

## 5.5 Reporting

Responsibilities:
- generate machine-readable JSON,
- generate HTML,
- render human-readable summaries from the normalized domain model.

The HTML report must be generated from Gradle Benchmark's normalized result/comparison model.

Do not generate the final user-facing HTML directly from Gradle Profiler output.

---

# 6. Scenario Discovery

Repositories should manage benchmark scenarios in a dedicated directory.

Example:

```text
benchmarks/
├── build.scenarios
├── configuration.scenarios
└── dependency-resolution.scenarios
```

The configured scenario directory should be searchable for files ending in:

```text
.scenarios
```

The CLI must support selecting:
- a scenario directory,
- a scenario file,
- optionally a scenario group.

Scenario groups defined by Gradle Profiler should be reused rather than reimplemented.

The tool should not introduce a second incompatible grouping system unless there is a clear future need.

---

# 7. Scenario Validation

A selected scenario must be validated before benchmark execution.

Errors should be explicit and actionable.

Examples:
- scenario directory does not exist,
- no `.scenarios` files found,
- selected scenario file does not exist,
- selected group is invalid,
- Gradle Profiler rejects the scenario,
- scenario cannot execute successfully.

Any scenario execution error invalidates the benchmark run.

Do not continue to comparison when the underlying benchmark is incomplete or failed.

---

# 8. Failure Semantics

Distinguish clearly between:

## Benchmark execution errors

Examples:
- Gradle Profiler crashes,
- Gradle invocation fails,
- scenario is invalid,
- benchmark output is missing,
- benchmark output is malformed,
- benchmark is interrupted,
- required iterations are incomplete.

These should produce:

```text
BENCHMARK_ERROR
```

The job must fail.

A configuration option should not allow an invalid benchmark to silently pass.

---

## Performance regression

The benchmark completed successfully, but performance exceeded the configured threshold.

Example:

```text
baseline median: 100s
candidate median: 107s
delta: +7%
threshold: +5%
```

This should produce:

```text
REGRESSION
```

Whether CI fails is configurable.

Example:

```yaml
fail-on-regression: false
```

or:

```yaml
fail-on-regression: true
```

## Enforcement defaults by mode

`fail-on-regression` is the only mechanism mapping a verdict to a non-zero exit code.

The workflow determines only the *default*. An explicit setting always wins.

| Mode | Derived from | Default |
|---|---|---|
| Variant | `run --baseline-scenario <name>` | `false` (exploratory) |
| Historical | `compare --baseline <a> --candidate <b>` | `true` (gating) |
| Revision | `compare --baseline <a> --candidate <b>` | `true` (gating) |

Do not branch on workflow inside the enforcement logic itself. There is one mechanism
with context-appropriate defaults.

`BASELINE_INCOMPATIBLE` is unaffected by `fail-on-regression`. It always fails, because
it represents an inability to conclude rather than a regression.

---

# 9. Regression Threshold

Do not use ambiguous configuration such as:

```yaml
regression-threshold: 5
```

The initial V1 configuration must explicitly communicate the unit/meaning:

```yaml
regression-threshold-percent: 5
```

CLI equivalent:

```bash
--regression-threshold-percent 5
```

Semantics:

> A candidate result whose configured comparison measurement, summarized by the configured statistic, increases by more than the configured percentage is classified as a regression.

The exact equality boundary must be explicitly documented and covered by tests.

Example policy to consider:

```text
delta <= threshold → acceptable
delta > threshold  → regression
```

Do not leave this boundary implicit.

Internally, duration measurements should use a consistent unit, preferably milliseconds.

A percentage threshold is the initial V1 policy.

Do not add absolute threshold configuration unless there is a concrete need.

The threshold comparison must use the unrounded value. Rounding is presentation only, so
a delta of 5.001% is a regression against a 5% threshold even though it displays as 5.00%.

---

# 9.1 Measurement and Statistic

Metric selection has two independent axes. Do not collapse them into a single field.

```text
measurement   what was measured        build_execution_time
statistic     how it was summarized    median
```

Gradle Profiler emits multiple samples per scenario when options such as `--measure-gc`
or `--measure-config-time` are used, and computes mean/min/p25/median/p75/max/stddev for
each one. `median` is a statistic, not a metric.

V1 accepts exactly one value of each:

```text
measurement = build_execution_time
statistic   = median
```

Reserved for later, requiring no schema change:

```text
measurement   gc_time, configuration_time, build_operation
statistic     mean, p75
```

`run.json` stores every statistic Gradle Profiler produced.

`comparison.json` records which measurement and statistic produced the verdict.

---

# 10. Noise and Statistical Confidence

The percentage threshold is initially a user-facing tolerance policy.

Do not incorrectly claim that a percentage threshold proves statistical significance.

Gradle benchmark data may contain natural variance.

V1 may begin with percentage-based regression classification, but the domain model and architecture should leave room for future confidence/noise analysis.

Possible future states may include:

```text
PASS
REGRESSION
INCONCLUSIVE
```

Do not overengineer confidence analysis in the first milestone unless required.

---

# 11. Result Artifacts

Gradle Benchmark must produce its own stable artifacts.

These are distinct from Gradle Profiler raw output.

Default local output directory:

```text
build/gradle-benchmark/
```

Allow an override:

```bash
--output-dir <path>
```

Generated output should conceptually be:

```text
build/gradle-benchmark/
├── run.json
├── comparison.json
├── report.html
└── raw/
    └── benchmark.json
```

Not every workflow must produce every file if a comparison has not occurred.

---

# 12. BenchmarkRun

A BenchmarkRun represents one valid benchmark execution.

Persist it as:

```text
run.json
```

This is a first-class machine-readable product artifact.

It should contain enough information to:
- understand what ran,
- reproduce/debug the run,
- determine compatibility with another run,
- support future historical analysis.

A conceptual schema:

```json
{
  "schemaVersion": 1,
  "toolVersion": "0.1.0",
  "runId": "...",
  "timestamp": "...",
  "revision": "...",
  "scenario": {
    "file": "build.scenarios",
    "group": "nightly",
    "hash": "..."
  },
  "environment": {
    "os": "...",
    "architecture": "...",
    "javaVersion": "...",
    "gradleVersion": "...",
    "gradleProfilerVersion": "..."
  },
  "benchmarks": []
}
```

The concrete schema may evolve during implementation, but it must:
- be versioned,
- be documented,
- remain deterministic where practical,
- be testable,
- avoid unnecessary GitHub-specific metadata.

---

# 13. BenchmarkComparison

A BenchmarkComparison represents the interpretation of compatible benchmark results.

Persist it as:

```text
comparison.json
```

It should contain:
- references/identifiers for compared runs,
- comparison policy,
- configured threshold,
- metrics used,
- calculated deltas,
- per-scenario status,
- overall status,
- conclusions.

Conceptual example:

```json
{
  "schemaVersion": 1,
  "overallComparisonStatus": "REGRESSION_PRESENT",
  "comparisonPolicy": {
    "measurement": "build_execution_time",
    "statistic": "median",
    "regressionThresholdPercent": 5.0
  },
  "scenarios": [
    {
      "name": "clean-build",
      "measurement": "build_execution_time",
      "statistic": "median",
      "baselineValueMs": 92100,
      "candidateValueMs": 98700,
      "deltaPercent": 7.17,
      "status": "REGRESSION",
      "workloadDelta": {
        "gradleVersion": ["9.1", "9.2"]
      }
    }
  ]
}
```

## Field names must not encode the statistic

Use `baselineValueMs` / `candidateValueMs` with an explicit `statistic` field.

Do not use `baselineMedianMs` / `candidateMedianMs`. Baking the statistic into the field
name forces a breaking schema change the first time `mean` or `p75` is supported.

## Two status vocabularies

Per-scenario status and overall status are drawn from different vocabularies. This is
deliberate; do not unify them.

```text
per-scenario    PASS | REGRESSION
overall         PASS | INCONCLUSIVE | REGRESSION_PRESENT | INCOMPATIBLE | ERROR
```

Rollup uses worst-case precedence:

```text
ERROR                 outranks everything
INCOMPATIBLE
REGRESSION_PRESENT
INCONCLUSIVE          reserved, not emitted in V1
PASS
```

`INCONCLUSIVE` is reserved in the enum but never emitted in V1, so introducing
confidence analysis later is not a breaking change. Its first intended use is
measurement-protocol mismatch (see section 17).

## workloadDelta

When workload configuration differs between the compared runs, record the difference and
surface it in the report. A regression that carries its likely explanation is far more
actionable than a bare percentage:

```text
Incremental Build   18.0s -> 20.6s   +14.2% REGRESSION

Configuration changes:
  Gradle:     9.1 -> 9.2
  Build JVM:  21.0.8 -> 25.0.1
```

This is the machine-readable result that other tools may ingest.

Do not require downstream tools to parse terminal text or HTML.

---

# 14. Raw Gradle Profiler Output

Preserve the original Gradle Profiler output for debugging.

Example:

```text
raw/benchmark.json
```

This raw file is not the canonical Gradle Benchmark public result model.

Treat it as source/debug data.

The normalized `run.json` is the primary execution artifact.

The normalized `comparison.json` is the primary comparison artifact.

---

# 15. HTML Report

Generate a custom Gradle Benchmark HTML report:

```text
report.html
```

The report should prioritize interpretation over raw data.

At minimum it should clearly show:
- overall result,
- configured regression threshold,
- scenario names,
- baseline metric,
- candidate metric,
- percentage delta,
- per-scenario status,
- clear conclusion,
- useful benchmark metadata.

Example:

```text
Build Performance: REGRESSION

Scenario             Baseline    Current    Change
Clean Build            92.1s      92.8s      +0.8% ✓
Incremental Build      18.0s      19.7s      +9.4% ✗
Configuration           7.2s       7.3s      +1.4% ✓

Configured regression threshold: 5%

Conclusion:
Incremental build performance exceeded the configured regression threshold.
```

The report may expose deeper statistics lower on the page.

The primary user experience should make the conclusion immediately obvious.

---

# 16. Console Output

Console output must be concise.

Do not treat console text as the product artifact.

Example:

```text
Benchmark completed: REGRESSION
1 of 4 scenarios exceeded the configured threshold.

Result:
build/gradle-benchmark/comparison.json

Report:
build/gradle-benchmark/report.html
```

The important outputs are:
- JSON,
- HTML,
- exit code.

---

# 17. Result Compatibility

Before comparing historical or revision-level results, validate compatibility.

Compatibility has two opposite failure modes, and the policy exists to sit between them.

Too strict, and a Gradle upgrade returns `BASELINE_INCOMPATIBLE`, discarding exactly the
regression nightly monitoring exists to catch.

Too loose, and a scenario that silently gained `--rerun-tasks` reports a 45% regression
for a benchmark that stopped being the same benchmark.

Compatibility must therefore reject changes in the *measurement environment*, not
automatically reject changes in the *system being measured*.

Metadata splits into four tiers. Three block; one is reported.

| Tier | Question it answers | Fields | On difference |
|---|---|---|---|
| Execution environment | Was the machine the same? | os, architecture, cpu core count, total memory | `INCOMPATIBLE` |
| Workload identity | Are we measuring the same operation? | tasks, action, cleanup, mutators, invoker, args, jvmArgs, systemProperties | `INCOMPATIBLE` |
| Measurement protocol | Did we collect the measurements comparably? | warm-up count, measured iteration count, profiler version, selected measurement | `INCOMPATIBLE` in V1 |
| Workload configuration | What changed in the thing being measured? | gradleVersion, buildJvmVersion | **Reported. Never blocks.** |

Absolute paths such as `gradleHome` and `javaHome` appear in none of these tiers. They
differ between a laptop and a CI runner, and between runner images, so including them
would make every historical comparison incompatible.

## Gradle args, JVM args and system properties

These genuinely straddle identity and configuration:

```text
changes what work is performed      is the independent variable
--rerun-tasks                       -Xmx8g
--no-build-cache                    -PuseNewCompiler=true
--offline                           -Dsome.feature.enabled=true
--configuration-cache
```

V1 must not pretend it can classify them semantically.

They therefore belong to workload identity: not because they are all identity, but
because automatic classification is not possible and blocking is the safe failure.

The `INCOMPATIBLE` message must name the precise argument that changed, so a safe refusal
does not read as a defect:

```text
BASELINE_INCOMPATIBLE
  workload identity differs: args changed
  baseline  [assembleDebug]
  candidate [assembleDebug, --rerun-tasks]
```

Only `gradleVersion` and `buildJvmVersion` are designated reportable in V1, because both
are structurally derivable rather than free-form. A mechanism for user-declared reportable
keys is a valid future extension. Do not build it yet.

## Measurement protocol

Comparing a median of ten iterations against a median of five is not invalid. It is valid
but less confident, and V1 has no vocabulary for that, since `INCONCLUSIVE` is reserved
and unused.

Silently comparing at reduced confidence is worse than refusing, so V1 blocks.

This is a deliberately temporary strictness, not a principle. Measurement-protocol
mismatch is the first intended use of `INCONCLUSIVE`, and this policy should relax when
confidence analysis lands.

Gradle Profiler version belongs to this tier rather than execution environment, because it
describes instrument behavior rather than the machine. It is the field most likely to
prove over-strict; relaxing it to major/minor is the expected first adjustment.

Do not blindly compare two result files merely because they have the same scenario name.

If results are not safely comparable, return a distinct state:

```text
BASELINE_INCOMPATIBLE
```

and explain which field differed.

Compatibility policy should be centralized and tested.

---

# 18. Scenario Identity / Hashing

Persist three derived values with each run. Do not persist a single undifferentiated
"scenario hash": the resolved Gradle Profiler scenario definition mixes concerns that must
be treated differently (see section 17).

```text
workloadIdentityHash    SHA-256 over the canonicalized subset:
                        name, tasks, action, cleanup, mutators,
                        invoker, args, jvmArgs, systemProperties

measurementProtocol     warmUpCount, measuredIterationCount,
                        profilerVersion, measurement

workloadConfiguration   gradleVersion, buildJvmVersion
```

Derive the hash from the resolved scenario representation rather than a raw path string.
Do not use file name alone as benchmark identity.

## Excluded fields

`gradleHome`, `javaHome` and the profiler's internal scenario `id` must appear in none of
the three. The first two are absolute paths that vary by machine; including them makes
every cross-machine comparison incompatible.

The build JVM *version* is required as workload configuration, but the profiler reports
`javaHome` only as a path. Resolve the version by executing `<javaHome>/bin/java -version`.

## Warm-up and iteration counts

These are not present in the profiler's serialized scenario definition. Derive them by
counting the `iterations` array grouped by `phase` (`WARM_UP` versus `MEASURE`).

## Required hash invariants

These must be covered by tests, because both failure directions are silent:

```text
differs only in gradleHome / javaHome     -> same hash
differs only in Gradle or build JVM       -> same hash, difference in workloadConfiguration
differs by an added --rerun-tasks         -> different hash
```

The goal is to detect when historical results no longer represent the same benchmark,
without mistaking an upgrade of the thing being measured for a different benchmark.

---

# 19. Nightly Result Tracking

For GitHub Actions V1:

```text
current CLI output
    ↓
upload GitHub Actions artifact
    ↓
future scheduled workflow
    ↓
find most recent successful compatible result
    ↓
download result
    ↓
compare using CLI
```

The CLI should only receive result files.

It should not need to know how those files were stored or retrieved.

`compare` requires both `--baseline` and `--candidate`. A missing or unreadable baseline is
an invalid-input error, not a special case. "There may be no baseline" is an orchestration
concern and belongs in the CI wrapper, not the comparison engine.

## Baseline advancement

The historical baseline is the most recent compatible benchmark that was **accepted as
healthy**. It is not simply the previous run, and not the most recent successful workflow.

```text
PASS         eligible as next baseline
REGRESSION   does not advance the baseline
ERROR        does not advance the baseline
```

Without this rule, a regression that persists across two nights disappears:

```text
Mon  100s  PASS          accepted baseline, stays put
Tue  110s  REGRESSION    vs Mon  +10.0%
Wed  111s  REGRESSION    vs Mon  +11.0%   (vs Tue it would be -0.9% PASS)
Thu  109s  REGRESSION    vs Mon   +9.0%
```

Comparing Wednesday against Tuesday would report a pass and silently retire a real
regression.

### Non-goal

Do not automatically advance the baseline after N consecutive regressions. That heuristic
makes persistent regressions vanish, which is precisely the failure this rule prevents.

### Accepted consequence

Once a team knowingly accepts a slower baseline, every subsequent run keeps reporting the
same regression.

The V1 escape hatch is manual promotion: an explicit input that publishes the current
result as an accepted baseline. Document this rather than letting users discover it.

Richer baseline management is a future extension, not a V1 concern.

### Artifact eligibility

The CI wrapper must be able to identify an eligible baseline without downloading every
candidate. Encode the verdict in the published artifact name so the lookup can filter on
listing alone.

Do not build a historical metrics server or database in V1.

Leave room for future storage providers such as:
- local filesystem,
- GitHub Actions artifacts,
- S3,
- database-backed stores.

Do not implement them prematurely.

---

# 20. GitHub Action UX

The Action should remain simple.

Conceptual configuration:

```yaml
- uses: <org>/gradle-benchmark-action@v1
  with:
    scenario-directory: benchmarks
    scenario-group: nightly
    regression-threshold-percent: 5
    fail-on-regression: false
```

GitHub-specific responsibilities may include:
- publishing a job summary,
- exposing result paths,
- uploading artifacts,
- historical artifact lookup,
- returning CI status.

## Publication must precede enforcement

Step ordering is a correctness requirement, not a style preference:

```text
run CLI
    |
    +-- capture exit code, do NOT fail the step
    +-- upload all generated artifacts
    +-- publish job summary
    +-- only now, exit with the captured code
```

A naive run-then-upload sequence loses `comparison.json` and `report.html` at exactly the
moment the user needs them most: the CLI exits non-zero, the step fails, and no later step
runs.

Every publication step must execute unconditionally. Benchmark execution errors should
likewise retain whatever diagnostic artifacts and profiler logs are safely available.

No benchmark interpretation logic should exist only in the Action.

If logic determines whether a benchmark is a regression, it belongs in the CLI/core domain.

---

# 21. Suggested CLI Commands

Initial command model:

```bash
gradle-benchmark validate
gradle-benchmark run
gradle-benchmark compare
```

### validate

Validate:
- scenario directory,
- scenario file,
- selected scenario group,
- required environment,
- Gradle Profiler availability/configuration where applicable.

### run

Execute a benchmark and generate:

```text
run.json
raw/benchmark.json
```

If the selected scenario contains comparable variants, it may also produce:

```text
comparison.json
report.html
```

### compare

Compare two compatible normalized BenchmarkRun files:

```bash
gradle-benchmark compare \
  --baseline previous/run.json \
  --candidate current/run.json \
  --regression-threshold-percent 5
```

Generate:

```text
comparison.json
report.html
```

The exact CLI structure may be refined during implementation, but architectural separation should remain.

---

# 22. Exit Codes

Define and document structured CLI exit codes.

A possible starting model:

```text
0 = successful benchmark / acceptable result
1 = benchmark execution failure
2 = regression detected when enforcement is enabled
3 = invalid configuration/input
4 = incompatible comparison
```

The exact numeric mapping may be refined.

Requirements:
- exit codes must be documented,
- Action logic may rely on them,
- tests must cover them,
- do not require parsing console output to determine the result.

---

# 23. Testing Requirements

Testing is a release requirement, not an optional follow-up task.

Every new feature must include appropriate test coverage.

Do not mark a milestone complete if its core behavior is untested.

---

## 23.1 Unit Tests

Unit-test deterministic domain logic extensively.

Examples:
- scenario discovery,
- scenario selection,
- configuration validation,
- threshold calculations,
- regression classification,
- equality boundary semantics,
- result parsing,
- compatibility checks,
- metadata validation,
- comparison calculation,
- report-view-model generation,
- output path handling,
- exit-code mapping.

Required threshold boundary cases should include at least:

```text
4.9% with 5% threshold
5.0% with 5% threshold
5.1% with 5% threshold
```

The intended semantics must be explicitly asserted.

---

## 23.2 Integration Tests

Include a small Gradle fixture project.

Use it to validate the real integration path where practical:

```text
CLI
→ Gradle Profiler
→ benchmark output
→ normalized run.json
```

Do not rely only on mocks for the Gradle Profiler integration.

Avoid making the full test suite unnecessarily slow.

A combination of:
- fast fixture-based tests,
- focused real integration tests,
- test doubles for expensive benchmark loops

is acceptable.

---

## 23.3 Comparison Fixture Tests

Store representative benchmark input fixtures.

Test:
- successful comparison,
- regression,
- no regression,
- malformed results,
- incompatible scenarios,
- incompatible environment metadata,
- missing metrics,
- partial benchmark output.

---

## 23.4 HTML / Report Tests

The HTML report is a major product output.

Test that important content is not accidentally removed.

At minimum verify:
- overall status,
- scenario names,
- baseline values,
- candidate values,
- delta,
- configured threshold,
- conclusion.

Snapshot/golden testing is acceptable where appropriate.

Prefer deterministic report generation.

---

## 23.5 GitHub Action Tests

The Action wrapper should be thin.

Test:
- input mapping,
- CLI invocation,
- exit-code propagation,
- artifact path handling,
- fail-on-regression behavior,
- historical result lookup orchestration where practical.

Keep domain logic out of these tests because domain logic should already be covered in the CLI/core tests.

---

# 24. CI Requirements

Every pull request should run automated CI.

At minimum:

```text
compile/build
unit tests
integration tests
static analysis/lint
packaging verification
```

CI should fail if any required verification fails.

If applicable to the selected implementation language, add:
- formatting validation,
- dependency lock/checks,
- code quality checks.

Keep CI deterministic and reasonably fast.

---

# 25. Release Automation

Shipping new versions must be automated.

Do not rely on manual local release steps.

Target lifecycle:

```text
pull request
    ↓
CI
    ├── build
    ├── unit tests
    ├── integration tests
    ├── static analysis
    └── package verification

merge to main
    ↓
main verification / development artifact as appropriate

version tag / release trigger
    ↓
release workflow
    ├── run full verification
    ├── build CLI distributions
    ├── publish release artifacts
    ├── publish GitHub Release
    ├── publish/update GitHub Action version
    └── generate release notes
```

Versioning strategy should be simple, documented, and automated.

Semantic Versioning is preferred unless there is a strong reason otherwise.

The GitHub Action should support stable major aliases where practical, for example:

```text
@v1
```

pointing to the latest compatible `1.x` release.

---

# 26. Implementation Milestones

Implement systematically.

Do not attempt all of V1 in one pass.

---

## Milestone 1 — Project Skeleton

Build:
- CLI entry point,
- command structure,
- configuration parsing,
- logging,
- output directory handling,
- structured exit codes,
- test harness,
- CI pipeline.

No real benchmark execution is required yet.

---

## Milestone 2 — Scenario Discovery and Validation

Build:
- scenario directory support,
- `.scenarios` discovery,
- scenario file selection,
- scenario group selection,
- input validation,
- useful errors.

Add unit tests.

---

## Milestone 3 — Gradle Profiler Integration

Build:
- Gradle Profiler invocation,
- controlled process execution,
- stdout/stderr capture,
- timeout/interruption handling where appropriate,
- raw result detection,
- malformed/missing result handling,
- integration fixture project.

Produce:

```text
raw/benchmark.json
```

Add integration tests.

---

## Milestone 4 — BenchmarkRun Model

Build:
- normalized run model,
- versioned schema,
- execution environment metadata,
- revision metadata,
- workload identity hash,
- measurement protocol,
- workload configuration,
- Gradle Profiler result normalization.

Produce:

```text
run.json
```

Add schema/serialization tests.

---

## Milestone 5 — Variant Comparison

Build:
- comparison model,
- percentage delta calculation,
- regression threshold policy,
- per-scenario status,
- overall status,
- machine-readable comparison output.

Produce:

```text
comparison.json
```

This completes the core of workflow one.

---

## Milestone 6 — HTML Reporting

Build:
- custom HTML report,
- clear top-level conclusion,
- per-scenario summary,
- metadata,
- deeper benchmark statistics as useful.

Produce:

```text
report.html
```

Add report tests.

---

## Milestone 7 — Historical Comparison

Build:
- compare two normalized run files,
- compatibility validation,
- previous-result baseline semantics,
- no-baseline behavior,
- incompatible-baseline behavior.

This is CLI/core functionality only.

Do not add GitHub artifact logic here.

---

## Milestone 8 — GitHub Action Wrapper

Build the thin Action adapter.

Responsibilities:
- action inputs,
- CLI installation/invocation,
- artifact upload,
- GitHub job summary,
- exit handling.

Workflow one should now be usable through GitHub Actions.

---

## Milestone 9 — Nightly GitHub Orchestration

Build:
- previous successful benchmark artifact lookup,
- artifact download,
- current benchmark execution,
- compatibility filtering,
- comparison invocation,
- artifact publication.

This completes workflow two.

---

## Milestone 10 — Revision Comparison

Only after the above behavior is stable.

Design carefully before implementation.

Requirements include:
- same benchmark definition across revisions,
- controlled scenario source semantics,
- comparable environments,
- clean checkout/worktree strategy,
- deterministic orchestration,
- robust cleanup,
- failure handling,
- result compatibility.

Do not take shortcuts that make the comparison misleading.

This completes workflow three and the intended V1.

---

# 27. Revision Comparison Design Notes

Do not implement these prematurely, but preserve them as requirements for the final V1 milestone.

The scenario definition may:
- exist only on the candidate revision,
- exist on both revisions,
- differ between revisions.

The comparison must use the same benchmark definition for both measurements.

A likely clean model is:

> Select one authoritative benchmark definition and execute that exact definition against both revisions.

Candidate-as-authoritative is a reasonable default to evaluate.

Do not require a scenario file to already exist on the baseline revision if it can be safely supplied externally to the baseline checkout.

Persist and validate the workload identity hash.

Revision comparison is definitionally a workload-configuration change, so the compatibility
policy in section 17 must not reject it.

Abort comparison if equivalent benchmark execution cannot be guaranteed.

---

# 28. Non-Goals for Initial V1

Do not prematurely build:

- a SaaS backend,
- a hosted metrics database,
- a web dashboard service,
- arbitrary benchmark framework support beyond Gradle,
- Android Macrobenchmark/Microbenchmark integration,
- performance history visualization beyond what the stored result model naturally enables,
- AI-generated benchmark interpretation,
- dynamic GitHub workflow dropdown generation,
- multi-CI-provider wrappers,
- S3/database result stores,
- statistical significance engine unless explicitly prioritized,
- complex distributed benchmark execution,
- automatic commit blame/root-cause analysis.

These can be future extensions.

Keep the first version focused.

---

# 29. Important Product Rules

## Rule 1

**The CLI is the product. GitHub Actions is an adapter.**

## Rule 2

**No domain logic may live only in the GitHub Action.**

## Rule 3

**Machine-readable JSON is the canonical output.**

Console text is informational only.

## Rule 4

**The HTML report is generated from the normalized Gradle Benchmark model.**

Do not make Gradle Profiler HTML the product output.

## Rule 5

**Invalid or incomplete benchmark execution must abort the workflow.**

Do not compare partial data.

## Rule 6

**Regression failure is configurable. Benchmark execution failure is not silently ignorable.**

## Rule 7

**Historical and revision comparisons must validate compatibility before drawing conclusions.**

## Rule 8

**Every feature requires appropriate tests before it is considered complete.**

## Rule 9

**Releases must be automated.**

## Rule 10

**Prefer simple, explicit configuration over magic behavior.**

---

# 30. Product Explanation

Use the following explanation when evaluating whether implementation choices still align with the product:

> Gradle Benchmark is automated performance regression testing for Gradle builds. It uses Gradle Profiler to measure build performance, then interprets those measurements against a configurable percentage tolerance, produces machine-readable results and a human-friendly report, and integrates with CI so teams can catch build-speed regressions before they become a developer productivity problem.

Short description:

> **Regression testing for Gradle build performance.**

If a feature does not contribute meaningfully to that goal, question whether it belongs in V1.

---

# 31. Expected Engineering Style

While implementing:
- prefer clear abstractions over speculative extensibility,
- keep domain models independent from CI providers,
- use dependency injection or explicit boundaries where external processes/filesystems need testing,
- avoid static/global state,
- produce actionable errors,
- preserve raw Gradle Profiler data for debugging,
- keep serialization schemas versioned,
- write tests alongside production code,
- keep commits/milestones small and reviewable,
- document public CLI behavior,
- avoid broad refactors unrelated to the current milestone.

Before beginning each milestone:
1. inspect the existing implementation,
2. state what will change,
3. identify required tests,
4. implement the smallest coherent version,
5. run the relevant test suite,
6. fix failures,
7. summarize what was completed and any open risks.

Do not skip ahead to later milestones if current foundations are unstable.

---

# 32. Definition of Done for V1

V1 is complete when:

- the CLI can discover and validate Gradle Profiler scenario files,
- the CLI can run a benchmark reliably,
- raw Gradle Profiler output is preserved,
- normalized `run.json` is generated,
- scenario/variant comparisons produce `comparison.json`,
- regression threshold percentage semantics are implemented and tested,
- a custom HTML report is generated,
- machine-readable outputs are documented,
- historical comparison between compatible run files works,
- the GitHub Action can execute the CLI,
- GitHub Actions can upload benchmark artifacts,
- nightly runs can locate and compare against the most recent compatible result accepted as healthy,
- baseline advancement rules and the manual promotion path are implemented and documented,
- revision comparison is implemented reliably,
- invalid/incomplete executions fail cleanly,
- `fail-on-regression` works,
- CI covers build, tests, analysis, and packaging,
- releases are automated,
- public behavior is documented,
- appropriate unit, integration, report, and Action tests exist.

Do not declare V1 complete while revision comparison remains experimental or misleading.
