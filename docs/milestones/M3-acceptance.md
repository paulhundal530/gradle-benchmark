# Milestone 3 — Gradle Profiler integration

**Scope:** profiler invocation, controlled process execution, output capture, raw result
detection, malformed and missing result handling, and an integration fixture.

**Status:** complete.

## The finding that matters

The plan's finding F1 said Gradle Profiler writes `benchmark.json`. **It does not.**

That claim came from reading `JsonResultWriter` on GitHub master. Checked against the
installed binary:

| | 0.25.2 (latest release, July 2026) |
|---|---|
| `JsonResultWriter` in the jar | yes |
| `ScenarioResultWriter` in the jar | **no** |
| `SampleStatistics` in the jar | **no** |
| `benchmark.json` written | **no** |
| Files actually written | `benchmark.csv`, `benchmark.html`, `profile.log`, per-scenario dirs |

The result model *is* produced, but embedded in `benchmark.html` as a `const
benchmarkResult = {...}` literal driving the report's charts. Two consequences:

**The model is extracted from the HTML report.** The CSV is not a viable alternative: it
carries scenario *titles* rather than names, and none of the arguments, JVM arguments or
system properties that scenario identity depends on. `benchmark.json` is preferred when
present, so this keeps working unchanged once a release writes it.

**Statistics are computed by us, not read.** Released Gradle Profiler computes none. This
is arguably better: we control the definitions and can match the profiler's own report
exactly (population standard deviation, R-7 interpolated quantiles). Milestone 4 does the
computing; this milestone only preserves the iteration values.

## The incomplete-run detector, corrected

Finding F3 claimed `samples[].stats` is absent when a scenario has no measured iterations,
making it the incomplete-run signal. Since released profiler never emits `stats` at all,
that detector would have failed every run.

The underlying condition was right, the mechanism was wrong. Measured against real runs:

| Run | `iterations[]` | MEASURE | WARM_UP |
|---|---|---|---|
| Success | 3 | 2 | 1 |
| Scenario whose build fails | 0 | 0 | 0 |

So the detector counts MEASURE-phase iterations. Warm-ups do not count: a run that warmed
up and then failed has measured nothing.

## Failure semantics

A failed benchmark **still writes** `benchmark.html` and `benchmark.csv`, so artifact
presence proves nothing and the exit code must gate first. Any of the following is
`BENCHMARK_ERROR` (exit 1), and none is suppressible:

- non-zero profiler exit
- no `benchmark.json` and no `benchmark.html`
- output that does not parse
- the embedded model missing, which means the report format changed
- any scenario with zero measured iterations
- zero scenarios

`raw/benchmark.json` is **never written for an invalid run**, so a downstream milestone
cannot accidentally read a partial result.

Console output stays a summary. A failed benchmark's stdout is dominated by progress
logging, so only lines describing the failure are kept and the profiler's log is linked:

```
Gradle Profiler failed to run the benchmark.
  ERROR: failed to run build. See log file for details.
  Caused by: Task 'noSuchTaskExistsHere' not found in root project 'gradle-benchmark-fixture'.

Full Gradle Profiler log:
  <output>/raw/profile.log
```

## What to review

| # | Artifact | What it tells you |
|---|---|---|
| 1 | `RawBenchmarkExtractor.kt` | Where the model comes from and why |
| 2 | `BenchmarkExecutor.kt` | Failure detection; every branch is a hard failure |
| 3 | `engine/src/test/resources/profiler-output/` | Real 0.25.2 reports, one successful and one failed, used as fixtures |
| 4 | `engine/src/integrationTest/` | Real profiler, real Gradle, both paths |

Unit tests parse captured real reports rather than synthesized HTML, so they cannot pass
against a format we invented. The integration tests are what would notice the real tool
changing; one asserts the profiler version explicitly, so when a release does write
`benchmark.json` the workaround can be removed deliberately rather than forgotten.

## Verification

```bash
./gradlew build                      # 125 unit tests
./gradlew :engine:integrationTest    # real profiler, real Gradle builds
```

See the local validation walkthrough in the pull request for end-to-end CLI checks.

## Selecting individual scenarios

Running one scenario previously meant adding a `scenario-groups` block to the user's own
file, which is a poor trade for a faster feedback loop. `--scenario <name>`, repeatable,
now selects directly:

```bash
gradle-benchmark run --scenario-file performance.scenarios --project-dir . \
  --scenario assemble_incremental
```

Gradle Profiler already accepts scenario names as *non-option* arguments, so they are
appended last, where it expects them. A test asserts that position, because putting them
anywhere else silently stops them being parsed.

It also rejects combining names with `--group` on its own, and its message is clearer than
one we would write, so that combination is passed through rather than pre-empted:

```
Cannot specify both --group and individual scenario names.
Use either only '--group x' OR specify scenario names directly.
```

## Findings for Milestone 4, from a real Android project

Running against a real project (Android, Gradle 9.3.1, an `apply-abi-change-to` scenario)
surfaced something the fixture could not, because the fixture declares no mutators.

**`mutators` contains absolute paths, and the plan puts it in `workloadIdentityHash`.**

```
ApplyAbiChangeToSourceFileMutator(/Users/phundal/AndroidStudioProjects/Numverify/./app/src/main/java/...)
```

The plan guards `gradleHome` and `javaHome` against exactly this, and misses `mutators`.
Any scenario using `apply-abi-change-to`, `apply-non-abi-change-to` or the resource-change
mutators embeds the project's absolute path, which means:

- the same benchmark hashes differently on a laptop and on CI
- every historical comparison returns `BASELINE_INCOMPATIBLE`
- the failure is silent and total, which is precisely the risk the plan flags as highest

Note also the `/./` in the path above, produced by `--project-dir .`. Two runs on the *same*
machine differ if one passes a relative project directory and the other an absolute one.

**Required in Milestone 4:** normalize mutator strings by relativizing embedded paths
against the project directory before hashing, so the meaningful part (which file the
benchmark mutates, which genuinely is workload identity) is kept and the machine-specific
prefix is dropped. The M4 hash invariants must gain a case covering it.

**Also observed, for the noise discussion deferred by section 10.** Warm-up exclusion is
doing more work than the fixture suggested: the clean build's warm-up was 44.3s against
measured builds of 2.3s and 2.0s. Including warm-ups would inflate the mean 7.5x. Median is
far more robust (2.30s versus 2.15s), which supports it as the default statistic.

Measured spread was 13.2% and 10.1% of the median at 2 and 3 iterations. A 5% regression
threshold sits well inside that, which is a concrete illustration of section 10's warning
that a percentage threshold is a tolerance policy and not statistical significance.

## Known gaps

- **No `run.json`.** The normalized model, statistics, environment capture and
  `workloadIdentityHash` are Milestone 4. This milestone stops at the raw result.
- **No comparison.** Naming `--baseline-scenario` validates it and says plainly that
  comparison is not implemented; no `comparison.json` or `report.html` is produced.
- **`--timeout-minutes` is parsed but not applied.** `ProcessGradleProfiler` supports a
  timeout; the CLI does not yet pass it through.
