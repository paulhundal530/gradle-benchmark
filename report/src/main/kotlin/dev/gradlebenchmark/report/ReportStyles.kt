package dev.gradlebenchmark.report

/**
 * Styles for the generated report.
 *
 * Inlined rather than linked: the report must be a single file that opens from a CI
 * artifact with no network access, which rules out webfonts and CDNs. Colours come from a
 * small token set so both themes stay consistent, and both are defined because a report
 * lands in whatever the reader's system happens to be set to.
 */
internal val REPORT_STYLES: String = """
:root {
  --ground: #fbfbfa;
  --surface: #ffffff;
  --ink: #16181d;
  --ink-muted: #5b616e;
  --ink-faint: #8b909c;
  --rule: #e3e5ea;
  --accent: #2f5d8a;
  --warm: #b0784a;
  --flag: #9a4b3f;
  --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}
@media (prefers-color-scheme: dark) {
  :root {
    --ground: #14161a;
    --surface: #1b1e24;
    --ink: #e6e8ec;
    --ink-muted: #a2a8b5;
    --ink-faint: #767c8a;
    --rule: #2c313a;
    --accent: #7fb0dd;
    --warm: #d3a273;
    --flag: #e08b7e;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 40px 24px 64px;
  background: var(--ground);
  color: var(--ink);
  font-family: var(--sans);
  font-size: 15px;
  line-height: 1.55;
}
main { max-width: 760px; margin: 0 auto; }
h1 { font-size: 1.5rem; font-weight: 620; margin: 0 0 4px; letter-spacing: -0.01em; }
h2 { font-size: 0.78rem; font-weight: 660; text-transform: uppercase; letter-spacing: 0.08em;
     color: var(--ink-muted); margin: 40px 0 12px; }
.subtitle { color: var(--ink-muted); margin: 0 0 32px; font-family: var(--mono); font-size: 0.82rem; }
.scenario {
  background: var(--surface); border: 1px solid var(--rule); border-radius: 6px;
  padding: 20px 22px; margin-bottom: 16px;
}
.scenario-name { font-weight: 620; font-size: 1.05rem; margin: 0 0 2px; }
.scenario-title { color: var(--ink-faint); font-size: 0.85rem; margin: 0 0 16px; }
.headline { display: flex; align-items: baseline; gap: 14px; flex-wrap: wrap; margin-bottom: 4px; }
.values { font-family: var(--mono); font-size: 1.05rem; color: var(--ink-muted); }
.delta { font-family: var(--mono); font-size: 1.5rem; font-weight: 600; letter-spacing: -0.01em; }
.faster { color: var(--accent); }
.slower { color: var(--warm); }
.flat { color: var(--ink-muted); }
/* Resolution sits directly under the number, at nearly the same weight, because a
   difference the experiment could not resolve is not a finding. */
.resolution { font-size: 0.9rem; margin: 6px 0 0; }
.resolved { color: var(--ink); }
.unresolved { color: var(--flag); }
.effort { color: var(--ink-faint); font-size: 0.82rem; font-family: var(--mono); margin: 2px 0 0; }
.chart { width: 100%; height: auto; margin: 18px 0 4px; display: block; }
.chart .warmup { fill: none; stroke: var(--warm); stroke-width: 1.5; opacity: 0.55; }
.chart .measured { fill: none; stroke: var(--accent); stroke-width: 1.75; }
.chart .point { fill: var(--accent); }
.chart .median { stroke: var(--ink-faint); stroke-width: 1; stroke-dasharray: 3 3; opacity: 0.6; }
.chart .boundary { stroke: var(--rule); stroke-width: 1; }
.chart .axis { fill: var(--ink-faint); font-family: var(--mono); font-size: 9px; }
.chart .offscale { fill: var(--warm); }
.differences { margin: 16px 0 0; padding: 12px 14px; border-left: 2px solid var(--rule);
               font-size: 0.86rem; }
.differences dt { font-family: var(--mono); color: var(--ink-muted); font-size: 0.8rem; }
.differences dd { margin: 2px 0 10px; font-family: var(--mono); font-size: 0.82rem; }
.differences dd:last-child { margin-bottom: 0; }
.changes-work { color: var(--flag); }
details { margin-top: 16px; }
summary { cursor: pointer; font-size: 0.84rem; color: var(--ink-muted); }
table { border-collapse: collapse; width: 100%; margin-top: 10px; font-family: var(--mono);
        font-size: 0.8rem; font-variant-numeric: tabular-nums; }
th, td { text-align: right; padding: 5px 8px; border-bottom: 1px solid var(--rule); }
th:first-child, td:first-child { text-align: left; }
th { color: var(--ink-faint); font-weight: 500; }
.diagnostics { border-left: 2px solid var(--flag); padding: 4px 0 4px 14px; }
.diagnostics p { margin: 0 0 8px; font-size: 0.88rem; color: var(--ink-muted); }
.diagnostics p:last-child { margin-bottom: 0; }
.meta { font-family: var(--mono); font-size: 0.78rem; color: var(--ink-faint); }
.meta div { margin-bottom: 3px; }
.note { color: var(--ink-muted); font-size: 0.88rem; }
""".trimIndent()
