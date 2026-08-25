# Milestone 5 — Variant comparison

**Scope:** the comparison model, delta calculation, regression policy, per-scenario and
overall status, and machine-readable comparison output. Produces `comparison.json`.

**Status:** complete, then substantially reworked.

Originally implemented the two-layer model in
[interpretation-model.md](../design/interpretation-model.md). The verdict half was then
removed: see [observational-model.md](../design/observational-model.md) for why, and
[M5b-acceptance.md](M5b-acceptance.md) for what the milestone actually delivers now.

The sections below describing statuses, thresholds and enforcement are retained as a record
of what was tried, and no longer describe the tool.

## Two layers

`comparison.json` reports what was measured separately from what a policy makes of it:

```json
"observation": {
  "baselineValue": 48.8, "candidateValue": 43.6, "unit": "ms",
  "deltaPercent": -10.57, "direction": "INDISTINGUISHABLE",
  "resolvablePercent": 33.58, "distinguishable": false,
  "baselineSampleSize": 2, "candidateSampleSize": 2,
  "resolutionReliable": false
},
"verdict": {
  "status": "INCONCLUSIVE",
  "policy": { "method": "PERCENT", "regressionThresholdPercent": 5.0 },
  "explanation": "-10.6% observed, with only 2 and 2 measured iterations. ..."
}
```

The observation is always populated and never depends on configuration, so a threshold
change never rewrites what was measured.

## The three-way rule

```
delta > threshold and distinguishable   -> REGRESSION
resolution reliable and
  delta + resolvable <= threshold       -> PASS
otherwise                               -> INCONCLUSIVE
```

Passing requires the *whole* interval to sit within tolerance, not just the point estimate.
A 1% delta on a run that can only resolve 8.8% is not a pass against a 5% threshold,
because the true difference could be 8%. Equally, a delta above the threshold that the run
cannot distinguish is not a regression: reporting one would assert a finding the experiment
did not support.

`INCONCLUSIVE` is now emitted, and means something specific: the difference is smaller than
the experiment could resolve. The numbers are still reported; what is withheld is the claim
that nothing changed.

## A finding from running it

`resolvablePercent` came out **33.6% at two iterations and 41.0% at twelve** for the same
scenario. More data appeared to make resolution worse.

It did not. Resampling two measurements can only produce three distinct medians, so the
interval comes out narrow no matter how noisy the build actually is. The two-iteration run
had simply been unable to see its own noise.

That matters because the estimate is optimistic exactly where it is most dangerous. So
`resolutionReliable` records whether the interval can be trusted, and a verdict may not
**pass** on an untrustworthy one. A clear regression is still reported: too little data to
rule a change out is not too little data to notice a large one.

## Diagnostics are separate from verdicts

Run quality is reported apart from outcomes, because a confident verdict over bad data is
worse than saying the data was bad:

```
! baseline: warm-up had not converged (last warm-up 2149ms against a median of 49ms).
  Measurements are likely inflated; consider more warm-ups.
! baseline: only 2 measured iterations, so small differences cannot be resolved.
```

This is why `run.json` now retains warm-up values. They are the only evidence that warm-up
converged, and they were previously discarded.

## Wording follows the mode

The status in the JSON is identical across modes; only the presentation differs.
Deliberately benchmarking a slower configuration is an experimental result, not a fault, so
variant mode says *slower* where nightly monitoring will say *regression*.

## Verified end to end

The fixture gained a `slowWork` task, roughly 250ms slower than `work`, so comparison tests
assert a difference far beyond machine noise rather than hoping one appears:

```
  slow                              336ms   +678.5%  slower
1 of 1 exceeded the 5.0% threshold.

exit 0 by default, exit 2 with --fail-on-regression
```

The task needed `outputs.upToDateWhen { false }`. Without it Gradle marked it up to date
after the first build and the delay never ran, which made the fixture silently useless the
first time.

## Known gaps

- **No HTML report.** Milestone 6.
- **Single horizon.** Both sides come from one run here. The second horizon and the
  committed anchor need two runs and arrive in Milestone 7.
- **`MANN_WHITNEY_U` is reserved but unimplemented.** It cannot conclude below four
  iterations per side whatever the effect size.
- **No minimum effect size.** Whether one is needed separately is still open: when the
  resolvable interval already exceeds the threshold the verdict is `INCONCLUSIVE` anyway,
  which may make an absolute floor redundant.
