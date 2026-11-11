// This dependency-free component also ships inside the IntelliJ plugin's HTML reports.
// Keep the factory self-contained so exported reports work offline without module requests.
export function viewingMapModule() {
  const escape = (v) =>
    String(v ?? "")
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "�")
      .replace(
        /[&<>"']/g,
        (c) =>
          ({
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#39;",
          })[c],
      );
  const finite = (v) => typeof v === "number" && Number.isFinite(v);
  const source = (e) =>
    e.inputKind ||
    (e.simulated
      ? "MOUSE_SIMULATION"
      : e.source === "MOUSE"
        ? "MOUSE_PROXY"
        : e.source) ||
    "UNKNOWN";
  const end = (e, s) =>
    finite(e.elapsedMs)
      ? e.elapsedMs
      : finite(e.timestampMs) && finite(s.startMs)
        ? e.timestampMs - s.startMs
        : null;
  const duration = (s) =>
    (s.events || []).reduce(
      (maximum, e) => Math.max(maximum, end(e, s) || 0),
      Math.max(
        0,
        finite(s.durationMs)
          ? s.durationMs
          : finite(s.endMs) && finite(s.startMs)
            ? s.endMs - s.startMs
            : 0,
      ),
    );
  const id = (...v) => JSON.stringify(v);
  const PAGE_SIZE = 120;
  function compact(s) {
    const snapshots = [],
      index = new Map(),
      references = new WeakMap();
    const events = (s.events || []).map((e) => {
      const r = e.region || s.mapSnapshots?.[e.regionRef];
      let ref = null;
      if (r) {
        ref = references.get(r);
        if (ref === undefined) {
          const k = id(
            r.filePath,
            r.documentStamp,
            r.firstLine,
            r.lastLine,
            r.source,
            r.text,
          );
          if (!index.has(k)) {
            index.set(k, snapshots.length);
            snapshots.push(r);
          }
          ref = index.get(k);
          references.set(r, ref);
        }
      }
      return {
        elapsedMs: end(e, s),
        dwellMs: e.dwellMs,
        file: e.filePath || e.file,
        line: e.line,
        lineText: e.lineText,
        inputKind: source(e),
        regionRef: ref,
      };
    });
    return {
      id: s.id || s.researchRecordingId,
      startMs: s.startMs,
      durationMs: duration(s),
      inProgress: !!s.inProgress,
      metadata: { initialConfiguration: s.metadata?.initialConfiguration },
      configurationChanges: s.configurationChanges || [],
      mapSnapshots: snapshots,
      events,
    };
  }
  const css = `.vm{font:14px/1.5 system-ui,sans-serif;color:#173b39;background:#fff;border:1px solid #bdcfcc;border-radius:10px;padding:18px;margin:16px 0}.vm h2,.vm h3{margin:8px 0}.vm-controls{display:flex;gap:12px;flex-wrap:wrap;align-items:end}.vm label{display:flex;flex-direction:column;gap:4px}.vm input,.vm select,.vm button{font:inherit;padding:6px;border:1px solid #9ab5b0;border-radius:4px;background:#fff;color:#173b39}.vm input{width:105px}.vm button{cursor:pointer}.vm button:disabled{opacity:.5;cursor:default}.vm-actions{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.vm-key{display:flex;gap:16px;flex-wrap:wrap;margin:12px 0}.vm-ramp{display:inline-block;width:90px;height:14px;background:linear-gradient(to right,#f3f8f6,#8cc8b9)}.vm-missing{background:repeating-linear-gradient(135deg,#fff,#fff 5px,#e6e9e9 5px,#e6e9e9 8px)}.vm-note{font-size:12px;color:#496763;white-space:pre-wrap;overflow-wrap:anywhere}.vm table{width:100%;border-collapse:collapse;table-layout:fixed}.vm td,.vm th{border-bottom:1px solid #d5e0de;padding:7px;text-align:left;vertical-align:top}.vm th:first-child{width:110px}.vm th:nth-child(2){width:85px}.vm th:nth-child(3){width:65px}.vm code{white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.5 ui-monospace,monospace}.vm-group{overflow-wrap:anywhere;border-top:2px solid #aec8c2;padding-top:10px}.vm-status{min-height:20px}.vm summary{cursor:pointer}`;

  function quality(s, from, to, input) {
    const changes = [
      { elapsedMs: 0, configuration: s.metadata?.initialConfiguration || {} },
      ...(s.configurationChanges || []),
    ]
      .filter((c) => finite(c.elapsedMs))
      .sort((a, b) => a.elapsedMs - b.elapsedMs);
    const segments = [];
    for (let i = 0; i < changes.length; i++) {
      const start = Math.max(from, changes[i].elapsedMs),
        stop = Math.min(to, changes[i + 1]?.elapsedMs ?? to);
      const c = changes[i].configuration || {},
        check = c.tracking?.calibrationCheck;
      const kind = source({ inputKind: c.inputKind });
      if (stop <= start || (input && kind !== input)) continue;
      segments.push({
        fromMs: start,
        toMs: stop,
        inputKind: kind,
        calibrated: typeof c.calibrated === "boolean" ? c.calibrated : null,
        calibrationCheck: check
          ? Object.fromEntries(
              [
                "checkedAtMs",
                "medianErrorPx",
                "p90ErrorPx",
                "medianVerticalPx",
                "p90VerticalPx",
                "jitter90Px",
                "samples",
                "targets",
              ]
                .filter((k) => finite(check[k]))
                .map((k) => [k, check[k]]),
            )
          : null,
      });
    }
    return segments;
  }

  function aggregate(s, options = {}) {
    const total = duration(s),
      from = Math.max(0, Math.min(total, Number(options.fromMs) || 0));
    const to = Math.max(
      from,
      Math.min(total, finite(options.toMs) ? options.toMs : total),
    );
    const input = options.inputKind || "",
      mode = options.mode === "regions" ? "regions" : "lines";
    const groups = new Map(),
      snapshots = new Set();
    for (const e of s.events || []) {
      const kind = source(e);
      if (input && input !== kind) continue;
      const r = e.region || s.mapSnapshots?.[e.regionRef],
        file = String(e.filePath || e.file || r?.filePath || "Unknown file"),
        version = r?.documentStamp ?? null;
      const key = id(file, version, kind);
      if (!groups.has(key))
        groups.set(key, {
          file,
          documentVersion: version,
          inputKind: kind,
          lines: new Map(),
          regions: new Map(),
        });
      const g = groups.get(key),
        line = Math.max(1, Math.trunc(e.line) || 1);
      const addLine = (n, text) => {
        if (!g.lines.has(n))
          g.lines.set(n, {
            firstLine: n,
            lastLine: n,
            text: null,
            exposureMs: 0,
            visits: 0,
          });
        const row = g.lines.get(n);
        if (typeof text === "string" && row.text === null) row.text = text;
        return row;
      };
      const first =
        Number.isInteger(r?.firstLine) && r.firstLine > 0 ? r.firstLine : line;
      const last =
        Number.isInteger(r?.lastLine) && r.lastLine >= first
          ? r.lastLine
          : first;
      const rk = id(first, last, r?.label || "Line visit");
      if (!g.regions.has(rk))
        g.regions.set(rk, {
          firstLine: first,
          lastLine: last,
          label: r?.label || "Line visit",
          text: null,
          captureState: "missing",
          exposureMs: 0,
          visits: 0,
        });
      const region = g.regions.get(rk);
      const sk = id(key, rk);
      if (r && !snapshots.has(sk)) {
        snapshots.add(sk);
        const text = typeof r.text === "string" ? r.text : null;
        const lines =
          text === null ? [] : text.replace(/\r\n?/g, "\n").split("\n");
        const marker = lines.findIndex((v) =>
          /^\[(?:region )?truncated\]$/.test(v),
        );
        if (marker >= 0) lines.length = marker;
        const captured = lines.slice(0, Math.min(last - first + 1, 20000));
        captured.forEach((v, i) => addLine(first + i, v));
        // Boundaries retain the distinction between absent text and an unvisited captured line.
        addLine(first, null);
        addLine(last, null);
        region.text = captured.length ? captured.join("\n") : null;
        region.captureState =
          captured.length >= last - first + 1
            ? "captured"
            : captured.length
              ? "partial"
              : "missing";
      }
      const row = addLine(
        line,
        typeof e.lineText === "string" ? e.lineText : null,
      );
      if (!r) {
        region.text = row.text;
        region.captureState = row.text === null ? "missing" : "captured";
      }
      const at = end(e, s),
        dwell = finite(e.dwellMs) ? Math.max(0, e.dwellMs) : 0;
      const overlap =
        at === null
          ? 0
          : Math.max(0, Math.min(to, at) - Math.max(from, 0, at - dwell));
      if (overlap > 0) {
        row.exposureMs += overlap;
        row.visits++;
        region.exposureMs += overlap;
        region.visits++;
      }
    }
    const rows = [];
    for (const g of groups.values()) {
      const values = [
        ...(mode === "regions" ? g.regions : g.lines).values(),
      ].sort((a, b) => a.firstLine - b.firstLine || a.lastLine - b.lastLine);
      let previous = null;
      const push = (r) =>
        rows.push({
          file: g.file,
          documentVersion: g.documentVersion,
          inputKind: g.inputKind,
          mode,
          ...r,
          captureState:
            r.captureState || (r.text === null ? "missing" : "captured"),
          recordingState: r.visits ? "recorded_visit" : "no_recorded_visit",
        });
      for (const row of values) {
        if (
          mode === "lines" &&
          previous !== null &&
          row.firstLine > previous + 1
        )
          push({
            firstLine: previous + 1,
            lastLine: row.firstLine - 1,
            text: null,
            exposureMs: 0,
            visits: 0,
            gap: true,
          });
        push(row);
        previous = row.lastLine;
      }
    }
    return {
      schemaVersion: 1,
      recordType: "viewing-map",
      policy: "attributed-exposure-v1",
      recordingId: s.id || s.researchRecordingId || null,
      inProgress: !!s.inProgress,
      fromMs: from,
      toMs: to,
      inputKind: input || "ALL_SEPARATE",
      mode,
      contentScope:
        "Captured text from the recording; exposure and visit counts are clipped to the selected interval. No recorded visit does not mean unread.",
      visitCountPolicy:
        "Each visit overlapping the interval is counted once; region totals assign each visit only to its recorded region, including nested regions.",
      interpretation:
        "Gaze-derived viewing exposure; no inference of fixation, comprehension or completed review. Proxy sources remain separate.",
      quality: quality(s, from, to, input),
      rows,
    };
  }

  const value = (row, metric) =>
    metric === "visits" ? row.visits : row.exposureMs / 1000;
  const colour = (n, max) =>
    n <= 0
      ? "#ffffff"
      : `rgb(${Math.round(243 - 103 * Math.min(1, n / Math.max(max, 0.001)))},${Math.round(248 - 48 * Math.min(1, n / Math.max(max, 0.001)))},${Math.round(246 - 61 * Math.min(1, n / Math.max(max, 0.001)))})`;
  const fmt = (n) =>
    Number(n)
      .toFixed(3)
      .replace(/\.?0+$/, "") || "0";
  const location = (r) =>
    r.firstLine === r.lastLine
      ? `L${r.firstLine}`
      : `L${r.firstLine}–${r.lastLine}`;
  const groupName = (r) =>
    `${r.file} [version ${r.documentVersion ?? "unknown"}, ${r.inputKind}]`;
  const imageLocation = (r) =>
    `${r.file.length > 70 ? "…" + r.file.slice(-69) : r.file} [version ${r.documentVersion ?? "unknown"}, ${r.inputKind}] · ${location(r)}`;
  const description = (r) =>
    r.captureState === "missing"
      ? "Missing captured content"
      : r.captureState === "partial"
        ? "Partially captured content"
        : r.visits
          ? "Recorded visit"
          : "No recorded visit in interval";
  function pageRows(map, page = 0) {
    return map.rows.slice(
      Math.max(0, page) * PAGE_SIZE,
      (Math.max(0, page) + 1) * PAGE_SIZE,
    );
  }

  function svg(map, { metric = "seconds", page = 0 } = {}) {
    const rows = pageRows(map, page),
      max = map.rows.reduce((n, r) => Math.max(n, value(r, metric)), 0),
      width = 1240;
    const parts = [
      `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${260 + rows.length * 74}" viewBox="0 0 ${width} ${260 + rows.length * 74}" role="img"><title>GAZEAIDE gaze-derived viewing map</title><rect width="100%" height="100%" fill="white"/><g font-family="monospace" font-size="13" fill="#173b39">`,
      `<text x="20" y="30" font-size="20">GAZEAIDE · Gaze-derived viewing map</text>`,
      `<text x="20" y="55">${escape(map.mode)} · ${fmt(map.fromMs / 1000)}–${fmt(map.toMs / 1000)} s · ${escape(map.inputKind)} · page ${page + 1}</text>`,
      `<text x="20" y="80">Colour: ${metric === "visits" ? "overlapping visits" : "attributed seconds"}; common scale 0–${fmt(max)}. Grey: missing captured content.</text>`,
      `<text x="20" y="105">No recorded visit does not mean unread. Captured document versions and input sources stay separate.</text>`,
      `<text x="20" y="130">${map.inProgress ? "Recovered/in-progress recording; may be incomplete." : "Recorded viewing exposure; no inference of comprehension or completed review."}</text>`,
      `<text x="20" y="155">${escape(qualityText(map).split("\n")[0].slice(0, 140) + (qualityText(map).split("\n")[0].length > 140 ? "…" : ""))}</text>`,
    ];
    rows.forEach((r, i) => {
      const y = 200 + i * 74;
      parts.push(
        `<rect x="16" y="${y}" width="1208" height="72" fill="${r.captureState === "missing" ? "#e6e9e9" : colour(value(r, metric), max)}"/>`,
        `<text x="24" y="${y + 18}">${escape(imageLocation(r))}</text>`,
        `<text x="24" y="${y + 39}">${escape(`${fmt(r.exposureMs / 1000)} s | ${r.visits} visits | ${description(r)} | ${(r.label || "").slice(0, 60)}`)}</text>`,
        `<text x="24" y="${y + 61}">${escape((r.text?.split("\n")[0] ?? "[content not captured]").slice(0, 150))}</text>`,
      );
    });
    parts.push("</g></svg>");
    return parts.join("");
  }

  function csv(map) {
    const fields = [
      "file",
      "documentVersion",
      "inputKind",
      "mode",
      "firstLine",
      "lastLine",
      "captureState",
      "recordingState",
      "exposureMs",
      "visits",
      "text",
    ];
    const cell = (v) => {
      let s = String(v ?? "");
      if (/^[\s]*[=+@-]/.test(s) || /^[\t\r]/.test(s)) s = "'" + s;
      return '"' + s.replaceAll('"', '""') + '"';
    };
    return (
      [
        fields.concat(["intervalStartMs", "intervalEndMs"]).join(","),
        ...map.rows.map((r) =>
          [...fields.map((k) => r[k]), map.fromMs, map.toMs]
            .map(cell)
            .join(","),
        ),
      ].join("\n") + "\n"
    );
  }
  function qualityText(map) {
    return (
      map.quality
        .map((q) => {
          const c = q.calibrationCheck;
          return (
            `${fmt(q.fromMs / 1000)}–${fmt(q.toMs / 1000)} s · ${q.inputKind}: ` +
            (q.inputKind !== "WEBCAM"
              ? "no webcam accuracy claim for this input."
              : c
                ? `Calibration check: median position error ${finite(c.medianErrorPx) ? fmt(c.medianErrorPx) : "unknown"} px; p90 vertical error ${finite(c.p90VerticalPx) ? fmt(c.p90VerticalPx) : "unknown"} px; p90 jitter ${finite(c.jitter90Px) ? fmt(c.jitter90Px) : "unknown"} px; ${c.samples ?? "unknown"} samples / ${c.targets ?? "unknown"} targets. Check timestamp: ${c.checkedAtMs ?? "unknown"}.`
                : "No calibration-check metrics recorded.")
          );
        })
        .join("\n") ||
      "No configuration or calibration-check metrics recorded for this filter."
    );
  }

  function mount(root, session) {
    root.classList.add("vm");
    root.innerHTML = `<style>${css}</style><h2>Gaze-derived viewing map</h2><p class="vm-note">Colour summarizes attributed viewing exposure. Sources and document versions remain separate. No recorded visit does not mean unread.</p><div class="vm-controls"><label>Measure<select data-vm="metric" aria-label="Measure"><option value="seconds">Attributed seconds</option><option value="visits">Visit count</option></select></label><label>Group by<select data-vm="mode" aria-label="Group by"><option value="lines">Lines</option><option value="regions">Recorded regions</option></select></label><label>Input source<select data-vm="source" aria-label="Input source"><option value="">All sources (separate)</option><option>WEBCAM</option><option>MOUSE_PROXY</option><option>MOUSE_SIMULATION</option><option>UNKNOWN</option></select></label><label>From (seconds)<input data-vm="from" type="number" min="0" step="0.001" value="0"></label><label>To (seconds)<input data-vm="to" type="number" min="0" step="0.001"></label><button data-vm="apply">Apply interval</button></div><p data-vm="summary" class="vm-note"></p><div class="vm-key"><span><i class="vm-ramp"></i> <span data-vm="scale"></span></span><span>White: no recorded visit</span><span class="vm-missing">Striped: missing captured content</span></div><details><summary>Recorded setup and calibration quality</summary><pre data-vm="quality" class="vm-note"></pre></details><div class="vm-actions"><button data-vm="svg">Export SVG (page)</button><button data-vm="png">Export PNG (page)</button><button data-vm="csv">Export values CSV (all rows)</button><button data-vm="json">Export values JSON (all rows)</button></div><p class="vm-note">Images contain this page; values contain every row under the current filters. Region visits contribute once to their recorded region. Source text comes from captured snapshots across the recording.</p><div class="vm-actions"><button data-vm="prev">Previous page</button><span data-vm="page"></span><button data-vm="next">Next page</button></div><p data-vm="status" class="vm-note vm-status" role="status"></p><div data-vm="rows"></div>`;
    const $ = (key) => root.querySelector(`[data-vm="${key}"]`);
    let current = session,
      map,
      page = 0,
      metric = "seconds";
    function render() {
      const from = Number($("from").value) * 1000,
        to = Number($("to").value) * 1000;
      if (
        !Number.isFinite(from) ||
        !Number.isFinite(to) ||
        from < 0 ||
        to < from
      ) {
        $("status").textContent = "Choose an interval with 0 ≤ From ≤ To.";
        return;
      }
      map = aggregate(current, {
        fromMs: from,
        toMs: to,
        inputKind: $("source").value,
        mode: $("mode").value,
      });
      metric = $("metric").value;
      page = Math.max(
        0,
        Math.min(page, Math.ceil(map.rows.length / PAGE_SIZE) - 1),
      );
      const visible = pageRows(map, page),
        max = map.rows.reduce((n, r) => Math.max(n, value(r, metric)), 0);
      $("summary").textContent =
        `${fmt(map.fromMs / 1000)}–${fmt(map.toMs / 1000)} s · ${map.rows.length} ${map.mode} entries${map.inProgress ? " · Recovered/in-progress recording; may be incomplete" : ""}.`;
      $("scale").textContent =
        `Common scale: 0–${fmt(max)} ${metric === "visits" ? "visits" : "s"}`;
      $("quality").textContent = qualityText(map);
      $("page").textContent =
        `Page ${page + 1} of ${Math.max(1, Math.ceil(map.rows.length / PAGE_SIZE))}`;
      $("prev").disabled = page === 0;
      $("next").disabled = (page + 1) * PAGE_SIZE >= map.rows.length;
      let html = "",
        group = "";
      for (const row of visible) {
        const name = groupName(row);
        if (name !== group) {
          if (group) html += "</tbody></table>";
          group = name;
          html += `<h3 class="vm-group">${escape(name)}</h3><table><thead><tr><th>Location</th><th>Seconds</th><th>Visits</th><th>Captured content</th></tr></thead><tbody>`;
        }
        html += `<tr${row.captureState === "missing" ? ' class="vm-missing"' : ` style="background:${colour(value(row, metric), max)}"`}><td>${escape(location(row))}</td><td>${fmt(row.exposureMs / 1000)}</td><td>${row.visits}</td><td><span class="vm-note">${escape(description(row))}</span>${row.label ? `<strong> · ${escape(row.label)}</strong>` : ""}<br><code>${escape(row.text ?? "[content not captured]")}</code></td></tr>`;
      }
      $("rows").innerHTML =
        html +
        (group
          ? "</tbody></table>"
          : "<p>No captured regions or visits for this source.</p>");
      $("status").textContent = "";
    }
    const save = (name, type, data) => {
      const blob = data instanceof Blob ? data : new Blob([data], { type }),
        url = URL.createObjectURL(blob),
        a = document.createElement("a");
      a.href = url;
      a.download = name;
      root.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
    for (const key of ["metric", "mode", "source"])
      $(key).onchange = () => {
        page = 0;
        render();
      };
    $("apply").onclick = () => {
      page = 0;
      render();
    };
    $("prev").onclick = () => {
      page--;
      render();
    };
    $("next").onclick = () => {
      page++;
      render();
    };
    for (const format of ["svg", "csv", "json"])
      $(format).onclick = () => {
        if (!map) return;
        const data =
          format === "svg"
            ? svg(map, { metric, page })
            : format === "csv"
              ? csv(map)
              : JSON.stringify(
                  {
                    ...map,
                    imagePage: page + 1,
                    imagePageSize: PAGE_SIZE,
                    metric,
                  },
                  null,
                  2,
                );
        save(
          `gazeaide-viewing-map.${format}`,
          { svg: "image/svg+xml", csv: "text/csv", json: "application/json" }[
            format
          ],
          data,
        );
      };
    $("png").onclick = async () => {
      if (!map) return;
      const url = URL.createObjectURL(
        new Blob([svg(map, { metric, page })], { type: "image/svg+xml" }),
      );
      try {
        const image = new Image();
        await new Promise((resolve, reject) => {
          image.onload = resolve;
          image.onerror = () =>
            reject(
              new Error("Could not render the map image. Export SVG instead."),
            );
          image.src = url;
        });
        const canvas = document.createElement("canvas");
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext("2d");
        if (!context)
          throw new Error(
            "PNG export is unavailable in this browser. Export SVG instead.",
          );
        context.drawImage(image, 0, 0);
        const blob = await new Promise((resolve) =>
          canvas.toBlob(resolve, "image/png"),
        );
        if (!blob) throw new Error("PNG encoding failed. Export SVG instead.");
        save("gazeaide-viewing-map.png", "image/png", blob);
      } catch (e) {
        $("status").textContent = e.message;
      } finally {
        URL.revokeObjectURL(url);
      }
    };
    function update(s) {
      current = s;
      page = 0;
      $("from").value = "0";
      $("to").value = String(duration(s) / 1000);
      render();
    }
    update(session);
    return { update, getMap: () => map };
  }
  return { aggregate, svg, csv, mount, compact, qualityText, PAGE_SIZE };
}

export const {
  aggregate: aggregateViewingMap,
  svg: viewingMapSvg,
  csv: viewingMapCsv,
  mount: mountViewingMap,
} = viewingMapModule();

export function viewingMapHtml(session) {
  const safe = JSON.stringify(viewingMapModule().compact(session)).replaceAll(
    "<",
    "\\u003c",
  );
  return `<section id="gazeaide-viewing-map"></section><script type="module">(${viewingMapModule.toString()})().mount(document.getElementById("gazeaide-viewing-map"),${safe});<${"/"}script>`;
}
