# The interpretation model

**Status:** decided. Supersedes the baseline-advancement rule recorded in section 19 of the
brief, which is shown below to be unsound.

This is the design the comparison layer is built on. It exists because three problems
surfaced from real measurements, and none of them is solved by a percentage threshold.

## Problem 1: a percentage threshold is unusable on fast scenarios

Measured jitter on a real Android incremental build, once properly warmed, is about ±20ms
around a 330ms median. In absolute terms that is stable. In relative terms it is not:

| Scenario | Median | What one 60ms GC pause costs |
|---|---|---|
| Incremental build | 334ms | **18.0%** |
| Clean build | 2,154ms | 2.8% |
| Large app clean build | 60,000ms | 0.1% |

A 5% threshold on a 334ms build is 17ms, which sits inside the noise floor. The same 5% on
a 60-second build is 3 seconds, comfortably outside it. **The same threshold means
completely different things depending on how fast the scenario is.**

## Problem 2: iteration counts are guessed, and the cost is severe

Ten warm-ups and ten iterations is not affordable nightly on a large repository, and an
expensive run that produces a wrong answer is the worst outcome of all.

The counts cannot be chosen correctly in advance. They can, however, be *evaluated
afterwards from data every run already produces*, at no additional build cost:

- **Did warm-up converge?** A real run's warm-ups went `5872, 580, 469, 450, 444, 399` —
  still falling at the sixth. Detectable automatically.
- **What could this run resolve?** Bootstrapped from the measured samples:

| Iterations | Smallest detectable difference | Builds |
|---|---|---|
| 2 | ~8.8% | 12 |
| 10 | ~4.1% | 20 |
| 20 | ~2.4% | 30 |
| 40 | ~0.6% | 50 |

This inverts the question. Nobody should guess iteration counts; they should decide what
effect size matters and read off the cost. If only regressions above 10% matter, two
iterations is genuinely enough — the failure today is not running too few iterations, it is
not knowing what a run could detect.

Warm-up values must therefore be retained in `run.json`. They currently are not, and they
are the only evidence that warm-up converged.

## Problem 3: drift accumulates invisibly

Section 19 specifies that a `PASS` advances the baseline. Simulating 1.5% daily drift
against a 5% threshold for thirty days:

```
auto-advancing baseline : alerts on days NONE
                          build is 1.56x slower after 30 days
anchored baseline       : first alert on day 4
```

**A build can become 56% slower with no alert ever firing**, because each day's small
regression silently becomes the next day's baseline. The rule that prevents a *persisting*
regression from vanishing is the same rule that lets *accumulating* ones hide.

Anchoring alone is not the answer either. It fails in the mirror direction:

```
anchor              100s
after optimisation   80s   vs anchor -20.0%  -> PASS (correctly)
after regression     92s   vs anchor  -8.0%  -> PASS   <-- a real 15% regression, missed
                                 vs previous run +15.0%  -> caught
```

Neither horizon is sufficient on its own.

---

# The model

## Two layers: observation and verdict

The tool reports what it measured, separately from what anyone decided that means.

```json
{
  "name": "assemble_incremental",
  "observation": {
    "baselineValueMs": 334.0,
    "candidateValueMs": 381.0,
    "deltaPercent": 14.2,
    "direction": "SLOWER",
    "resolvablePercent": 4.1,
    "distinguishable": true
  },
  "verdict": {
    "status": "REGRESSION",
    "policy": { "method": "percent", "regressionThresholdPercent": 5.0 }
  }
}
```

The observation is always populated and never depends on configuration. A platform team
reading the JSON gets signal regardless of anyone's threshold; CI gating reads `verdict`
only when it is turned on.

`resolvablePercent` is the smallest difference this run's sample size could distinguish,
derived from the measured values rather than assumed.

### INCONCLUSIVE means something specific

Not "no data". It means the observed difference is smaller than the experiment could
resolve, and the numbers are still shown:

```
observed delta:     +2.1%  (334ms -> 341ms)
resolvable at n=3:  ±8.8%
verdict:            INCONCLUSIVE

This experiment cannot resolve a 2.1% difference.
That is not evidence the change is harmless.
```

The alternative — reporting `PASS` — quietly asserts something the data does not support.

## Dual horizon

A nightly run compares against two references, because each catches what the other misses:

| Horizon | Reference | Catches | Blind to |
|---|---|---|---|
| Short | previous accepted run | step changes; never goes stale | gradual drift |
| Long | committed anchor | accumulated drift | regressions after an unclaimed improvement |

```
Incremental Build
  vs previous run   +1.8%   within tolerance
  vs anchor (v2.4)  +8.3%   DRIFT

Build has drifted 8.3% since the anchor was set 23 runs ago,
in increments that individually passed.
```

## The anchor is a committed file

Accepting a regression is a deliberate, reviewable decision, so it lives in the repository:

```json
{
  "acceptedAt": "2026-08-24",
  "reason": "AGP 9 upgrade, +6% accepted",
  "runId": "...",
  "scenarios": { "assemble_clean": 2154.0 }
}
```

Moving it is a pull request: attributed, reviewed, and visible in git history. For a
platform team that is usually the point — a silent reset in CI leaves no trace of who
decided a regression was acceptable, or why.

---

## Open questions

**Anchor validity across environments.** A committed anchor records absolute values, which
are only meaningful in the environment that produced them. An anchor recorded on a laptop
is not comparable to a CI run on Linux. Either the anchor is keyed by execution
environment, or anchors must be produced by CI and the CLI must refuse an anchor whose
environment does not match. This interacts directly with the compatibility tiers and needs
resolving before the anchor file is implemented.

**How `resolvablePercent` is computed.** Bootstrapping the median is straightforward and
distribution-free but needs a fixed seed to stay deterministic, which byte-identical output
requires. An order-statistic interval avoids randomness entirely but is coarse at small n.

**Threshold defaults for the long horizon.** Drift tolerance is not the same quantity as
per-run tolerance and should probably not share a default.

**Minimum effect size.** Problem 1 argues for an absolute floor beneath which nothing is
reported as changed, so fast scenarios are not permanently ungateable. Whether that is a
separate policy or falls out of `resolvablePercent` is unresolved: if the resolvable
interval already exceeds the threshold, the verdict is `INCONCLUSIVE` anyway, which may
make an explicit floor redundant.

## Milestone impact

- **M5** delivers the two-layer model and variant comparison within a single run. Dual
  horizon does not apply there, since both sides come from the same run, but the schema
  must accommodate it now rather than after publication.
- **M7** delivers the second horizon and the anchor file, because both require two runs.
- Retaining warm-up values is a change to `run.json` and should land in M5 alongside the
  schema, not later.
- Section 19 of the brief must be corrected: `PASS` advancing the baseline is the mechanism
  that hides drift.
