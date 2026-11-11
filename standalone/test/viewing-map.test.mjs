import { test } from "node:test";
import assert from "node:assert/strict";
import {
  aggregateViewingMap as aggregate,
  viewingMapCsv,
  viewingMapSvg,
  viewingMapHtml,
  viewingMapModule,
} from "../lib/viewing-map.mjs";
import { reportHtml } from "../lib/research.mjs";

const region = (overrides = {}) => ({
  filePath: "/demo.java",
  documentStamp: 1,
  firstLine: 1,
  lastLine: 4,
  label: "Method",
  text: "void f() {\n  a();\n  b();\n}",
  source: "WEBCAM",
  ...overrides,
});
const visit = (overrides = {}) => ({
  file: "/demo.java",
  inputKind: "WEBCAM",
  elapsedMs: 1000,
  dwellMs: 800,
  line: 2,
  lineText: "  a();",
  region: region(),
  ...overrides,
});
const session = (events, extra = {}) => ({
  id: "map-test",
  startMs: 10000,
  durationMs: 3000,
  events,
  configurationChanges: [],
  ...extra,
});
const recorded = (m) => m.rows.filter((r) => r.visits);

test("time intervals clip exposure and count each overlapping visit once, with exact boundaries", () => {
  const s = session([
    visit(),
    visit({ elapsedMs: 1500, dwellMs: 500 }),
    visit({ elapsedMs: 3000, dwellMs: 500 }),
  ]);
  const m = aggregate(s, { fromMs: 500, toMs: 1250 });
  assert.equal(recorded(m)[0].exposureMs, 750);
  assert.equal(recorded(m)[0].visits, 2);
  assert.equal(recorded(aggregate(s, { fromMs: 1500, toMs: 2500 })).length, 0);
  assert.equal(recorded(aggregate(s, { fromMs: 900, toMs: 900 })).length, 0);
});
test("brief visits survive in maps without the assistant's 200 ms threshold", () => {
  const row = recorded(
    aggregate(session([visit({ elapsedMs: 100, dwellMs: 80 })])),
  )[0];
  assert.equal(row.exposureMs, 80);
  assert.equal(row.visits, 1);
});
test("unsorted legacy visits cannot shorten the map's inferred duration", () => {
  const s = session([visit({ elapsedMs: 4000 }), visit({ elapsedMs: 1000 })], {
    durationMs: undefined,
  });
  const map = aggregate(s);
  assert.equal(map.toMs, 4000);
  assert.equal(recorded(map)[0].visits, 2);
});
test("source and captured document versions never merge; legacy mouse and simulation retain provenance", () => {
  const s = session([
    visit(),
    visit({ inputKind: "MOUSE_PROXY" }),
    visit({ region: region({ documentStamp: 2 }) }),
    visit({ inputKind: undefined, source: "WEBCAM", simulated: true }),
    visit({ inputKind: undefined, source: "MOUSE" }),
  ]);
  const rows = recorded(aggregate(s));
  assert.equal(rows.length, 4);
  assert.equal(rows.find((r) => r.inputKind === "MOUSE_PROXY").visits, 2);
  assert.equal(recorded(aggregate(s, { inputKind: "WEBCAM" })).length, 2);
  assert.equal(rows.find((r) => r.inputKind === "MOUSE_SIMULATION").visits, 1);
});
test("captured zero-exposure lines differ from missing content and preserve blank lines", () => {
  const s = session([
    visit({
      region: region({ text: "void f() {\n\n[region truncated]" }),
      lineText: undefined,
    }),
  ]);
  const rows = aggregate(s).rows;
  assert.equal(
    rows.find((r) => r.firstLine === 1).recordingState,
    "no_recorded_visit",
  );
  assert.equal(rows.find((r) => r.firstLine === 1).captureState, "captured");
  assert.equal(rows.find((r) => r.firstLine === 2).text, "");
  assert.equal(rows.find((r) => r.firstLine === 4).captureState, "missing");
  assert.ok(rows.some((r) => r.gap && r.firstLine === 3));
  assert.equal(
    aggregate(s, { mode: "regions" }).rows[0].captureState,
    "partial",
  );
});
test("nested regions count a visit once at its recorded region and preserve revisits", () => {
  const s = session([
    visit(),
    visit({
      line: 3,
      elapsedMs: 2000,
      region: region({
        firstLine: 3,
        lastLine: 3,
        text: "  b();",
        label: "Nested",
      }),
    }),
    visit({ elapsedMs: 3000 }),
  ]);
  const m = aggregate(s, { mode: "regions" });
  assert.equal(m.rows.length, 2);
  assert.equal(
    m.rows.reduce((n, r) => n + r.exposureMs, 0),
    2400,
  );
  assert.equal(m.rows.find((r) => r.label === "Method").visits, 2);
});
test("calibration summaries follow recording configuration intervals, preserve zero and omit future checks", () => {
  const initial = {
    inputKind: "WEBCAM",
    calibrated: true,
    tracking: {
      calibrationCheck: {
        checkedAtMs: 9000,
        p90VerticalPx: 0,
        jitter90Px: 2,
        samples: 50,
        targets: 5,
      },
    },
  };
  const next = { inputKind: "WEBCAM", calibrated: false, tracking: {} };
  const s = session([visit()], {
    metadata: { initialConfiguration: initial },
    configurationChanges: [{ elapsedMs: 2000, configuration: next }],
  });
  const early = aggregate(s, { toMs: 1500 });
  assert.equal(early.quality.length, 1);
  assert.equal(early.quality[0].calibrationCheck.p90VerticalPx, 0);
  assert.match(
    viewingMapModule().qualityText(early),
    /p90 vertical error 0 px/,
  );
  const late = aggregate(s, { fromMs: 2100 });
  assert.equal(late.quality[0].calibrationCheck, null);
  assert.match(
    viewingMapModule().qualityText(late),
    /No calibration-check metrics/,
  );
});
test("legacy timestamp events and missing calibration remain explicitly unknown", () => {
  const s = session([
    visit({
      elapsedMs: undefined,
      timestampMs: 11000,
      region: null,
      lineText: undefined,
    }),
  ]);
  const m = aggregate(s);
  assert.equal(recorded(m)[0].exposureMs, 800);
  assert.equal(m.rows[0].documentVersion, null);
  assert.equal(m.rows[0].captureState, "missing");
  assert.equal(m.quality[0].calibrated, null);
});
test("snapshot compaction preserves results and stores shared code once", () => {
  const s = session(
    Array.from({ length: 1000 }, (_, i) =>
      visit({ elapsedMs: i * 3 + 3, dwellMs: 3 }),
    ),
  );
  const compact = viewingMapModule().compact(s);
  assert.equal(compact.mapSnapshots.length, 1);
  assert.ok(!("region" in compact.events[0]));
  assert.deepEqual(aggregate(compact), aggregate(s));
});
test("portable HTML and SVG escape hostile paths and code; CSV neutralizes spreadsheet formulas", () => {
  const attack = "</script><img src=x onerror=alert(1)>";
  const s = session([
    visit({
      file: '=HYPERLINK("bad")',
      lineText: attack,
      region: region({ text: attack, label: attack }),
    }),
  ]);
  const html = viewingMapHtml(s),
    svg = viewingMapSvg(aggregate(s));
  assert.equal((html.match(/<script/g) || []).length, 1);
  assert.equal((html.match(/<\/script>/g) || []).length, 1);
  assert.ok(!html.includes(attack));
  assert.ok(!svg.includes("<img src=x"));
  assert.ok(svg.includes("&lt;"));
  assert.ok(viewingMapCsv(aggregate(s)).includes("'=HYPERLINK"));
});
test("SVG uses bounded pages, a common scale and explicit filters; JSON/CSV keep all rows", () => {
  const r = region({
    lastLine: 300,
    text: Array.from({ length: 300 }, (_, i) => `line ${i + 1}`).join("\n"),
  });
  const m = aggregate(session([visit({ region: r })]), {
    fromMs: 250,
    toMs: 1000,
  });
  const svg = viewingMapSvg(m, { page: 1, metric: "visits" });
  assert.equal(m.rows.length, 300);
  assert.equal((svg.match(/<rect x=/g) || []).length, 120);
  assert.ok(svg.includes("page 2"));
  assert.ok(svg.includes("common scale 0–1"));
  assert.equal(viewingMapCsv(m).trim().split("\n").length, 301);
});
test("HTML report map honors trace exclusions without changing original events", () => {
  const s = session([visit(), visit({ elapsedMs: 2000 })]);
  const html = reportHtml(s, { traceEdits: { 1: { pruned: true } } });
  assert.ok(html.includes('id="gazeaide-viewing-map"'));
  assert.ok(html.includes('"regionRef":0'));
  assert.equal(s.events.length, 2);
});
