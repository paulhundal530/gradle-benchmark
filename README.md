# gradle-benchmark

Gradle Benchmark turns build-speed benchmarking into an automated regression check.

Gradle Profiler measures builds. Gradle Benchmark interprets those measurements against an
explicit tolerance, produces machine-readable results and a human-readable report, and
integrates with CI so teams catch build-speed regressions before they become a developer
productivity problem.

> **Status:** early development. Milestone 2 of 10 — scenario discovery and validation
> work against a real Gradle Profiler; benchmark execution is not wired up yet.

## Requirements

- JDK 21+
- [Gradle Profiler](https://github.com/gradle/gradle-profiler) on `PATH`

## Building

```bash
./gradlew build          # compile, unit tests, lint
./gradlew installDist    # produces cli/build/install/gradle-benchmark
```

## Commands

```bash
gradle-benchmark validate   # check scenario selection and tooling, without benchmarking
gradle-benchmark run        # execute a benchmark, optionally comparing variants
gradle-benchmark compare    # compare two normalized run.json files
gradle-benchmark help       # usage for the tool or a command
```

### Validating a selection

`validate` resolves which scenarios a selection will actually run, without benchmarking
anything. It delegates to Gradle Profiler's own resolution, so grouping semantics are not
reimplemented.

```bash
gradle-benchmark validate --scenario-dir benchmarks --project-dir .
```

```text
scenario file: benchmarks/build.scenarios
scenarios:     2
  - baseline
  - cc-enabled
```

Failures exit `3` and name what was inspected rather than merely reporting invalidity:

```text
Baseline scenario 'basline' is not part of this selection.
  Selection resolves to: baseline, cc-enabled
```

`run` performs the same validation before measuring, so a misspelled baseline costs a
tenth of a second rather than the minutes it takes to benchmark the wrong thing.

### Comparing variants

The baseline is comparison policy, not a property of the measurements: Gradle Profiler
writes every scenario into one result file and has no reason to know which is the control.
Name it explicitly, and every other scenario is compared against it.

```bash
gradle-benchmark run \
  --scenario-file build.scenarios \
  --baseline-scenario baseline \
  --regression-threshold-percent 5
```

```text
baseline
    |
    +-- compare --> configuration-cache
    +-- compare --> configuration-cache-isolated
```

Omitting `--baseline-scenario` produces `run.json` only, with no comparison.

### Comparing runs

```bash
gradle-benchmark compare \
  --baseline previous/run.json \
  --candidate current/run.json \
  --regression-threshold-percent 5
```

Both arguments are required. "There may be no baseline" is an orchestration concern for
the CI wrapper, not something the comparison engine models.

## Artifacts

```text
build/gradle-benchmark/
├── run.json          normalized record of one benchmark execution
├── comparison.json   interpretation of two compatible results
├── report.html       human-readable report
└── raw/              preserved Gradle Profiler output, for debugging only
```

JSON is the canonical output. Console text is informational, and nothing downstream should
need to parse it or the HTML.

## Regression policy

A candidate whose configured measurement, summarized by the configured statistic, exceeds
the baseline by **more than** the threshold is a regression:

```text
delta <= threshold  ->  acceptable
delta >  threshold  ->  regression
```

The comparison uses the unrounded value, so 5.001% regresses against a 5% threshold even
though it displays as 5.00%.

Detecting a regression is separate from failing CI. `--fail-on-regression` is the only
thing that maps a verdict to a non-zero exit code, and the command decides only its
default:

| Mode | Invocation | Default |
|---|---|---|
| Variant | `run --baseline-scenario <name>` | `false` — exploratory |
| Historical / revision | `compare --baseline <a> --candidate <b>` | `true` — gating |

An explicit `--fail-on-regression` / `--no-fail-on-regression` always wins.

## Exit codes

These are a stable contract. Branch on them rather than parsing output.

| Code | Meaning |
|---|---|
| 0 | Success, or an acceptable result under the configured policy |
| 1 | Benchmark execution failure — invalid, incomplete, or unusable output |
| 2 | Regression detected **and** `--fail-on-regression` enabled |
| 3 | Invalid configuration or input, including an unreadable baseline file |
| 4 | Incompatible comparison |

Code 4 is returned regardless of `--fail-on-regression`: it means nothing could be
concluded, which must never be reported as "no regression found". Code 1 is likewise never
suppressible — an invalid benchmark must not be silently passable.

## License

Apache License 2.0. See [LICENSE](LICENSE).
