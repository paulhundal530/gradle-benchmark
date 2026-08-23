# Milestone 1 — Skeleton, CLI, CI

**Scope:** CLI entry point, command structure, configuration parsing, output directory
handling, structured exit codes, test harness, CI pipeline. No benchmark execution.

**Status:** complete.

## What to review

In rough order of how much signal each carries:

| # | Artifact | What it tells you |
|---|---|---|
| 1 | [`docs/cli-surface/`](../cli-surface/) | The complete public CLI surface as plain text. Read this to judge whether the command line matches the spec — every command, option, default and help string is here. |
| 2 | [`README.md`](../../README.md) | Documented public behavior: commands, artifacts, regression policy, exit codes. |
| 3 | `./gradlew build` | Compiles all modules, runs 44 unit tests, fails on any compiler warning or ktlint violation. |
| 4 | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) | The verification that runs per PR. |
| 5 | `settings.gradle.kts` + `*/build.gradle.kts` | Module boundaries. `core` declares no dependency on `engine`, so section 5's layering is enforced by the compiler rather than by convention. |

The CLI surface files are generated, not hand-written, and a test asserts the live CLI
still matches them. A change to documented behavior therefore appears as a reviewable
diff instead of being discovered by a user.

```bash
./gradlew :cli:test -PupdateCliSurface   # regenerate after an intentional change
```

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| CLI entry point | `cli/src/main/kotlin/.../Main.kt`; packaged launcher `gradle-benchmark` |
| Command structure | `validate`, `run`, `compare`, `help` — see `docs/cli-surface/` |
| Configuration parsing | `CommandParsingTest` — selection, threshold, mode defaults, overrides |
| Output directory handling | `OutputPathsTest` — defaults to `build/gradle-benchmark`, artifact names stable |
| Structured exit codes | `ExitCodeTest` (mapping) and `CliExitCodeTest` (end to end, all five codes) |
| Test harness | JUnit 5 + AssertJ via the shared convention plugin |
| CI pipeline | `.github/workflows/ci.yml` — format, build, unit test, integration test, package, smoke-start |
| No GitHub vocabulary in the CLI | See the layering check below |

## Layering check

Section 29 rule 2 says no domain logic may live only in the GitHub Action, and section 5.2
says the CLI must not embed GitHub concepts. The naive grep for `github` produces only
false positives, because Clikt's package is `com.github.ajalt`, so exclude it:

```bash
grep -rinE "github|actions/|GITHUB_|job.summary|upload.artifact" \
  cli/src/main core/src/main engine/src/main report/src/main \
  | grep -v "com\.github\.ajalt"
```

Currently returns nothing. Note that the word *workflow* does appear in `ComparisonMode`,
referring to the three product workflows (variant, historical, revision) rather than to
GitHub Actions workflows.

## Behavior worth checking by hand

```bash
./gradlew installDist
B=./cli/build/install/gradle-benchmark/bin/gradle-benchmark
```

| Invocation | Expected |
|---|---|
| `$B` | Full usage, exit 0 |
| `$B help run` | `run` usage, exit 0 |
| `$B help nonsense` | `no such command`, exit 3 |
| `$B compare` | Missing required options, exit 3 |
| `$B run` | `fail-on-regression: false` — variant mode is exploratory |
| `$B compare --baseline a.json --candidate b.json` | `fail-on-regression: true` — gating |
| `$B run --fail-on-regression` | `true`, because an explicit choice beats the mode default |

The last three are the milestone's most load-bearing behavior: enforcement is one
mechanism whose default is chosen by the invocation, never branched on per workflow.

## Build performance

`gradle.properties` enables parallel execution, the build cache, and the configuration
cache. On a project this small the absolute saving is modest (a no-op `build` goes from
roughly 0.44s to 0.29s), but configuration-cache compatibility is far cheaper to hold from
the start than to retrofit: every task added from here is checked as it lands. The root
`integrationTest` aggregator was verified against a real module task to confirm it still
works once Milestone 3 registers one.

CI does not currently reuse the configuration cache between runs. `gradle/actions` can
cache it, but only with a `cache-encryption-key` secret, which is not set up.

## Known gaps

Deliberate, and none of them are in Milestone 1's scope:

- ~~**`integrationTest` is a no-op.**~~ Closed in Milestone 2: `engine` now has an
  integration test suite that exercises a real `gradle-profiler` process.
- **`engine` and `report` compile empty.** Populated in Milestones 2, 3 and 6.
- ~~**`validate` parses but does not execute.**~~ Closed in Milestone 2.
- **`run` and `compare` do not execute.** `run` validates its selection and stops;
  `compare` still only echoes configuration. No command fabricates a benchmark result.
