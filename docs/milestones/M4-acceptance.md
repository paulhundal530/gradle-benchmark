# Milestone 4 — BenchmarkRun model

**Scope:** normalized run model, versioned schema, environment metadata, revision metadata,
workload identity, measurement protocol, and Gradle Profiler result normalization.

**Status:** complete. Produces `run.json`.

## What to review

| # | Artifact | What it tells you |
|---|---|---|
| 1 | `WorkloadIdentity.kt` | What counts as identity, what does not, and why |
| 2 | `Statistics.kt` | Statistics we compute, matching the profiler's own definitions |
| 3 | `BenchmarkRunAssembler.kt` | How raw output becomes the normalized model |
| 4 | `RunJsonWriterTest.kt` | The published schema keys, pinned |

## Statistics are computed, not read

Released Gradle Profiler computes no statistics at all, so they are computed here. The
definitions deliberately match the ones its HTML report uses, so a user reading our
`run.json` and the report sitting beside it sees the same numbers:

- quantiles by linear interpolation, type R-7
- **population** standard deviation, dividing by n rather than n-1

Both were read out of the report's own JavaScript rather than assumed.

Warm-ups are excluded. On a real Android project the clean build's warm-up was 44.3s
against measured builds of 2.3s and 2.0s; including warm-ups would have inflated the mean
7.5x.

Raw measured values are retained alongside the summary. A statistical regression method
needs the samples, not a summary, and they cannot be recovered later. See
[the regression methods note](../design/regression-methods.md).

## The four metadata tiers, realized

| Tier | Where it lives in `run.json` | On difference |
|---|---|---|
| Execution environment | `executionEnvironment` | blocks (Milestone 7) |
| Workload identity | `workloadIdentityHash` + `workloadIdentity` | blocks |
| Measurement protocol | `measurementProtocol` | blocks in V1 |
| Workload configuration | `workloadConfiguration` | **reported, never blocks** |

Identity is recorded alongside its hash, not just fingerprinted. An incompatible comparison
has to name the field that differed; "the hashes differ" would be true and useless.

`gradleHome` and `javaHome` appear nowhere in `run.json`. They are absolute paths that vary
by machine, and the only thing they could do is make comparisons fail.

## The mutator fix

Found by benchmarking a real Android project, which the fixture could not have surfaced
because it declares no mutators. Gradle Profiler describes a mutator as:

```
ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/./app/src/main/java/Repo.kt)
```

The embedded absolute path would have made the same benchmark fingerprint differently on a
laptop and on CI, so every historical comparison would report an incompatible baseline —
silently, which is the failure mode the plan calls its highest risk.

Paths are now relativized against the project directory before hashing. *Which* file a
benchmark mutates genuinely is part of its identity, so the path is rewritten rather than
dropped. Normalizing also collapses the `./` segment that `--project-dir .` produces, so a
relative and an absolute project directory agree.

Verified on the real project. Before:

```
ApplyAbiChangeToSourceFileMutator(/Users/phundal/AndroidStudioProjects/Numverify/./app/src/main/java/.../NumberValidationRepository.kt)
```

After:

```
ApplyAbiChangeToSourceFileMutator(app/src/main/java/.../NumberValidationRepository.kt)
```

## Hash invariants

Each is a test, because every one of them fails silently:

| Invariant | Why |
|---|---|
| Same benchmark on a laptop and on CI hashes identically | Otherwise every nightly comparison is incompatible |
| A Gradle or JVM upgrade does not change identity | Otherwise the most valuable regression signal is discarded |
| Adding `--rerun-tasks` **does** change identity | Otherwise a changed benchmark reports a 45% regression |
| Mutating a different file **does** change identity | Otherwise unrelated benchmarks compare as one |
| Distinct argument lists never share a hash | `["--ab","--c"]` and `["--a","b--c"]` must not collide |

The last one is why the canonical rendering separates fields with ASCII control characters
rather than a printable delimiter: a comma or space can occur inside an argument, and a
separator that can appear in a value is not a separator.

## Verified end to end on a real project

```
scenario: assemble_incremental
  identity.mutators: [ApplyAbiChangeToSourceFileMutator(app/src/main/java/.../NumberValidationRepository.kt)]
  configuration:     {gradleVersion: 9.3.1, buildJvmVersion: 21.0.11, usesScanPlugin: false}
  protocol:          {warmUpCount: 2, measuredIterationCount: 3, profilerVersion: 0.25.2}
  measurement:       total execution time (ms)
  values:            [491.94, 437.05, 418.43]
  statistics:        median 437.05, mean 449.14, stddev 31.20
```

`buildJvmVersion` is resolved by executing `<javaHome>/bin/java -version`, because the
profiler reports only a path. An unresolvable JDK records absent rather than failing the
run: the version is metadata, not a prerequisite.

`toolVersion` is generated from the Gradle project version rather than a constant somebody
has to remember to bump.

## Determinism

`runId` and `timestamp` are supplied to the assembler rather than generated inside it, so
the same input produces an equal result and tests are meaningful. `run.json` is written
with defaults encoded, so a field equal to its default still appears and the shape never
varies between runs.

## Verification

```bash
./gradlew build                      # 181 tests
./gradlew :engine:integrationTest    # real profiler, real Gradle builds
```

## Known gaps

- **No comparison.** `comparison.json` and the HTML report are Milestones 5 and 6. Naming
  `--baseline-scenario` validates it and says so plainly.
- **`--timeout-minutes` is parsed but not applied.**
- **Revision is captured, not interpreted.** Used from Milestone 10.
