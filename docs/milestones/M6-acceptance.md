# Milestone 6 — HTML report

**Scope:** the human-facing report, rendered from the normalized model.

**Status:** complete. Produces `report.html`.

## What it shows

For each scenario compared: the delta, both measured values, **what the experiment could
resolve**, the warm-up and iteration counts, a chart of the iteration series, any
differences between the two sides, and full statistics behind a disclosure.

Then run diagnostics, then environment metadata.

```
Benchmark comparison
39bd3be → a6adc53  ·  total execution time

configuration_only
  -44.2%    118ms → 66ms
  Beyond what this experiment could resolve (±25.5%).
  3 warm-ups, 5 measured iterations per side
  [chart]
```

## Resolution is as prominent as the delta

Directly beneath the number, at nearly the same weight, and coloured differently when the
difference could not be resolved. A report that showed only a percentage would quietly
reintroduce the false confidence the previous milestone was spent removing, so the two are
deliberately inseparable.

A difference the run could not resolve says so, and says what that does *not* mean:

> Within what this experiment could resolve (±29.5%), so not separable from noise. That is
> not evidence of no change.

There is no verdict language anywhere. A test asserts the report never contains
`REGRESSION`, `PASS`, `FAIL` or `threshold`.

## The chart, and the scale problem it exposed

The chart plots warm-ups and measured iterations on one axis, with the measured median as a
reference line. It exists because convergence is the thing a table cannot show: a series
reading `5872, 580, 469, 450, 444, 399` is obviously still falling when drawn, and easy to
skim past as text.

The first attempt was useless. Checking the geometry against real data:

```
warm-ups: [3887, 83, 84]   measured: [68, 68, 66, 43, 51]
  warmup    y 8-102
  measured  y 102-103      <- one pixel of movement
```

Scaling to the first warm-up collapsed the entire measured series into a flat line. The
scale now follows the measured values, keeps warm-ups close enough to be informative about
convergence, and clamps the rest to the top edge where they are labelled with their value so
a pinned line is never mistaken for a measurement:

```
  warmup    y 8-29
  measured  y 50-84        <- 34 pixels, variation visible
  off-scale warm-ups marked: [3887]
```

## Self-contained and deterministic

No scripts, no stylesheets, no external requests, no webfonts — a report is usually opened
straight out of a CI artifact with no network. Charts are inline SVG for the same reason.

Rendering is deterministic: coordinates are fixed-precision and nothing is generated at
render time, so the same input produces byte-identical output and the golden tests mean
something.

Scenario names and titles come from user files, so everything is escaped. A scenario named
`<script>alert(1)</script>` renders as text.

## Also covered

`run` without a named baseline now writes a report too, showing the measurements and stating
plainly that nothing was compared rather than implying a comparison.

The statistics table labels its rows by whatever actually distinguishes the two sides: the
scenario name in a variant comparison, the revision across two runs, where both sides share
a name.

## Verification

```bash
./gradlew build   # includes 20 report tests
```

Section 23.4 asks that the report's important content cannot be accidentally removed. Each
element above is pinned by a test.

## Known gaps

- **No index across scenarios.** A run with many scenarios is a long scroll; there is no
  summary table at the top.
- **The chart shows one measurement.** A scenario measuring GC time as well as execution
  time renders only the compared measurement.
