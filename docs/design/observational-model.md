# The tool reports; it does not judge

**Status:** decided. Supersedes the verdict layer in
[interpretation-model.md](interpretation-model.md), whose observation half survives intact.

## What changed

Gradle Benchmark no longer decides whether a result is acceptable. It reports what was
measured, how precisely, and under what conditions. What to do about that is the user's
call.

```
configuration_cache_experiment

  main (cc disabled)      2.14s   baseline
  feature (cc enabled)    1.62s   -24.3%

  These runs differ in:
    args        [] vs [--configuration-cache]
    revision    a3f21c9 vs 8b04e7d

  3 warm-ups, 10 measured iterations per side
  The difference is well beyond what this experiment could resolve (±3.1%).
```

No status. No threshold. No exit code that means "regression".

## Why

Enforcement was tried and does not hold up. Simulating a nightly gate at realistic noise —
a 40-second build, the 6% coefficient of variation measured on a real project, eight
iterations, a genuine 4% regression landing on night ten:

```
night 10   REGRESSION    +8.8%
night 11   INCONCLUSIVE  +6.5%
night 12   REGRESSION    +8.2%
night 13   INCONCLUSIVE  +4.2%
night 15   REGRESSION    +8.3%
```

The same persistent regression alternated between verdicts night to night. A gate that
flickers is worse than no gate, because people learn to ignore it.

Three further things came out of the same simulation:

- `PASS` never fired once in twenty nights, so a baseline that advances on `PASS` never
  advanced, and the dual-horizon design collapsed into a single horizon.
- Observed deltas ranged +4.0% to +8.8% for a true 4% regression, because comparing against
  one run's median bakes that run's own noise in permanently.
- The iteration counts required are unforgiving. At 6% variation, resolving a difference
  takes roughly 9 iterations per side for 10%, 35 for 5%, and 97 for 3%.

That last one is not a defect to engineer around; it is what the measurements permit. A tool
claiming to catch 5% regressions from three iterations is lying. Given that, asserting a
verdict is claiming an authority the data does not grant.

Reporting has none of these problems. "24.3% faster, from 3 warm-ups and 10 measured
iterations, resolvable to ±3.1%" is true regardless of anyone's threshold, reproducible, and
leaves the decision where it belongs.

## What the tool still asserts

Facts about the experiment, not judgements about the result:

- the measured values and their statistics
- the difference between two sides
- **whether that difference exceeds what the experiment could resolve** — a statement about
  the measurement, not about whether the change is acceptable
- how many warm-ups and measured iterations produced it
- what differed between the two sides
- whether warm-up converged

## Differences are reported, never refused

Comparing two runs that differ in arguments, tasks, Gradle version or revision is allowed,
and every difference is listed beside the numbers.

Refusing would be enforcement wearing a different hat. It would also block the case this
tool now exists for: comparing configuration-cache-enabled against disabled is *precisely* a
comparison where the arguments differ, and that difference is the independent variable.

The risk is real and accepted: comparing a clean build against an incremental one produces
a true and worthless "-94% faster". Listing the differences prominently is what lets a user
notice, rather than the tool deciding for them.

## Reproducibility is the quality bar

"Run the same experiment twice and reach the same conclusion" is a property that can be
checked. "Is this a regression" is not.

The tool supports it by reporting the resolvable interval and sample sizes, so a user can
see whether a difference is beyond noise before believing it. Repetition is left to the
user; nothing in the tool pretends a single run is more than one sample.

## Consequences

Removed:

- `--fail-on-regression` and the exit code meaning regression
- `--regression-threshold-percent`, which has no purpose without a gate
- `PASS`, `REGRESSION` and `INCONCLUSIVE` as verdicts
- the `verdict` object in `comparison.json`
- baseline advancement, anchors and drift, which were all nightly concerns

Kept, and now the whole product rather than half of it:

- the observation: values, delta, resolvable interval, sample sizes, warm-up counts
- diagnostics about experiment quality
- exit codes for a failed benchmark and for invalid input

## Nightly monitoring

Tabled. It may be built later on top of the observational output, or not at all. Nothing in
the current design forecloses it: a team wanting a gate can write one over
`comparison.json`, using a threshold they chose for a scenario they understand, which is
where that decision belonged in the first place.
