import { escape, key } from "./core.mjs";
const known = (v, list) => (list.includes(v) ? v : "UNKNOWN");
const finite = (obj, names) =>
  Object.fromEntries(
    names.filter((n) => Number.isFinite(obj?.[n])).map((n) => [n, obj[n]]),
  );
export function reducedConfiguration(c = {}) {
  const o = {
    input: known(c.inputKind, ["WEBCAM", "MOUSE_PROXY", "MOUSE_SIMULATION"]),
    scenario: known(c.scenario, [
      "FOCUS",
      "COVERAGE",
      "FADE",
      "NAMES",
      "RELATIONSHIPS",
      "WALKTHROUGH",
      "OBSERVE",
      "CUSTOM",
      "PERSONALIZATION",
    ]),
    calibrated: !!c.calibrated,
  };
  if (c.features)
    o.features = Object.fromEntries(
      [
        "focus",
        "fadeViewed",
        "relationships",
        "historyContext",
        "explanations",
        "stableMs",
        "fadeAfterMs",
        "explainAfterMs",
      ].map((k) => [k, c.features[k]]),
    );
  if (c.tracking) {
    const t = c.tracking;
    o.tracking = {
      showCursor: !!t.showCursor,
      response: known(t.response, [
        "EXTRA_STEADY",
        "STEADY",
        "BALANCED",
        "RESPONSIVE",
        "BYPASSED",
      ]),
      ...finite(t, ["smoothingMs", "lineHoldMs"]),
      pipelineVersion: known(t.pipelineVersion, [
        "raw-eyemu-median3-time-lowpass-line-hold-v2",
        "mouse-direct-v1",
      ]),
    };
    if (t.calibrationCheck)
      o.tracking.calibration = finite(t.calibrationCheck, [
        "version",
        "displayWidth",
        "displayHeight",
        "medianErrorPx",
        "p90ErrorPx",
        "medianVerticalPx",
        "p90VerticalPx",
        "jitter90Px",
        "samples",
        "targets",
      ]);
    if (t.personalResponse)
      o.tracking.personalResponse = {
        version: 1,
        active: !!t.personalResponse.active,
        response: known(t.personalResponse.response, [
          "EXTRA_STEADY",
          "STEADY",
          "BALANCED",
          "RESPONSIVE",
        ]),
        ...finite(t.personalResponse, [
          "p90VerticalPx",
          "jitter90Px",
          "targetHitRate",
          "acquisitionMs",
          "standardHitRate",
          "standardAcquisitionMs",
          "rowHeight",
          "targets",
          "samples",
        ]),
      };
  }
  if (c.review)
    o.review = {
      purpose: known(c.review.purpose, ["EXPLAIN", "COMPARE", "REVIEW"]),
      quietOffers: !!c.review.quietOffers,
      cooldownMs: 30000,
    };
  return o;
}
export function reduced(
  session,
  {
    studyId,
    participantId,
    taskCode = "",
    uploadId = crypto.randomUUID(),
    kind = "session",
  },
) {
  const out = {
    protocol: "gazeaide-upload-v1",
    schemaVersion: 1,
    uploadId,
    kind,
    studyId,
    participantId,
    edition: "standalone",
    editionVersion: "1.0.0",
  };
  if (kind === "connection_test") return out;
  if (
    session.events.length > 50000 ||
    session.configurationChanges.length > 5000
  )
    throw new Error("Reduced upload exceeds the connector event limit.");
  Object.assign(out, {
    pluginVersion: "1.0.0",
    taskCode,
    durationMs: session.durationMs,
    initialConfiguration: reducedConfiguration(
      session.metadata?.initialConfiguration,
    ),
    configurationChanges: session.configurationChanges.map((c) => ({
      elapsedMs: Math.max(0, c.elapsedMs),
      paused: !!c.paused,
      configuration: reducedConfiguration(c.configuration),
    })),
  });
  const files = new Map(),
    regions = new Map(),
    id = (map, k, prefix) => {
      if (!map.has(k)) map.set(k, prefix + (map.size + 1));
      return map.get(k);
    };
  out.events = session.events.map((e) => ({
    elapsedMs: Math.max(0, e.elapsedMs),
    fileId: id(files, e.file, "f"),
    line: Math.max(0, e.line),
    column: Math.max(0, e.column),
    dwellMs: Math.max(0, e.dwellMs),
    input: known(e.inputKind, ["WEBCAM", "MOUSE_PROXY", "MOUSE_SIMULATION"]),
    legacyDwellTrigger: !!e.explained,
    ...(e.region
      ? {
          regionId: id(regions, key(e.region), "r"),
          regionFirstLine: e.region.firstLine,
          regionLastLine: e.region.lastLine,
        }
      : {}),
  }));
  return out;
}
export function reportHtml(
  session,
  { traceEdits = {}, walkthrough = session.walkthrough || [] } = {},
) {
  const rows = session.events
    .map((e, i) => ({ e, i }))
    .filter(({ i }) => !traceEdits[i]?.pruned);
  const summary = `${session.events.length} original visits · ${session.durationMs / 1000} s session duration · ${session.events.reduce((n, e) => n + e.dwellMs, 0) / 1000} s exposure`;
  const safe = JSON.stringify(
    rows.map(({ e, i }) => ({ ...e, note: traceEdits[i]?.note || "" })),
  ).replaceAll("<", "\\u003c");
  return `<!doctype html><meta charset="utf-8"><title>GAZEAIDE viewing report</title><style>body{font:15px/1.6 system-ui;max-width:1000px;margin:40px auto;padding:20px;color:#183a35}pre{white-space:pre-wrap;background:#f0f5f2;padding:20px}input{width:100%}article{border-top:1px solid #ccdcd5;margin:20px 0}small{color:#607c70}td,th{padding:7px;text-align:left;border-bottom:1px solid #ddd}table{width:100%}</style><h1>GAZEAIDE · Standalone viewing report</h1><p>${escape(summary)}</p><p>Viewing visits are exposure estimates, not physiological fixations, comprehension, correctness or completed review. Inputs retain their provenance. Legacy “explained” marks local context preparation, not sending or reading an AI answer.</p><p>Trace annotations and exclusions affect this exported report only; original events remain in JSON.</p><input aria-label="Replay visit" type="range" id="slider" min="0" max="${Math.max(0, rows.length - 1)}" value="0"><p id="at"></p><pre id="source"></pre><script type="application/json" id="events">${safe}</script><script>const rows=JSON.parse(document.getElementById('events').textContent);function show(){const e=rows[Number(document.getElementById('slider').value)];document.getElementById('at').textContent=e?e.file+':'+e.line+' ['+e.inputKind+'] '+e.dwellMs+' ms · '+(e.note||''):'No visits';document.getElementById('source').textContent=e?(e.region?.text||e.lineText):'';}document.getElementById('slider').oninput=show;show();</script><h2>Viewing trace</h2><table><tr><th>At</th><th>Location</th><th>Input</th><th>Exposure</th><th>Annotation</th></tr>${rows.map(({ e, i }) => `<tr><td>${e.elapsedMs} ms</td><td>${escape(e.file)}:${e.line}</td><td>${escape(e.inputKind)}</td><td>${e.dwellMs} ms</td><td>${escape(traceEdits[i]?.note)}</td></tr>`).join("")}</table><h2>Review walkthrough</h2>${walkthrough.map((s) => `<article><h3>${escape(s.region.label)}</h3><small>${escape(s.region.filePath)}:${s.region.firstLine}–${s.region.lastLine} [${escape(s.region.source)}] · ${s.reviewed ? "User marked reviewed" : "Not marked reviewed"}</small><pre>${escape(s.region.text)}</pre><p>${escape(s.notes)}</p></article>`).join("")}`;
}
