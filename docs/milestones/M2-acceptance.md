# Milestone 2 — Scenario discovery and validation

**Scope:** scenario directory support, `.scenarios` discovery, file and group selection,
input validation, and useful errors.

**Status:** complete.

## What to review

| # | Artifact | What it tells you |
|---|---|---|
| 1 | The error table below | Every section 7 failure case, with the exact message a user sees |
| 2 | [`docs/cli-surface/gradle-benchmark-validate.txt`](../cli-surface/gradle-benchmark-validate.txt) | The validation surface |
| 3 | `engine/src/integrationTest/` | Six tests against a real `gradle-profiler` process |
| 4 | `ScenarioDiscovery.kt`, `ScenarioInspector.kt`, `ScenarioValidator.kt` | Discovery, profiler delegation, orchestration |

## A finding that changed the design

The plan specified `--dry-run` for semantic validation. Measured against the fixture:

| Command | Time | Behavior |
|---|---|---|
| `--dump-scenarios` | 0.1s | Resolves and validates the scenario definition. Never invokes Gradle. |
| `--dry-run` | 7.1s | Runs real Gradle builds and writes `benchmark.csv` / `benchmark.html`. |

`--dry-run` is not a validation primitive; it is a benchmark that throws its numbers away.
Validation therefore uses `--dump-scenarios`, which is roughly seventy times cheaper on a
trivial project and produces no artifacts.

The distinction still matters for later: `--dump-scenarios` proves a scenario is *well
formed*, while `--dry-run` proves it can *execute*. Section 7 lists both. Only the first
is implemented here, because a `validate` that always costs seven seconds would not be run
often enough to be useful.

## Error cases

All exit `3` (invalid input), print to stderr, and name what was inspected. Verified
end to end against the real profiler:

| Section 7 case | Message |
|---|---|
| Directory does not exist | `Scenario directory does not exist: nope` / `Checked /abs/path/nope` |
| No `.scenarios` files found | `No .scenarios files found in <dir>` / `Checked <abs path>` |
| Selected file does not exist | `Scenario file does not exist: benchmarks/typo.scenarios` / `Available in benchmarks: build.scenarios` |
| Ambiguous directory | `Multiple .scenarios files found ...; pass --scenario-file to choose one.` / `Found: build.scenarios, configuration.scenarios` |
| Nothing selected | `No scenario file was selected.` / `Pass --scenario-file, or --scenario-dir to discover one.` |
| Invalid group | `Gradle Profiler rejected the scenario selection.` / `Unknown scenario group 'nope' requested. Available groups are: nightly` |
| Unknown baseline | `Baseline scenario 'basline' is not part of this selection.` / `Selection resolves to: baseline, cc-enabled` |
| Baseline with no candidates | `Baseline scenario 'baseline' is the only scenario in this selection.` / `A comparison needs at least one candidate ...` |

The profiler reports failures as uncaught Java exceptions. The message itself is genuinely
useful, so it is kept and the class name and stack trace are stripped. Two integration
tests assert that `java.lang.IllegalArgumentException` and `at org.gradle.profiler` never
reach a user.

## Design notes

**Grouping is not reimplemented.** `--scenario-group` is passed straight through to the
profiler's `--group`, and the resolved scenario names come from the profiler's own dump.
Section 6 forbids a second grouping system, and parsing the scenario DSL ourselves would
have created one by accident.

**The baseline is checked before the benchmark, not after.** The baseline is comparison
policy, and a policy naming a scenario the run will not produce can never be applied. Both
`validate` and `run` reject it up front. Naming the only scenario in a selection is also
rejected: a fan-out needs at least one candidate.

**Selection precedence:** both given, the file resolves against the directory unless it is
absolute; file alone is used as given; directory alone is unambiguous only with exactly
one candidate; neither is an error.

## Verification

```bash
./gradlew build                      # 86 unit tests
./gradlew :engine:integrationTest    # 6 tests against a real gradle-profiler
```

Integration tests skip locally when `gradle-profiler` is absent, but **fail** when `CI` is
set. A green build that silently tested nothing is worse than a red one, and these are the
only tests standing between us and a mocked integration that has drifted from the real
tool. CI installs the profiler explicitly and verifies it before running them.

Verified by hiding the profiler from `PATH` with `CI=true` and confirming the build fails.

## Known gaps

- **Executability is not validated.** `--dump-scenarios` does not prove the scenario's
  tasks exist or that the build succeeds. That arrives with Milestone 3, which runs the
  benchmark for real.
- **`compare` is still a stub.** Milestone 7.
