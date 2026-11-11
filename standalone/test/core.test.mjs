import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import {
  region,
  identifier,
  occurrences,
  PointFilter,
  LineTarget,
  RegionDwell,
  History,
  Engine,
  features,
  affine,
  correct,
  prompt,
  csv,
  key,
  evaluate,
  chooseResponse,
} from "../lib/core.mjs";
import { reduced, reportHtml } from "../lib/research.mjs";
const file = {
  path: "demo.java",
  version: 123,
  text: "class Demo {\n  int caller() {\n    return validator();\n  }\n  int validator() {\n    return 0;\n  }\n}",
};
const r = {
  ...region(file.text, 2),
  filePath: file.path,
  documentStamp: 123,
  source: "WEBCAM",
};
test("lexical region selection ignores braces inside strings and comments", () => {
  assert.equal(r.firstLine, 2);
  assert.equal(r.lastLine, 4);
  const x = region('function a() {\n let x="}"; // }\n return x;\n}', 2);
  assert.equal(x.lastLine, 4);
  assert.equal(identifier("return itemCount;", 8), "itemCount");
  assert.equal(identifier("return itemCount;", 2), null);
  assert.deepEqual(occurrences('x + xx + "x" // x', "x"), [[0, 1]]);
});
test("Python indentation and bounded fallback preserve region limits", () => {
  assert.equal(
    region("def foo():\n    return 1\n\ndef bar():\n    pass", 1).firstLine,
    1,
  );
  assert.equal(region(Array(20).fill("plain").join("\n"), 14).firstLine, 9);
  assert.ok(
    region("{\n" + "hello\n".repeat(2000) + "}", 100).text.length < 6100,
  );
});
test("median/time filter matches duplicate timestamp, gap and invalid-sample behavior", () => {
  const f = new PointFilter();
  assert.deepEqual(f.update(0, 0, 100), { x: 0, y: 0 });
  assert.deepEqual(f.update(900, 900, 100), { x: 0, y: 0 });
  assert.ok(f.update(10, 10, 200).x < 10);
  assert.deepEqual(f.update(90, 90, 900), { x: 90, y: 90 });
  assert.equal(f.update(NaN, 1, 950), null);
});
test("line hold and hysteresis reject jitter at boundaries", () => {
  const target = new LineTarget();
  assert.equal(target.update(1, 30, 30, 20, 0, 100), -1);
  assert.equal(target.update(1, 30, 30, 20, 100, 100), 1);
  assert.equal(target.update(2, 42, 50, 20, 150, 100), 1);
  assert.equal(target.update(3, 70, 70, 20, 200, 100), -1);
  assert.equal(target.update(3, 70, 70, 20, 310, 100), 3);
});
test("region dwell accumulates line changes, excludes pending intervals and prepares once", () => {
  const d = new RegionDwell();
  d.observe(r, 100);
  d.observe({ ...r }, 400);
  d.pause(600);
  assert.equal(d.elapsed(9000), 500);
  d.observe(r, 9500);
  assert.equal(d.elapsed(9500), 500);
  assert.equal(d.claim(12000, 3000), true);
  assert.equal(d.claim(18000, 3000), false);
  d.observe({ ...r, source: "MOUSE_PROXY" }, 18001);
  assert.equal(d.elapsed(18001), 0);
});
test("history retains revisits but separates provenance, expiry and source versions", () => {
  const h = new History();
  h.add(r, 0);
  h.add(r, 100);
  h.add({ ...r, firstLine: 5 }, 200);
  h.add(r, 300);
  assert.equal(h.recent(400, "WEBCAM").length, 3);
  assert.equal(h.recent(400, "MOUSE_PROXY").length, 0);
  assert.equal(h.recent(46000, "WEBCAM").length, 0);
  h.invalidate(file.path);
  assert.equal(h.entries.length, 0);
});
test("engine preserves 3-second block dwell across ten short line visits; pause resets it", () => {
  const visits = [],
    prepared = [];
  const e = new Engine({
      onVisit: (v) => visits.push(v),
      onPrepared: (p) => prepared.push(p),
    }),
    o = features("RELATIONSHIPS");
  for (let i = 0; i < 11; i++)
    e.observe(file, 1 + (i % 2), 5, "MOUSE_PROXY", 100 + i * 300, o);
  assert.equal(prepared.length, 1);
  assert.equal(visits.length, 10);
  assert.equal(
    visits.reduce((n, v) => n + v.dwellMs, 0),
    3000,
  );
  e.exit(3500);
  e.observe(file, 2, 5, "MOUSE_PROXY", 5000, o);
  e.tick(7000);
  assert.equal(prepared.length, 1);
  e.tick(8100);
  assert.equal(prepared.length, 2);
});
test("pending line candidates never accrue uncertain time in dwell or visits", () => {
  const visits = [],
    e = new Engine({ onVisit: (v) => visits.push(v) }),
    o = features("RELATIONSHIPS");
  e.observe(file, 2, 5, "WEBCAM", 100, o);
  e.tick(700);
  e.pending(r, 1100);
  e.observe(file, 1, 5, "WEBCAM", 1400, o);
  assert.equal(e.dwell.elapsed(1400), 1000);
  e.exit(1900);
  assert.equal(
    visits.reduce((n, v) => n + v.dwellMs, 0),
    1500,
  );
});
test("affine calibration is well conditioned for offset/negative display coordinates and rejects singular targets", () => {
  const samples = [
      [-2000, 500],
      [-1000, 500],
      [-2000, 1000],
      [-1000, 1000],
    ].map(([x, y]) => ({ x, y, tx: 2 * x + 30, ty: 0.5 * y - 100 })),
    fit = affine(samples);
  assert.ok(fit);
  assert.deepEqual(correct(fit, { x: -1500, y: 700 }), { x: -2970, y: 250 });
  assert.equal(
    affine([
      { x: 1, y: 1, tx: 1, ty: 1 },
      { x: 2, y: 2, tx: 2, ty: 2 },
      { x: 3, y: 3, tx: 3, ty: 3 },
    ]),
    null,
  );
});
test("question purpose preserves bounded context and explicit input provenance", () => {
  const h = [r, { ...r, firstLine: 5, text: "validator source" }];
  assert.ok(
    !prompt(r, "line", null, h, "EXPLAIN").includes("validator source"),
  );
  assert.ok(prompt(r, "line", null, h, "COMPARE").includes("validator source"));
  assert.ok(prompt(r, "line", null, h, "REVIEW").includes("WEBCAM"));
});
test("personal response evaluation checks a frozen preset on distinct trials", () => {
  const trials = Array.from({ length: 6 }, (_, row) => ({
    row,
    y: 100 + row * 29,
    start: row * 2400,
    samples: Array.from({ length: 70 }, (_, i) => ({
      x: 10,
      y: 100 + row * 29,
      t: row * 2400 + i * 33,
    })),
  }));
  const chosen = chooseResponse(trials, 29);
  assert.ok(
    ["EXTRA_STEADY", "STEADY", "BALANCED", "RESPONSIVE"].includes(chosen),
  );
  const q = evaluate(trials.slice(0, 4), chosen, 29);
  assert.equal(q.targets, 4);
  assert.ok(q.hitRate > 0.9);
  assert.equal(q.p90VerticalPx, 0);
});
const session = {
  id: "test",
  schemaVersion: 3,
  startMs: 1000,
  endMs: 2000,
  durationMs: 1000,
  project: "secret project",
  metadata: {
    labels: { participant: "private label" },
    initialConfiguration: {
      inputKind: "WEBCAM",
      scenario: "RELATIONSHIPS",
      features: features("RELATIONSHIPS"),
      calibrated: true,
      review: { purpose: "EXPLAIN", quietOffers: true },
    },
  },
  configurationChanges: [],
  events: [
    {
      timestampMs: 1200,
      elapsedMs: 200,
      file: "/private/source.java",
      line: 3,
      column: 2,
      dwellMs: 100,
      inputKind: "WEBCAM",
      source: "WEBCAM",
      lineText: "=IMPORTXML(secret)",
      region: r,
      explained: true,
    },
  ],
};
test("CSV resists spreadsheet formulas; self-contained report escapes code and script terminators", () => {
  assert.ok(csv(session).includes("'=IMPORTXML"));
  const bad = structuredClone(session);
  bad.events[0].region.text = "</script><img src=x onerror=alert(1)>";
  const html = reportHtml(bad);
  assert.ok(!html.includes("<img src=x"));
  assert.ok(html.includes("\\u003c"));
  assert.ok(html.includes('id="gazeaide-viewing-map"'));
});
test("reduced data removes paths, labels, code, epoch times, notes and AI content and passes original receiver schema", async () => {
  const extra = structuredClone(session);
  extra.metadata.initialConfiguration.tracking = {
    response: "EXTRA_STEADY",
    smoothingMs: 260,
    lineHoldMs: 260,
  };
  const p = reduced(extra, {
      studyId: "s1",
      participantId: "P1",
      taskCode: "t1",
      uploadId: "d43bef80-d73e-4454-b87b-5ab97f12646f",
    }),
    raw = JSON.stringify(p);
  for (const value of [
    "secret project",
    "private label",
    "/private/source.java",
    "=IMPORTXML",
    "documentStamp",
    "timestampMs",
  ])
    assert.ok(!raw.includes(value), value);
  const receiver = await readFile(
      new URL("../docs/google-drive-receiver.js", import.meta.url),
      "utf8",
    ),
    ctx = vm.createContext({});
  vm.runInContext(receiver, ctx);
  assert.doesNotThrow(() => ctx.validatePayload_(JSON.parse(raw)));
});
test("export trace pruning affects only report; original JSON visits remain intact", () => {
  const html = reportHtml(session, {
    traceEdits: { 0: { pruned: true, note: "annotation" } },
  });
  assert.equal(session.events.length, 1);
  assert.ok(html.includes("original visits"));
  assert.ok(!html.includes("/private/source.java"));
});
test("receiver accepts both original plugin fixture and labelled standalone edition; rejects false edition names", async () => {
  const receiver = await readFile(
      new URL("../docs/google-drive-receiver.js", import.meta.url),
      "utf8",
    ),
    ctx = vm.createContext({});
  vm.runInContext(receiver, ctx);
  const original = JSON.parse(
    await readFile(
      new URL("./remote-session-v1.json", import.meta.url),
      "utf8",
    ),
  );
  assert.doesNotThrow(() => ctx.validatePayload_(original));
  assert.doesNotThrow(() =>
    ctx.validatePayload_({
      ...original,
      edition: "standalone",
      editionVersion: "0.1.0",
    }),
  );
  assert.throws(() =>
    ctx.validatePayload_({ ...original, edition: "unknown" }),
  );
});
