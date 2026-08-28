# gradle-benchmark

Gradle Benchmark turns Gradle Profiler measurements into reproducible, comparable results.

Gradle Profiler measures builds. Gradle Benchmark normalizes those measurements, compares
two of them, and reports what changed, how precisely it was measured, and under what
conditions.

It reports; it does not judge. There is no threshold and no pass or fail, because deciding
whether a difference matters requires knowing what the scenario is for. A team wanting to
gate on a number can read `comparison.json` and apply one they chose. See
[the observational model](docs/design/observational-model.md) for why.

> **Status:** early development. Benchmarks run, two runs or two scenarios can be compared,
> and results are written as JSON and as a self-contained HTML report.

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

### Comparing scenarios in one run

The baseline is your choice of control, named explicitly. Every other scenario in the run is
compared against it.

```bash
gradle-benchmark run \
  --scenario-file build.scenarios \
  --baseline-scenario baseline
```

### Comparing two runs

The main case: the same scenario measured twice, on two branches or commits.

```bash
gradle-benchmark run --scenario-file build.scenarios --output-dir out/before
# switch branch, or change what you are testing
gradle-benchmark run --scenario-file build.scenarios --output-dir out/after

gradle-benchmark compare \
  --baseline out/before/run.json \
  --candidate out/after/run.json
```

```text
Compared a3f21c9 against 8b04e7d:

  configuration                 1.62s    -24.3%
    beyond what this experiment could resolve (±3.1%)
    3 warm-ups, 10 measured iterations per side
    differs in:
      args: [] -> [--configuration-cache]
```

Differences between the two runs are reported, never a reason to refuse. Comparing
configuration-cache-enabled against disabled is precisely a comparison where the arguments
differ, and that difference is the point of the experiment.

## Artifacts

```text
build/gradle-benchmark/
├── run.json          normalized record of one benchmark execution
├── comparison.json   what two sets of measurements show
├── report.html       the same thing, for reading and sharing
└── raw/              preserved Gradle Profiler output, for debugging only
```

JSON is the canonical output. Console text is informational, and nothing downstream should
need to parse it.

## What the tool asserts

Facts about the experiment, never judgements about the result:

- the measured values and their statistics, over measured iterations only
- the difference between two sides
- **whether that difference exceeds what the experiment could resolve** — a statement about
  the measurement, not about whether the change is acceptable
- how many warm-ups and measured iterations produced it
- what differed between the two sides
- whether warm-up converged

"Smaller than this experiment could resolve" is not the same as "unchanged", and the tool
says which one it means.

### Resolution and iteration counts

How small a difference you can resolve follows from how many iterations you run. Measured on
a real project with roughly 6% variation between builds:

| Iterations per side | Smallest resolvable difference |
|---|---|
| 2 | ~9% |
| 9 | ~10% |
| 18 | ~7% |
| 35 | ~5% |
| 97 | ~3% |

Decide what size of difference matters to you, then read off the cost. A run reports what it
could resolve, so a result is never more confident than the measurements allow.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The benchmark ran and results were produced |
| 1 | Benchmark execution failure — invalid, incomplete, or unusable output |
| 3 | Invalid configuration or input |

There is deliberately no code meaning "regression". A team wanting to fail a build on a
threshold can read `comparison.json` and apply one they chose for a scenario they understand.

## License

Apache License 2.0. See [LICENSE](LICENSE).
