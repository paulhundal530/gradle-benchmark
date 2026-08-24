# Regression definition: percentage threshold versus statistical test

**Status:** design note. Not implemented. Percentage threshold remains the Milestone 5
policy; this records the case for a second method and what it would require.

## The problem with a percentage threshold alone

Section 10 warns that a percentage threshold is a user-facing tolerance policy and must not
be presented as statistical significance. Real data makes that concrete.

Benchmarking a real Android project produced measured spreads of **13.2%** and **10.1%** of
the median, at 2 and 3 iterations. A 5% regression threshold sits well inside that noise, so
the gate would fire on variance rather than on a real change.

Raising the threshold until it clears the noise is not a fix: it makes the tool blind to
genuine regressions smaller than the noise floor, which on a slow build is exactly the size
of regression worth catching early.

## Gradle Profiler already does this

This is not a new concept to bolt on. Gradle Profiler's own HTML report computes a
**Mann-Whitney U test** and displays it as a *"Confidence of difference to baseline"*
column. The report bundles the `mann-whitney-utest` JavaScript library and calls it as:

```js
uTest: function (scenario, baseline, sample) {
    const samples = [
        measuredIterations(baseline).map(i => i.values[sample.name]),
        measuredIterations(scenario).map(i => i.values[sample.name]),
    ]
    const u = mwu.test(samples)
    const z = mwu.criticalValue(u, samples)
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))     // standard normal CDF
}
```

Three things follow from that.

**It reinforces the baseline fan-out model.** The report's confidence column only renders
when a baseline scenario is selected, and compares every other scenario against it. That is
independently the same model this project settled on.

**It uses the same measured-iteration filter we do.** Warm-ups are excluded on both sides.

**We can match its numbers exactly**, the same way statistics were matched to the profiler's
population standard deviation and R-7 quantiles. Its reported "confidence" is the normal
CDF of z, so a confidence of 0.975 corresponds to a two-tailed p of 0.05.

Note the profiler computes z by **normal approximation with tie correction**, which the
library itself documents as intended for samples larger than 20. At the sample sizes real
benchmarks use, an exact U distribution is more defensible. Matching the profiler's number
and being correct at small n may not be the same thing, and that tension needs resolving
before implementing.

## Why Mann-Whitney rather than a t-test

Build times are right-skewed and not normally distributed: a build cannot take less than
some floor, but can take arbitrarily longer when a daemon stalls or a cache misses.
Mann-Whitney is non-parametric and assumes no distribution, only that the two samples are
independent. It also tests a shift in distribution rather than a difference of means, which
is closer to the question actually being asked.

## The constraint that decides feasibility: sample size

A U test cannot report significance the sample size cannot support. Even when two samples
are *completely separated*, the smallest reachable two-tailed p is bounded by the number of
possible arrangements:

| Iterations per side | Arrangements | Smallest reachable p | Can reach p < 0.05 |
|---|---|---|---|
| 2 | 6 | 0.333 | no |
| 3 | 20 | 0.100 | no |
| 4 | 70 | 0.029 | **yes** |
| 5 | 252 | 0.008 | yes |
| 6 | 924 | 0.002 | yes |
| 8 | 12870 | 0.0002 | yes |

**Four measured iterations per side is the hard floor**, and five or more gives real margin.
Below that the test can never conclude, no matter how large the regression.

This has to be surfaced, not hidden. A tool that silently reports "no significant
regression" because the user configured `iterations = 3` would be worse than one that
reports nothing, because it reads as evidence of no regression rather than absence of
evidence.

## Statistical significance is not practical significance

With enough iterations, a 0.3% slowdown becomes statistically significant and is not worth
failing a build over. Any test-based method therefore needs **both** a significance
criterion and a minimum effect size:

> regression = the distributions differ significantly **and** the median moved by more than
> the minimum effect

Otherwise the method trades false positives from noise for false positives from triviality.

## Where this fits the existing design

The architecture already anticipates it. `comparisonPolicy` is a recorded object rather than
a bare threshold, so a method is an added field and not a schema break:

```json
{
  "comparisonPolicy": {
    "measurement": "build_execution_time",
    "statistic": "median",
    "method": "percent",
    "regressionThresholdPercent": 5.0
  }
}
```

```json
{
  "comparisonPolicy": {
    "measurement": "build_execution_time",
    "statistic": "median",
    "method": "mann_whitney_u",
    "significanceLevel": 0.05,
    "minimumEffectPercent": 3.0
  }
}
```

**`INCONCLUSIVE` finally earns its place.** It is reserved in the status enum and never
emitted, with measurement-protocol mismatch as its provisional first use. A statistical
method gives it a much better one: too few iterations, or a test that cannot distinguish the
distributions, is precisely "not enough evidence to conclude" — neither a pass nor a
regression. The rollup precedence already ranks it above `PASS` and below
`REGRESSION_PRESENT`, which is the correct ordering for that meaning.

## Open questions

- Exact U distribution at small n, or the profiler's normal approximation for numeric
  agreement with its report? They diverge exactly where real benchmarks live.
- One-tailed or two-tailed? Only slowdowns fail a build, but an unexplained speed-up is
  often a sign the benchmark stopped measuring what it used to.
- Does the default method change, or does `percent` stay the default with the test opt-in?
  Changing the default changes what every existing configuration means.
- Should a selection whose iteration count cannot support the configured method be rejected
  at `validate` time, the way an unknown baseline scenario already is?
