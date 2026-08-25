# Milestone 5b — Comparison, without judgement

**Scope:** reworks Milestone 5 to report rather than judge, and adds comparison of two
separate runs.

**Status:** complete.

Implements [observational-model.md](../design/observational-model.md).

## What the tool does now

```
Compared a3f21c9 against 8b04e7d:

  configuration                 1.62s    -24.3%
    beyond what this experiment could resolve (±3.1%)
    3 warm-ups, 10 measured iterations per side
    differs in:
      args: [] -> [--configuration-cache]
```

No status. No threshold. No exit code meaning regression.

## Removed

- `--regression-threshold-percent` and `--fail-on-regression`
- exit code 2, and exit code 4
- `PASS`, `REGRESSION`, `INCONCLUSIVE` as verdicts
- `Verdict`, `RegressionPolicy`, `ComparisonPolicy`, `ScenarioStatus`, `ComparisonStatus`
- baseline advancement, anchors and drift, which were nightly concerns

The observation layer survives as the whole product. It is the half that held up against
real data.

## Added

**`compare --baseline a/run.json --candidate b/run.json`** — the case the tool mainly exists
for. Scenarios are matched by name across the two runs; one present in only one run is noted
rather than treated as an error, since a branch may legitimately add or remove one.

**Differences are reported, never refused.** Every field that differs between the two sides
is listed beside the numbers, and those that change the work performed rather than its
configuration are marked:

```
    differs in:
      tasks: assembleDebug -> clean assembleDebug (changes the work performed)
```

Refusing would be enforcement in a different costume, and it would block the case the tool
exists for: comparing configuration-cache-enabled against disabled is precisely a comparison
where arguments differ.

**Warm-up counts are reported** alongside sample sizes, because "24.3% faster from 3
warm-ups and 10 measured iterations" is a claim someone can reproduce, where the percentage
alone is not.

**Machine differences are flagged**, since absolute values are not comparable across
hardware. Flagged, not refused.

## Why enforcement was abandoned

Simulating a nightly gate at realistic noise — a 40-second build, the 6% variation measured
on a real project, eight iterations, a genuine 4% regression landing on night ten:

```
night 10   REGRESSION    +8.8%
night 11   INCONCLUSIVE  +6.5%
night 12   REGRESSION    +8.2%
night 13   INCONCLUSIVE  +4.2%
```

The same persistent regression alternated verdicts night to night. A gate that flickers is
worse than no gate.

The same simulation showed `PASS` never firing across twenty nights, so a baseline advancing
on `PASS` never advanced; and observed deltas ranging +4.0% to +8.8% for a true 4%
regression, because comparing against one run's median bakes that run's noise in
permanently.

## Verified end to end

Two runs of the same scenario, differing only in `--configuration-cache`:

```
Compared two runs at b27b568:

  configuration                   31ms    -26.7%
    within what this experiment could resolve (±29.5%), so not separable from noise
    3 warm-ups, 8 measured iterations per side
    differs in:
      args: [] -> [--configuration-cache]
```

The fixture is too fast for 26.7% to be resolvable at eight iterations, and the tool says so
rather than reporting a difference it cannot support. That is the behaviour the model exists
to produce.

## Known gaps

- **No HTML report.** Still the next thing.
- **Nightly monitoring is tabled.** Nothing forecloses it; a team can gate on
  `comparison.json` today with a threshold they chose.
- **Repetition is left to the user.** The tool reports what a run could resolve; running the
  experiment several times to confirm a conclusion is manual.
