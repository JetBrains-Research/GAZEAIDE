import {
  SCENARIOS,
  RESPONSES,
  features,
  Engine,
  PointFilter,
  LineTarget,
  region,
  key,
  location,
  occurrences,
  mask,
  prompt,
  affine,
  correct,
  estimate,
  percentile,
  evaluate,
  chooseResponse,
  csv,
  escape,
} from "/lib/core.mjs";
import { reduced, reportHtml } from "/lib/research.mjs";
import { targetQuality, runGuideTargets } from "/lib/guide.mjs";
import { buildAgentContext, composeAgentRequest } from "/lib/agent-context.mjs";
import { reviewItems, reviewDocument, documentRegion } from "/lib/review.mjs";
const $ = (id) => document.getElementById(id),
  secret = document.querySelector("meta[name=gazeaide-key]").content;
document.querySelector("meta[name=gazeaide-key]").remove();
const api = async (route, body) => {
  const r = await fetch("/api/" + route, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "x-gazeaide-key": secret,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await r.json();
  if (!r.ok) throw new Error(result.error || "Local request failed");
  return result;
};
const notice = (text) => ($("notice").textContent = text);
const action = (id, fn) =>
  $(id).addEventListener("click", () =>
    Promise.resolve()
      .then(fn)
      .catch((e) => notice(e.message)),
  );
let config,
  prefs = {},
  file,
  files = [],
  opened = new Map(),
  recording = false,
  paused = true,
  session = null,
  selected = null,
  clockStart = 0,
  epochStart = 0,
  tracker = null,
  cameraGeneration = 0,
  connecting = false,
  rawTap = null,
  rawStatus = null,
  lastRaw = 0,
  lastPointer = null,
  offer = null,
  nextOffer = 0,
  offered = new Set(),
  captured = null,
  steps = [],
  stepIndex = 0,
  traceEdits = {},
  replayTimer,
  uploadPreview = null,
  queue = null,
  chatEvents = [],
  codexState = {},
  reviewMode = "conversation",
  sourcePath = "GazeReviewDemo.java",
  reviewSnapshot = null,
  externalReview = null,
  reviewGeneration = 0,
  threadsCursor = null,
  availableThreads = [],
  sessionWrite = Promise.resolve(),
  sharedWrite = Promise.resolve();
const filter = new PointFilter(),
  lineTarget = new LineTarget();
const engine = new Engine({
  onVisit: (visit) => {
    if (!recording) return;
    const start = Math.max(visit.at, clockStart),
      end = visit.timestamp;
    if (end <= start) return;
    session.events.push(eventOf(visit, start, end));
  },
  onPrepared: (p) => prepare(p),
  onActive: (r) => {
    if ($("follow-tour").checked && $("scenario").value === "WALKTHROUGH") {
      const i = steps.findIndex((s) => key(s.region) === key(r));
      if (i >= 0) {
        stepIndex = i;
        renderStep();
      }
    }
  },
});
const now = () => performance.now();
const input = () => $("input").value;
const response = () =>
  $("use-profile").checked && prefs.profile?.calibrationKey === calibrationKey()
    ? prefs.profile.response
    : $("response").value;
const getOptions = () => {
  const scenario = $("scenario").value,
    o = features(scenario);
  if (scenario === "CUSTOM")
    for (const k of [
      "focus",
      "fadeViewed",
      "relationships",
      "historyContext",
      "explanations",
    ])
      o[k] = $("feature-" + k).checked;
  for (const [k, min, max] of [
    ["stableMs", 200, 2000],
    ["fadeAfterMs", 500, 30000],
    ["explainAfterMs", 1000, 15000],
  ])
    o[k] = Math.max(
      min,
      Math.min(max, Number($(k).value) || features(scenario)[k]),
    );
  return o;
};
function eventOf(v, start, end) {
  return {
    timestampMs: Math.round(epochStart + end - clockStart),
    elapsedMs: Math.round(end - clockStart),
    file: v.file,
    line: v.line,
    column: v.column,
    dwellMs: Math.round(end - start),
    explained: v.explained,
    source: v.source === "MOUSE_PROXY" ? "MOUSE" : "WEBCAM",
    simulated: v.source === "MOUSE_SIMULATION",
    inputKind: v.source,
    word: engine.token || "",
    lineText: v.lineText.trim(),
    region: v.region,
  };
}
const geometry = () => [
  window.innerWidth,
  window.innerHeight,
  window.screenX,
  window.screenY,
  window.devicePixelRatio,
  screen.width,
  screen.height,
];
const calibrationKey = () =>
  JSON.stringify([prefs.calibration?.fit, prefs.calibration?.geometry]);
const compatible = () =>
  prefs.calibration?.geometry?.every((n, i) => n === geometry()[i]);
async function savePrefs() {
  await api("preferences", prefs);
}
function configuration() {
  const o = getOptions(),
    kind = input(),
    real = kind === "WEBCAM",
    [smooth, hold] = RESPONSES[response()];
  return {
    inputKind: kind,
    scenario: $("scenario").value,
    features: o,
    calibrated: real && compatible(),
    aiProvider: "Codex",
    aiModel: "",
    tracking: {
      showCursor: $("cursor").checked,
      response: real ? response() : "BYPASSED",
      smoothingMs: real ? smooth : 0,
      lineHoldMs: real ? hold : 0,
      pipelineVersion:
        kind === "MOUSE_PROXY"
          ? "mouse-direct-v1"
          : "raw-eyemu-median3-time-lowpass-line-hold-v2",
      calibrationCheck: real ? prefs.calibration?.check : null,
      personalResponse: prefs.profile
        ? { ...prefs.profile, active: $("use-profile").checked }
        : null,
    },
    review: {
      purpose: $("purpose").value,
      quietOffers: $("quiet").checked,
      cooldownMs: 30000,
    },
  };
}
function changeLog() {
  $("input-label").textContent = input();
  if (recording)
    session.configurationChanges.push({
      elapsedMs: Math.round(now() - clockStart),
      paused,
      configuration: configuration(),
    });
}
function setup() {
  const s = $("scenario").value;
  $("scenario-hint").textContent = SCENARIOS[s][1];
  $("live-instructions").textContent = SCENARIOS[s][1];
  $("custom-options").hidden = s !== "CUSTOM";
  $("input-label").textContent = input();
  $("setup-summary").textContent =
    `${input()} · ${SCENARIOS[s][0]} · ${$("purpose").selectedOptions[0].textContent}`;
  $("profile-status").textContent = prefs.profile
    ? `${prefs.profile.response} · fresh target-row hit rate ${Math.round(100 * prefs.profile.targetHitRate)}% · p90 vertical error ${Math.round(prefs.profile.p90VerticalPx)} px${prefs.profile.calibrationKey === calibrationKey() ? "" : " · recalibration requires a new profile"}`
    : "No personal profile saved.";
  $("use-profile").disabled =
    recording ||
    !prefs.profile ||
    prefs.profile.calibrationKey !== calibrationKey();
  if (!recording) {
    engine.reset(now());
    captured = null;
    offer = null;
    offered.clear();
    nextOffer = 0;
    filter.reset();
    lineTarget.reset();
    renderCode();
    renderEffects();
    publish();
  }
}
for (const [id, [name]] of Object.entries(SCENARIOS))
  $("scenario").add(new Option(name, id));
for (const [k, title] of Object.entries({
  focus: "Focus viewed block",
  fadeViewed: "Fade previous blocks",
  relationships: "Lexical name matches",
  historyContext: "Recent viewing context",
  explanations: "Prepare AI context",
})) {
  $("feature-switches").insertAdjacentHTML(
    "beforeend",
    `<label class="check"><input type="checkbox" id="feature-${k}" class="setup-control">${title}</label>`,
  );
}
$("scenario").value = "FOCUS";
$("scenario").addEventListener("change", () => {
  if ($("scenario").value !== "CUSTOM")
    for (const k of ["stableMs", "fadeAfterMs", "explainAfterMs"])
      $(k).value = features($("scenario").value)[k];
});
for (const el of document.querySelectorAll(".setup-control"))
  el.addEventListener("change", () => {
    if (recording) return;
    paused = true;
    setup();
  });
$("cursor").addEventListener("change", () => {
  changeLog();
  renderEffects();
});
function tab(id) {
  engine.exit(now());
  for (const p of document.querySelectorAll(".panel"))
    p.classList.toggle("active", p.id === id);
  for (const b of document.querySelectorAll("[data-tab]"))
    b.classList.toggle("selected", b.dataset.tab === id);
}
for (const b of document.querySelectorAll("[data-tab]"))
  b.onclick = () => tab(b.dataset.tab);
function renderCode() {
  if (!file) return;
  const lines = file.text.split("\n"),
    clean = mask(file.text).split("\n");
  $("code").innerHTML = lines
    .map((s, i) => {
      const block = file.blocks?.find(
        (b) => b.firstLine <= i + 1 && b.lastLine >= i + 1,
      );
      const plain =
        block &&
        !["generatedCode", "recordedDiff", "toolOutput"].includes(block.kind);
      return `<div class="code-line ${block ? "review-" + block.kind : ""}" data-line="${i}"><span class="line-no">${i + 1}</span><span class="line-source">${(plain ? escape(s) : syntax(s, clean[i])) || " "}</span></div>`;
    })
    .join("");
  $("filename").textContent = file.path;
  $("code").classList.toggle(
    "conversation",
    file.contentKind === "codexSession",
  );
  renderEffects();
}
const syntax = (s, clean) => syntaxTail(s, clean);
function syntaxTail(s, clean) {
  let at = 0,
    out = "";
  for (const m of s.matchAll(
    /\b(?:public|private|class|static|void|return|if|else|new|const|let|function|def|import|from|true|false|null|int|boolean|String|async|await)\b|\b\d+(?:\.\d+)?\b|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/.*|#.*/g,
  )) {
    out += escape(s.slice(at, m.index));
    const cls = clean?.slice(m.index, m.index + m[0].length).trim()
      ? /^\d/.test(m[0])
        ? "syn-num"
        : "syn-key"
      : /^['"]/.test(m[0])
        ? "syn-str"
        : "syn-comment";
    out += `<span class="${cls}">${escape(m[0])}</span>`;
    at = m.index + m[0].length;
  }
  return out + escape(s.slice(at));
}
let effectToken = null;
function renderEffects() {
  if (!file) return;
  const o = getOptions(),
    active = !paused && engine.pendingAt === null ? engine.active : null,
    lines = file.text.split("\n");
  for (const el of $("code").children) {
    const i = Number(el.dataset.line),
      inside =
        active?.filePath === file.path &&
        i + 1 >= active.firstLine &&
        i + 1 <= active.lastLine;
    const rr =
      o.fadeViewed && engine.faded.size && !inside ? regionCached(i) : null;
    el.classList.toggle(
      "dim",
      !!(
        (active && o.focus && !inside) ||
        (!inside &&
          o.fadeViewed &&
          rr &&
          engine.faded.has(
            key({
              ...rr,
              filePath: file.path,
              documentStamp: file.version,
              source: input(),
            }),
          ))
      ),
    );
    el.classList.toggle(
      "viewed",
      $("scenario").value === "COVERAGE" &&
        !!lines[i].trim() &&
        (engine.coverage.get(`${file.path}:${i + 1}`) || 0) +
          (engine.visit?.line === i + 1 ? now() - engine.visit.at : 0) >=
          500,
    );
    el.classList.toggle("target", !paused && engine.visit?.line === i + 1);
    el.classList.toggle(
      "cursor",
      !paused && $("cursor").checked && engine.visit?.line === i + 1,
    );
  }
  const token = active && o.relationships ? engine.token : null,
    marker = `${key(active)}:${token}`;
  if (marker !== effectToken) {
    effectToken = marker;
    for (const el of $("code").children) {
      const i = Number(el.dataset.line),
        s = lines[i],
        ranges =
          active && i + 1 >= active.firstLine && i + 1 <= active.lastLine
            ? occurrences(s, token)
            : [];
      let at = 0,
        out = "";
      for (const [a, b] of ranges) {
        out +=
          escape(s.slice(at, a)) + "<mark>" + escape(s.slice(a, b)) + "</mark>";
        at = b;
      }
      if (ranges.length || el.lastElementChild.querySelector("mark"))
        el.lastElementChild.innerHTML = ranges.length
          ? out + escape(s.slice(at))
          : syntaxTail(s, mask(s));
    }
  }
}
let regionCache = new Map();
function regionCached(line) {
  if (!regionCache.has(line))
    regionCache.set(
      line,
      documentRegion(file, line) || region(file.text, line),
    );
  return regionCache.get(line);
}
function fileList() {
  const needle = $("file-filter").value.toLowerCase();
  $("files").replaceChildren();
  for (const name of files
    .filter((x) => x.toLowerCase().includes(needle))
    .slice(0, 300)) {
    const b = document.createElement("button");
    b.textContent = name;
    b.classList.toggle("current", name === file?.path);
    b.onclick = () => loadFile(name).catch((e) => notice(e.message));
    $("files").append(b);
  }
}
function invalidateSource(name) {
  engine.history.invalidate(name);
  engine.prepared = null;
  captured = null;
  offer = null;
  $("prompt").value = "";
  $("context-status").textContent =
    "Source changed. Capture a new version before asking about current code.";
  $("offer").hidden = true;
  publish();
}
async function loadFile(name, force = false) {
  engine.exit(now());
  filter.reset();
  lineTarget.reset();
  if (force || !opened.has(name))
    opened.set(name, await api("file?path=" + encodeURIComponent(name)));
  const next = opened.get(name);
  if (file?.path === name && file.version !== next.version) {
    invalidateSource(name);
    notice(
      "Source changed. Captured context was cleared; older recording snapshots remain.",
    );
  }
  file = next;
  if (file.contentKind !== "codexSession") {
    sourcePath = name;
    reviewMode = "source";
  } else reviewMode = "conversation";
  updateReviewControls();
  regionCache.clear();
  effectToken = null;
  renderCode();
  fileList();
  $("code-scroll").scrollTop = 0;
}
action("example", async () => {
  if (recording)
    throw new Error("Stop the recording before changing project files.");
  const text = await (await fetch("/GazeReviewDemo.java")).text();
  opened.set("GazeReviewDemo.java", {
    path: "GazeReviewDemo.java",
    text,
    version: await digest(text),
  });
  if (!files.includes("GazeReviewDemo.java"))
    files.unshift("GazeReviewDemo.java");
  await loadFile("GazeReviewDemo.java");
});
const digest = async (text) =>
  parseInt(
    [
      ...new Uint8Array(
        await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)),
      ),
    ]
      .map((n) => n.toString(16).padStart(2, "0"))
      .join("")
      .slice(0, 13),
    16,
  );
action("add-files", () => $("file-picker").click());
$("file-picker").onchange = async (e) => {
  try {
    for (const f of e.target.files) {
      if (f.size > 500000) throw new Error("Choose source files below 500 KB.");
      const text = await f.text();
      if (text.includes("\0")) throw new Error("Choose text source files.");
      const name = f.webkitRelativePath || f.name;
      opened.set(name, {
        path: name,
        text,
        version: await digest(text),
        imported: true,
      });
      if (!files.includes(name)) files.push(name);
    }
    if (e.target.files[0])
      await loadFile(
        e.target.files[0].webkitRelativePath || e.target.files[0].name,
      );
    notice("Files opened locally. The source viewer is read-only.");
  } catch (e) {
    notice(e.message);
  }
  e.target.value = "";
};
$("file-filter").oninput = fileList;
action("reload", async () => {
  if (file?.contentKind === "codexSession") return refreshReview();
  if (!file || file.imported)
    return notice("Reopen imported files to refresh their source.");
  const old = file,
    updated = await api("file?path=" + encodeURIComponent(file.path));
  if (updated.version !== old.version) {
    engine.exit(now());
    invalidateSource(old.path);
    opened.set(old.path, updated);
    await loadFile(old.path);
    notice(
      "Source reloaded. Pending context cleared; recorded snapshots are unchanged.",
    );
  } else notice("Source matches the captured version.");
});
$("font-size").onchange = () => {
  engine.exit(now());
  document.documentElement.style.setProperty(
    "--code-size",
    $("font-size").value + "px",
  );
  document.documentElement.style.setProperty(
    "--row-height",
    Math.round(Number($("font-size").value) * 1.7) + "px",
  );
};
function pointToCode(p, t, filtered) {
  const el = document.elementFromPoint(p.x, p.y)?.closest(".code-line");
  if (!el || !$("code").contains(el) || !file) {
    engine.exit(t);
    lineTarget.reset();
    return;
  }
  let line = Number(el.dataset.line);
  if (filtered) {
    const bounds = el.getBoundingClientRect(),
      accepted = lineTarget.update(
        line,
        p.y,
        bounds.top + bounds.height / 2,
        bounds.height,
        t,
        RESPONSES[response()][1],
      );
    if (accepted < 0) {
      const r = {
        ...regionCached(line),
        filePath: file.path,
        documentStamp: file.version,
        source: input(),
      };
      engine.pending(r, t);
      return;
    }
    line = accepted;
    if (line < 0 || line >= file.text.split("\n").length) {
      engine.exit(t);
      return;
    }
  }
  const row = $("code").children[line],
    rect = row.lastElementChild.getBoundingClientRect(),
    charWidth = Number($("font-size").value) * 0.602,
    column = Math.max(0, Math.floor((p.x - rect.left) / charWidth));
  engine.observe(file, line, column, input(), t, getOptions());
}
window.addEventListener("mousemove", (e) => {
  lastPointer = { x: e.clientX, y: e.clientY };
  if (!paused && input() !== "WEBCAM" && document.hasFocus()) routePointer();
});
window.addEventListener("mouseout", (e) => {
  if (!e.relatedTarget) {
    lastPointer = null;
    engine.exit(now());
  }
});
function routePointer() {
  if (!lastPointer) return;
  if (input() === "MOUSE_SIMULATION") {
    const p = filter.update(
      lastPointer.x,
      lastPointer.y,
      now(),
      RESPONSES[response()][0],
    );
    pointToCode(p, now(), true);
  } else pointToCode(lastPointer, now(), false);
}
function suspend() {
  engine.exit(now());
  filter.reset();
  lineTarget.reset();
  renderEffects();
}
window.addEventListener("blur", suspend);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) suspend();
});
$("code-scroll").addEventListener("scroll", suspend, { passive: true });
async function connectCamera() {
  if (recording) throw new Error("Stop recording before changing input.");
  if (tracker || connecting) return;
  connecting = true;
  const generation = ++cameraGeneration;
  $("camera").disabled = true;
  $("stop-camera").disabled = false;
  let candidate;
  try {
    const { UnitEyeWeb } = await import("/vendor/uniteye-cv.js");
    if (generation !== cameraGeneration) return;
    candidate = new UnitEyeWeb({
      screenW: screen.width,
      screenH: screen.height,
      modelUrl: "/vendor/models/eyemu.onnx",
      onStatus: (stage) => {
        $("camera-status").textContent = "Starting: " + stage;
      },
      onGaze: (_final, raw, blink, face) => {
        if (generation !== cameraGeneration) return;
        const t = now();
        if (!face || blink || !raw || !Number.isFinite(raw.x + raw.y)) {
          rawStatus?.(
            !face
              ? "No face detected"
              : blink
                ? "Blink / eyes closed"
                : "No valid prediction",
          );
          suspend();
          return;
        }
        rawStatus?.("Face visible");
        lastRaw = t;
        if (rawTap) {
          rawTap({ ...raw, t });
          return;
        }
        if (
          paused ||
          input() !== "WEBCAM" ||
          !document.hasFocus() ||
          document.hidden
        )
          return;
        if (!compatible()) {
          paused = true;
          changeLog();
          suspend();
          notice(
            "Window or display geometry changed. Recalibrate before resuming webcam tracking.",
          );
          return;
        }
        const p = correct(prefs.calibration.fit, raw),
          smoothed = filter.update(p.x, p.y, t, RESPONSES[response()][0]);
        pointToCode(smoothed, t, true);
      },
    });
    const initialization = candidate.init().then(() => {
      if (generation !== cameraGeneration) candidate.stop();
    });
    await Promise.race([
      initialization,
      new Promise((_, reject) => {
        const timer = setTimeout(
          () =>
            reject(
              new Error(
                "Webcam startup timed out. Check network and camera permission, then retry.",
              ),
            ),
          60000,
        );
        candidate._startupTimer = timer;
      }),
    ]);
    clearTimeout(candidate._startupTimer);
    if (generation !== cameraGeneration) {
      candidate.stop();
      return;
    }
    tracker = candidate;
    tracker.start();
    $("camera-status").textContent =
      "Webcam connected · calibrate before recording";
    notice(
      "Webcam connected. Calibration uses raw samples; video stays local.",
    );
  } catch (e) {
    clearTimeout(candidate?._startupTimer);
    candidate?.stop();
    if (generation !== cameraGeneration) return;
    cameraGeneration++;
    connecting = false;
    $("camera-status").textContent = "Camera unavailable: " + e.message;
    notice(e.message);
    tracker = null;
    paused = true;
    $("input").value = "MOUSE_PROXY";
    $("input-label").textContent = input();
    $("camera").disabled = recording;
    $("stop-camera").disabled = true;
  } finally {
    if (generation === cameraGeneration) {
      connecting = false;
      $("camera").disabled = recording || !!tracker;
      $("stop-camera").disabled = !tracker;
    }
  }
}
action("camera", connectCamera);
function stopCamera() {
  cancelGuide();
  if ($("guide").open) $("guide").close();
  ++cameraGeneration;
  tracker?.stop();
  tracker?.faceLandmarker?.close();
  tracker?.session?.release?.();
  tracker = null;
  connecting = false;
  paused = true;
  suspend();
  $("input").value = "MOUSE_PROXY";
  $("camera").disabled = recording;
  $("stop-camera").disabled = true;
  $("camera-status").textContent = "Camera off · Mouse proxy selected";
  changeLog();
  updateRecordingControls();
  publish();
}
action("stop-camera", stopCamera);
window.addEventListener("beforeunload", () => {
  tracker?.stop();
});
function snapshotSession() {
  if (!session) return null;
  const t = now(),
    s = structuredClone(session);
  if (recording && engine.visit) {
    const start = Math.max(engine.visit.at, clockStart);
    if (t > start) s.events.push(eventOf(engine.visit, start, t));
  }
  if (recording) {
    s.endMs = Math.round(epochStart + t - clockStart);
    s.durationMs = s.endMs - s.startMs;
    s.inProgress = true;
  }
  s.walkthrough = structuredClone(steps);
  return s;
}
function persistSession(s = snapshotSession()) {
  if (!s) return Promise.resolve();
  sessionWrite = sessionWrite.catch(() => {}).then(() => api("session", s));
  return sessionWrite;
}
function updateRecordingControls() {
  for (const el of document.querySelectorAll(".setup-control"))
    el.disabled = recording;
  $("record").disabled = recording;
  $("pause").disabled = !recording;
  $("stop").disabled = !recording;
  $("pause").textContent = paused ? "Resume" : "Pause";
  $("record-status").textContent = recording
    ? `${paused ? "Paused" : "Recording"} · ${input()}`
    : "Setup paused · no recording";
  $("camera").disabled = recording || !!tracker;
  $("stop-camera").disabled = !tracker;
  $("sessions").disabled = recording;
  $("import-session").disabled = recording;
}
action("record", () => {
  if (
    input() === "WEBCAM" &&
    (!tracker || !compatible() || now() - lastRaw > 1000)
  )
    throw new Error(
      "Connect and calibrate the webcam, and ensure valid face samples, before recording.",
    );
  $("review-summary").textContent = JSON.stringify(
    {
      input: input(),
      scenario: SCENARIOS[$("scenario").value][0],
      participant: $("participant").value,
      task: $("task").value,
      condition: $("condition").value,
      features: getOptions(),
      question: $("purpose").value,
    },
    null,
    2,
  );
  $("review-dialog").showModal();
});
action("cancel-start", () => $("review-dialog").close());
action("confirm-start", () => {
  $("review-dialog").close();
  clockStart = now();
  epochStart = Date.now();
  engine.reset(clockStart);
  recording = true;
  paused = false;
  selected = null;
  steps = [];
  traceEdits = {};
  captured = null;
  offer = null;
  nextOffer = 0;
  offered.clear();
  session = {
    id: `${epochStart}-${crypto.randomUUID().slice(0, 8)}`,
    schemaVersion: 3,
    edition: "standalone",
    editionVersion: "1.0.0",
    pluginCompatibilityVersion: "1.0.0",
    project: config.project,
    startMs: epochStart,
    endMs: epochStart,
    durationMs: 0,
    metadata: {
      labels: {
        participant: $("participant").value,
        task: $("task").value,
        condition: $("condition").value,
      },
      initialConfiguration: configuration(),
    },
    configurationChanges: [],
    events: [],
    aiRequests: [],
    walkthrough: [],
  };
  changeLog();
  updateRecordingControls();
  if ($("scenario").value === "WALKTHROUGH") buildTour();
  tab("session");
  notice(
    "Recording started. Work in the code viewer; Stop recording saves the session.",
  );
  publish();
});
action("pause", () => {
  suspend();
  if (paused && input() === "WEBCAM" && (!compatible() || !tracker))
    throw new Error(
      "Webcam requires calibration in the current window geometry. Stop recording to recalibrate.",
    );
  paused = !paused;
  changeLog();
  updateRecordingControls();
  publish();
});
action("stop", async () => {
  suspend();
  const t = now();
  session.endMs = Math.round(epochStart + t - clockStart);
  session.durationMs = session.endMs - session.startMs;
  session.inProgress = false;
  recording = false;
  paused = true;
  session.walkthrough = structuredClone(steps);
  await persistSession(session);
  selected = session;
  updateRecordingControls();
  await library();
  showResults();
  tab("results");
  notice(
    "Recording saved locally. JSON keeps original visits; report annotations affect HTML exports.",
  );
  publish();
});
function prepare(p) {
  const purpose = $("purpose").value;
  if (
    purpose === "COMPARE" &&
    new Set([...p.history, p.region].map(key)).size < 2
  ) {
    $("context-status").textContent =
      "Compare needs two distinct viewed regions in the last 45 seconds.";
    return;
  }
  captured = {
    prompt: prompt(p.region, p.line, p.token, p.history, purpose),
    region: p.region,
    purpose,
    history: p.history,
    capturedAtMs: Date.now(),
    origin: "locally prepared",
  };
  $("context-status").textContent =
    `Captured ${location(p.region)} [${p.region.source}] · prepared locally; not sent.`;
  if (!$("prompt").matches(":focus")) $("prompt").value = captured.prompt;
  const offerKey = key(p.region) + ":" + purpose;
  if (
    $("quiet").checked &&
    !offer &&
    now() >= nextOffer &&
    !offered.has(offerKey)
  ) {
    offered.add(offerKey);
    offer = structuredClone(captured);
    nextOffer = now() + 30000;
  }
  $("offer").hidden = !offer;
  $("offer-label").textContent = offer
    ? `${offer.purpose} · ${location(offer.region)}`
    : "";
  publish();
}
function openCaptured(c = captured) {
  if (!c)
    throw new Error(
      "No captured context yet. Use Gaze-assisted review or explicitly capture the current block.",
    );
  $("prompt").value = c.prompt;
  $("context-status").textContent =
    `${c.origin || "Captured"} · ${location(c.region)} [${c.region.source}]`;
  tab("context");
}
action("preview", () => openCaptured());
action("open-offer", () => {
  const c = offer;
  if (!c) return;
  openCaptured(c);
  offer = null;
  nextOffer = now() + 30000;
  $("offer").hidden = true;
});
action("dismiss-offer", () => {
  offer = null;
  nextOffer = now() + 30000;
  $("offer").hidden = true;
});
action("capture", () => {
  let p = engine.prepared;
  if (
    p &&
    p.region.filePath === file?.path &&
    p.region.documentStamp !== file.version
  )
    p = null;
  if (engine.active)
    p = {
      region: engine.active,
      line: file.text.split("\n")[engine.active.firstLine - 1],
      token: engine.token,
      history: engine.history.recent(now(), input()),
    };
  if (!p && file)
    p = {
      region: {
        ...regionCached(0),
        filePath: file.path,
        documentStamp: file.version,
        source: input(),
      },
      line: file.text.split("\n")[0],
      history: [],
      token: null,
    };
  if (!p) throw new Error("Open a source file first.");
  prepare(p);
  if (captured) captured.origin = "explicit capture";
});
action("copy-prompt", async () => {
  if (!$("prompt").value.trim()) throw new Error("Prepare a prompt first.");
  await navigator.clipboard.writeText($("prompt").value);
  notice("Prompt copied. Paste and send it in native Codex chat when ready.");
});
function agentContext() {
  let viewport = null;
  if (file) {
    const bounds = $("code-scroll").getBoundingClientRect();
    const rows = [...$("code").children].filter((el) => {
      const r = el.getBoundingClientRect();
      return r.bottom > bounds.top && r.top < bounds.bottom;
    });
    if (rows.length) {
      const first = Number(rows[0].dataset.line),
        last = Number(rows.at(-1).dataset.line);
      viewport = {
        filePath: file.path,
        documentStamp: file.version,
        firstLine: first + 1,
        lastLine: last + 1,
        text: file.text
          .split("\n")
          .slice(first, last + 1)
          .join("\n"),
        contentKind: file.contentKind || "sourceFile",
        kind: "viewport",
      };
      if (file.blocks)
        viewport = file.blocks
          .filter(
            (b) =>
              b.lastLine >= first + 1 &&
              b.firstLine <= last + 1 &&
              b.kind !== "heading" &&
              b.name !== "empty" &&
              b.text.trim(),
          )
          .map((b) => {
            const from = Math.max(first + 1, b.firstLine),
              to = Math.min(last + 1, b.lastLine);
            return {
              ...viewport,
              firstLine: from,
              lastLine: to,
              text: file.text
                .split("\n")
                .slice(from - 1, to)
                .join("\n"),
              kind: b.kind,
              provenance: b.provenance,
            };
          });
    }
  }
  const recent =
    captured &&
    Date.now() - captured.capturedAtMs < 600000 &&
    captured.region.filePath === file?.path &&
    captured.region.documentStamp === file.version
      ? captured.region
      : null;
  return buildAgentContext({
    session: snapshotSession(),
    target: engine.active || recent,
    viewport,
    configuration: configuration(),
  });
}
action("preview-agent-context", () => {
  $("agent-context-text").textContent = JSON.stringify(agentContext(), null, 2);
  $("agent-context-dialog").showModal();
});
action("close-agent-context", () => $("agent-context-dialog").close());
async function send(text, questionOrigin = "composer") {
  if (!text.trim()) throw new Error("Enter or capture a question first.");
  const context = agentContext(),
    attach = $("attach-agent-context").checked;
  composeAgentRequest(text, context, attach);
  const r = await api("codex/send", {
    text,
    context,
    attach,
    recordResearch: $("record-agent-example").checked,
    questionOrigin,
    recordingId: recording ? session.id : null,
  });
  externalReview = null;
  reviewGeneration++;
  reviewSnapshot = null;
  reviewMode = "conversation";
  if (recording)
    session.aiRequests.push({
      elapsedMs: Math.round(now() - clockStart),
      threadId: r.threadId,
      turnId: r.turnId,
      action: "explicit_send",
      exampleId: r.exampleId,
      contextAttached: r.contextAttached,
    });
  tab("codex");
  notice(
    "Question sent to the companion Codex chat. The answer streams in the main viewer.",
  );
  await refreshCodex();
  await refreshAgentExamples();
  if (recording)
    await displayReview(
      makeReviewDocument(
        codexState.threadId,
        reviewItems(chatEvents),
        "Companion conversation",
      ),
      { reset: true },
    );
}
let agentExamples = [];
async function refreshAgentExamples() {
  const result = await api("research");
  agentExamples = result.records;
  const chosen = $("agent-examples").value;
  $("agent-examples").replaceChildren(
    ...agentExamples.map(
      (r) =>
        new Option(
          `${new Date(r.createdAtMs).toLocaleString()} · ${r.status} · ${r.question.slice(0, 55)}`,
          r.id,
        ),
    ),
  );
  if (agentExamples.some((r) => r.id === chosen))
    $("agent-examples").value = chosen;
  $("agent-research-status").textContent =
    result.error ||
    `${agentExamples.length} local examples. Native model identity is unknown unless reported. Feedback is an optional user label, not a correctness score.`;
  showAgentExample();
}
function showAgentExample() {
  const r = agentExamples.find((r) => r.id === $("agent-examples").value);
  $("agent-example-question").textContent =
    r?.question ||
    "No research examples yet. Send a question with Save research example locally enabled.";
  $("agent-example-summary").textContent = r
    ? `${r.status} · ${r.contextAttached ? "Context attached" : "Attachment off"} · ${r.context?.regions?.length || 0} available regions · ${r.recordingId ? "Linked to recording " + r.recordingId : "No active recording"}`
    : "";
  $("agent-example-answer").textContent =
    r?.response?.text || "No answer observed for this record.";
  $("agent-example-text").textContent = r
    ? JSON.stringify(r, null, 2)
    : "No research examples yet. Send a question with Save research example locally enabled.";
  $("save-agent-feedback").disabled =
    !!r && ["starting", "in_progress"].includes(r.status);
  $("agent-feedback").value = r?.feedback?.label || "unlabelled";
  $("agent-feedback-note").value = r?.feedback?.note || "";
}
$("agent-examples").onchange = showAgentExample;
action("refresh-agent-examples", refreshAgentExamples);
action("save-agent-feedback", async () => {
  if (!$("agent-examples").value)
    throw new Error("Choose a research example first.");
  await api("research/feedback", {
    id: $("agent-examples").value,
    label: $("agent-feedback").value,
    note: $("agent-feedback-note").value,
  });
  await refreshAgentExamples();
});
action("export-agent-dataset", async () => {
  await refreshAgentExamples();
  download(
    "gazeaide-agent-examples.jsonl",
    "application/x-ndjson",
    agentExamples.map((r) => JSON.stringify(r)).join("\n") + "\n",
  );
});
action("send-context", () =>
  send($("prompt").value, "prepared_or_edited_prompt"),
);
action("send-chat", async () => {
  const text = $("chat-question").value;
  await send(text);
  $("chat-question").value = "";
});
action("connect-codex", async () => {
  await api("codex/connect", {});
  await refreshCodex();
  notice("Codex transport connected. Send a question to verify model access.");
});
action("interrupt", () => api("codex/interrupt", {}));
action("new-chat", async () => {
  await api("codex/new", {});
  chatEvents = [];
  externalReview = null;
  reviewSnapshot = null;
  reviewGeneration++;
  reviewMode = "conversation";
  await refreshCodex();
});
let refreshingCodex = false;
async function refreshCodex() {
  if (refreshingCodex) return;
  refreshingCodex = true;
  try {
    const wasBusy = codexState?.busy;
    codexState = await api("codex");
    if (wasBusy && !codexState.busy) await refreshAgentExamples();
    $("codex-status").textContent =
      `${codexState.status}${codexState.account ? " · " + codexState.account.type : ""}${codexState.busy ? " · answering" : ""}${codexState.threadId ? " · " + codexState.threadId : ""}`;
    chatEvents = codexState.events;
    const messages = new Map();
    let error = "";
    for (const e of chatEvents) {
      const p = e.params;
      if (e.method === "item/agentMessage/delta") {
        const item = messages.get(p.itemId) || { role: "Codex", text: "" };
        item.text += p.delta;
        messages.set(p.itemId, item);
      } else if (e.method === "item/completed" || e.method === "item/started") {
        const item = p.item;
        if (item?.type === "agentMessage")
          messages.set(item.id, {
            role: "Codex",
            text: item.text || messages.get(item.id)?.text || "",
          });
        if (item?.type === "userMessage")
          messages.set(item.id, {
            role: "You",
            text: item.content?.map((c) => c.text || "").join("\n") || "",
          });
      } else if (e.method === "gazeaide/error") error = p.message;
      else if (e.method === "error")
        error = p.error?.message || "Codex request failed";
      else if (e.method === "turn/completed" && p.turn?.error)
        error = p.turn.error.message;
      else if (e.method === "gazeaide/requestDenied")
        error =
          "Review mode declined a tool or permission request: " + p.method;
    }
    const container = $("messages"),
      atBottom =
        container.scrollHeight - container.scrollTop - container.clientHeight <
        60;
    container.replaceChildren();
    for (const m of messages.values()) {
      const el = document.createElement("div");
      el.className = "message " + (m.role === "You" ? "user" : "assistant");
      const label = document.createElement("small");
      label.textContent = m.role.toUpperCase();
      el.append(label, document.createTextNode(m.text));
      container.append(el);
    }
    if (error) {
      const el = document.createElement("p");
      el.textContent = error;
      container.append(el);
    }
    if (atBottom) container.scrollTop = container.scrollHeight;
    if (!externalReview && reviewMode === "conversation") {
      const next = makeReviewDocument(
        codexState.threadId,
        reviewItems(chatEvents),
        "Companion conversation",
      );
      if (
        (recording || !$("follow-output").checked) &&
        reviewSnapshot &&
        next.text !== reviewSnapshot.text
      ) {
        $("pending-review").hidden = false;
      } else if (!recording) await displayReview(next);
    }
  } finally {
    refreshingCodex = false;
  }
}
function makeReviewDocument(id, items, title) {
  return reviewDocument(id, items, title, {
    includeTools: $("include-command-output").checked,
  });
}
function updateReviewControls() {
  const conversation = reviewMode === "conversation";
  $("view-conversation").classList.toggle("selected", conversation);
  $("view-source").classList.toggle("selected", !conversation);
  $("session-tools").hidden = !conversation;
  $("session-compose").hidden = !conversation;
  $("reload").textContent = conversation ? "Refresh session" : "Reload source";
  $("code-scroll").setAttribute(
    "aria-label",
    conversation ? "Codex session content viewer" : "File content viewer",
  );
}
async function displayReview(next, { reset = false } = {}) {
  if (file?.path === next.path && file.text === next.text) {
    updateReviewControls();
    return;
  }
  const scroll = reset ? 0 : $("code-scroll").scrollTop;
  next.version = await digest(next.text);
  engine.exit(now());
  filter.reset();
  lineTarget.reset();
  if (file?.path === next.path && file.version !== next.version)
    invalidateSource(next.path);
  reviewSnapshot = next;
  opened.set(next.path, next);
  file = next;
  reviewMode = "conversation";
  regionCache.clear();
  effectToken = null;
  updateReviewControls();
  renderCode();
  $("review-title").textContent = next.title;
  $("review-status").textContent =
    externalReview && !externalReview.sample
      ? `Local session · ${externalReview.thread.cwd || "project unavailable"} · read-only snapshot · ${externalReview.turns.length} loaded turns. Follow-ups use the separate companion chat.`
      : next.path.endsWith("/sample")
        ? "Sample content · synthetic example, no model request. Try mouse proxy before webcam setup."
        : "Companion conversation · answers stream here outside recording. During recording, Refresh session loads new content explicitly.";
  $("older-turns").hidden = !externalReview?.nextCursor;
  $("pending-review").hidden = true;
  $("code-scroll").scrollTop = scroll;
}
function externalDocument() {
  const items = [...externalReview.turns]
    .reverse()
    .flatMap((t) => (t.items || []).map((item) => ({ ...item, turnId: t.id })));
  return makeReviewDocument(
    externalReview.thread.id,
    items,
    externalReview.thread.name,
  );
}
async function refreshReview(older = false) {
  if (externalReview?.sample) return;
  if (!externalReview) {
    await refreshCodex();
    await displayReview(
      makeReviewDocument(
        codexState.threadId,
        reviewItems(chatEvents),
        "Companion conversation",
      ),
    );
    return;
  }
  const generation = reviewGeneration,
    threadId = externalReview.thread.id;
  const result = await api(
    "codex/thread?id=" +
      encodeURIComponent(threadId) +
      (older && externalReview.nextCursor
        ? "&cursor=" + encodeURIComponent(externalReview.nextCursor)
        : ""),
  );
  if (generation !== reviewGeneration) return;
  if (older) {
    const known = new Set(externalReview.turns.map((t) => t.id));
    externalReview.turns.push(...result.turns.filter((t) => !known.has(t.id)));
    externalReview.nextCursor = result.nextCursor;
    $("follow-output").checked = false;
  } else {
    // Refresh starts at the newest page; do not silently stitch over a possible
    // gap when more than one page was added in the original session.
    externalReview = result;
  }
  await displayReview(externalDocument(), { reset: older });
}
async function ownConversation() {
  externalReview = null;
  reviewGeneration++;
  reviewMode = "conversation";
  reviewSnapshot = null;
  await refreshCodex();
  await displayReview(
    makeReviewDocument(
      codexState.threadId,
      reviewItems(chatEvents),
      "Companion conversation",
    ),
    { reset: true },
  );
}
let checkingExternal = false;
async function checkExternalOutput() {
  if (
    checkingExternal ||
    !externalReview ||
    externalReview.sample ||
    reviewMode !== "conversation" ||
    document.hidden
  )
    return;
  checkingExternal = true;
  const generation = reviewGeneration;
  try {
    if (!recording && $("follow-output").checked) {
      // Other Codex processes may flush item updates without changing the
      // thread's summary timestamp. Read saved history to discover that output.
      const previous = reviewSnapshot?.text;
      await refreshReview();
      if (generation === reviewGeneration && reviewSnapshot?.text !== previous)
        jumpReview("answer");
      return;
    }
    const summary = await api(
      "codex/thread?id=" +
        encodeURIComponent(externalReview.thread.id) +
        "&metadata=true",
    );
    if (generation !== reviewGeneration) return;
    if (
      summary.updatedAt !== externalReview.thread.updatedAt ||
      summary.status?.type === "active"
    ) {
      $("pending-review").hidden = false;
    }
  } catch (e) {
    $("review-status").textContent =
      "Session refresh unavailable: " + e.message;
  } finally {
    checkingExternal = false;
  }
}
action("view-conversation", async () => {
  reviewMode = "conversation";
  if (reviewSnapshot) {
    file = null;
    await displayReview(reviewSnapshot);
  } else await ownConversation();
});
action("view-source", () => loadFile(sourcePath));
action("expand-review", () => {
  suspend();
  const expanded = document.body.classList.toggle("expanded-review");
  $("expand-review").textContent = expanded
    ? "Show setup & research"
    : "Expand viewer";
});
$("include-command-output").onchange = async () => {
  if (externalReview?.sample)
    await displayReview(
      makeReviewDocument(
        "sample",
        externalReview.items,
        "Sample Codex conversation",
      ),
    );
  else if (externalReview) await displayReview(externalDocument());
  else
    await displayReview(
      makeReviewDocument(
        codexState.threadId,
        reviewItems(chatEvents),
        "Companion conversation",
      ),
    );
};
action("own-chat", ownConversation);
action("show-companion", ownConversation);
async function findThreads(more = false) {
  $("thread-status").textContent = "Reading local Codex session list…";
  const result = await api(
    "codex/threads?search=" +
      encodeURIComponent($("thread-search").value.trim()) +
      "&archived=" +
      $("thread-archived").checked +
      (more && threadsCursor
        ? "&cursor=" + encodeURIComponent(threadsCursor)
        : ""),
  );
  availableThreads = more ? [...availableThreads, ...result.data] : result.data;
  threadsCursor = result.nextCursor;
  $("more-threads").hidden = !threadsCursor;
  $("threads").replaceChildren();
  for (const t of availableThreads) {
    const option = document.createElement("option");
    option.value = t.id;
    option.textContent = t.name.slice(0, 130);
    option.title = t.cwd || "";
    $("threads").append(option);
  }
  $("thread-status").textContent =
    `${availableThreads.length} local sessions shown. Select one, then Open session. Sessions on another computer or cloud host may not be available.`;
  await refreshCodex();
}
action("find-threads", () => findThreads());
action("more-threads", () => findThreads(true));
action("open-thread", async () => {
  const id = $("threads").value;
  if (!id) throw new Error("Find and choose a local Codex session first.");
  const generation = ++reviewGeneration;
  const result = await api("codex/thread?id=" + encodeURIComponent(id));
  if (generation !== reviewGeneration) return;
  externalReview = result;
  await displayReview(externalDocument(), { reset: true });
  jumpReview("answer");
  notice("Local Codex session opened for review. No message was sent to it.");
});
action("refresh-thread", () => refreshReview());
action("older-turns", () => refreshReview(true));
function jumpReview(kind) {
  if (file?.contentKind !== "codexSession") return;
  const blocks = file.blocks.filter((b) =>
    kind === "answer"
      ? b.kind === "heading" && b.role === "Codex" && b.phase !== "commentary"
      : b.kind === "recordedDiff",
  );
  if (!blocks.length)
    return notice(
      kind === "answer"
        ? "No answer loaded yet."
        : "No recorded diffs in the loaded session. Generated code may appear inside an answer.",
    );
  const block =
    kind === "answer"
      ? blocks.at(-1)
      : blocks.find(
          (b) =>
            $("code").children[b.firstLine - 1].offsetTop >
            $("code-scroll").scrollTop + 40,
        ) || blocks[0];
  suspend();
  $("code").children[block.firstLine - 1].scrollIntoView({ block: "start" });
}
action("latest-output", () => jumpReview("answer"));
action("next-change", () => jumpReview("diff"));
action("sample-conversation", async () => {
  reviewGeneration++;
  externalReview = {
    sample: true,
    turns: [],
    thread: { id: "sample", cwd: "Synthetic example" },
  };
  const items = [
    {
      id: "sample-user",
      type: "userMessage",
      content: [
        {
          text: "How can we keep a failed calibration target without discarding earlier targets?",
        },
      ],
    },
    {
      id: "sample-answer",
      type: "agentMessage",
      text: "Keep completed targets in a list and retry only the current target. If the camera is slow, collect for longer before deciding whether the target passed.\n\n```javascript\nconst completed = [];\nwhile (completed.length < targets.length) {\n  const trial = await collect(targets[completed.length]);\n  if (trial.qualityPassed) completed.push(trial);\n  else await waitForExplicitRetry();\n}\n```\n\nOnly offer Save after every training and independent check target passes the sampling gates. This sample illustrates the idea; it is not a measurement of gaze accuracy.",
    },
    {
      id: "sample-diff",
      type: "fileChange",
      status: "completed",
      changes: [
        {
          path: "example/calibration.js",
          diff: '@@ -1 +1 @@\n-throw new Error("Not enough samples");\n+await retryCurrentTarget();',
        },
      ],
    },
  ];
  await displayReview(
    makeReviewDocument(
      "sample",
      (externalReview.items = items),
      "Sample Codex conversation",
    ),
    { reset: true },
  );
  notice("Synthetic sample loaded. No camera or model request was used.");
});
function buildTour() {
  const regions = new Map();
  const source = selected || session;
  if (source?.events.length) {
    for (const e of source.events)
      if (e.region && !regions.has(key(e.region)))
        regions.set(key(e.region), e.region);
  }
  // Retain captured regions in first-seen order.
  if (!regions.size)
    for (const f of file?.contentKind === "codexSession"
      ? [file]
      : opened.values()) {
      let i = 0;
      while (i < f.text.split("\n").length) {
        const r = {
          ...(documentRegion(f, i) || region(f.text, i)),
          filePath: f.path,
          documentStamp: f.version,
          source: input(),
        };
        if (!regions.has(key(r))) regions.set(key(r), r);
        i = Math.max(i + 1, r.lastLine);
        if (regions.size >= 150) break;
      }
    }
  const old = new Map(steps.map((s) => [key(s.region), s]));
  steps = [...regions.values()].map(
    (r) => old.get(key(r)) || { region: r, notes: "", reviewed: false },
  );
  stepIndex = 0;
  renderStep();
  publish();
}
function renderStep() {
  const s = steps[stepIndex];
  $("tour-steps").replaceChildren(
    ...steps.map(
      (s, i) =>
        new Option(
          `${i + 1}. ${s.region.label}${s.reviewed ? " ✓" : ""}`,
          String(i),
        ),
    ),
  );
  $("tour-steps").value = String(stepIndex);
  $("tour-overview").textContent =
    `${new Set(steps.map((s) => s.region.filePath)).size} files · ${steps.length} captured code steps · ${steps.filter((s) => s.reviewed).length} marked reviewed by you`;
  $("tour-files").replaceChildren();
  for (const name of new Set(steps.map((s) => s.region.filePath))) {
    const b = document.createElement("button");
    b.textContent = name;
    b.onclick = () => {
      stepIndex = steps.findIndex((s) => s.region.filePath === name);
      renderStep();
    };
    $("tour-files").append(b);
  }
  $("tour-location").textContent = s
    ? `${location(s.region)} [${s.region.source}, version ${s.region.documentStamp}]${opened.get(s.region.filePath)?.version !== s.region.documentStamp ? " · captured snapshot; current source differs or is unavailable" : ""}`
    : "No captured steps";
  $("tour-code").textContent = s?.region.text || "";
  $("tour-notes").value = s?.notes || "";
  $("tour-reviewed").checked = !!s?.reviewed;
}
async function saveTour() {
  const target = selected || session;
  if (target && !recording) {
    target.walkthrough = structuredClone(steps);
    await persistSession(target);
  }
  publish();
}
action("build-tour", () => {
  buildTour();
  tab("tour");
});
$("tour-steps").onchange = () => {
  stepIndex = Number($("tour-steps").value);
  renderStep();
};
action("tour-prev", () => {
  stepIndex = Math.max(0, stepIndex - 1);
  renderStep();
});
action("tour-next", () => {
  stepIndex = Math.min(steps.length - 1, stepIndex + 1);
  renderStep();
});
$("tour-notes").oninput = () => {
  if (steps[stepIndex]) {
    steps[stepIndex].notes = $("tour-notes").value;
    saveTour().catch((e) => notice(e.message));
  }
};
$("tour-reviewed").onchange = () => {
  if (steps[stepIndex]) {
    steps[stepIndex].reviewed = $("tour-reviewed").checked;
    saveTour().catch((e) => notice(e.message));
    renderStep();
  }
};
action("tour-ask", () => {
  const s = steps[stepIndex];
  if (!s) throw new Error("Capture a walkthrough first.");
  captured = {
    region: s.region,
    prompt:
      prompt(s.region, s.region.label, null, [], "REVIEW") +
      "\nDiscuss behavior, inferred rationale (label it as inference), suggested checks and missing context.",
    origin: "Walkthrough snapshot",
  };
  openCaptured();
});
const download = (name, type, text) => {
  const url = URL.createObjectURL(new Blob([text], { type })),
    a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
};
action("export-tour", () => {
  const s = selected || snapshotSession() || { events: [], durationMs: 0 };
  download(
    "gazeaide-walkthrough.html",
    "text/html",
    reportHtml(s, { walkthrough: steps }),
  );
});
async function library() {
  const list = await api("sessions");
  $("sessions").replaceChildren(
    new Option("Current session", ""),
    ...list.map(
      (s) =>
        new Option(
          `${new Date(s.startMs).toLocaleString()} · ${s.metadata?.labels?.participant || "unlabelled"} · ${s.eventCount} visits`,
          s.id,
        ),
    ),
  );
}
action("refresh-sessions", library);
$("sessions").onchange = async () => {
  try {
    if (recording)
      throw new Error(
        "Stop the current recording before opening saved results.",
      );
    selected = $("sessions").value
      ? await api("session?id=" + encodeURIComponent($("sessions").value))
      : session;
    steps = selected?.walkthrough || [];
    traceEdits = {};
    renderStep();
    showResults();
    if (selected?.events.length) {
      const e = selected.events.at(-1),
        r = e.region;
      if (r) {
        captured = {
          region: r,
          prompt: prompt(r, e.lineText, e.word, [], "EXPLAIN"),
          origin:
            "Reconstructed from recording; not evidence of preparation or sending",
        };
      }
    }
  } catch (e) {
    notice(e.message);
  }
};
function resultSession() {
  return selected || snapshotSession();
}
const trace = () => {
  const s = resultSession();
  return (s?.events || [])
    .map((e, i) => ({ e, i }))
    .filter(
      ({ e }) =>
        !$("trace-source").value || e.inputKind === $("trace-source").value,
    );
};
function showResults() {
  const s = resultSession();
  if (!s) return;
  $("result-summary").textContent =
    `${s.events.length} line visits · ${(s.durationMs / 1000).toFixed(1)} s session duration · ${(s.events.reduce((n, e) => n + e.dwellMs, 0) / 1000).toFixed(1)} s exposure${s.inProgress ? " · recovered/in-progress checkpoint" : ""}`;
  $("replay").max = Math.max(0, trace().length - 1);
  showReplay();
  const totals = new Map();
  for (const e of s.events) {
    const k = `${e.file}:${e.line} [${e.inputKind}, v${e.region?.documentStamp ?? "legacy"}]`;
    totals.set(k, (totals.get(k) || 0) + e.dwellMs);
  }
  $("heatmap").innerHTML = [...totals]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 80)
    .map(
      ([k, n]) =>
        `<div class="heat-row" style="background:rgba(8,127,113,${Math.min(0.5, n / 6000)})"><span>${n} ms</span>${escape(k)}</div>`,
    )
    .join("");
}
function showReplay() {
  const visit = trace()[Number($("replay").value)];
  $("replay-label").textContent = visit
    ? `${visit.e.elapsedMs} ms · ${visit.e.file}:${visit.e.line} · ${visit.e.inputKind} · ${visit.e.dwellMs} ms`
    : "No visits in this filter";
  $("replay-code").textContent =
    visit?.e.region?.text || visit?.e.lineText || "";
  $("trace-note").value = visit ? traceEdits[visit.i]?.note || "" : "";
  $("trace-pruned").checked = visit ? !!traceEdits[visit.i]?.pruned : false;
}
$("replay").oninput = showReplay;
$("trace-source").onchange = () => {
  $("replay").value = 0;
  showResults();
};
for (const id of ["trace-note", "trace-pruned"])
  $(id).oninput = () => {
    const v = trace()[Number($("replay").value)];
    if (v)
      traceEdits[v.i] = {
        note: $("trace-note").value,
        pruned: $("trace-pruned").checked,
      };
  };
action("play-replay", () => {
  if (replayTimer) {
    clearInterval(replayTimer);
    replayTimer = null;
    $("play-replay").textContent = "Play trace";
    return;
  }
  replayTimer = setInterval(() => {
    const n = Number($("replay").value);
    if (n >= Number($("replay").max)) {
      $("play-replay").click();
      return;
    }
    $("replay").value = n + 1;
    showReplay();
  }, 700);
  $("play-replay").textContent = "Pause replay";
});
for (const b of document.querySelectorAll("[data-export]"))
  b.onclick = () => {
    const s = resultSession();
    if (!s) return notice("Record or import a session first.");
    const format = b.dataset.export;
    download(
      `gazeaide-${s.id || "import"}.${format}`,
      format === "json"
        ? "application/json"
        : format === "csv"
          ? "text/csv"
          : "text/html",
      format === "json"
        ? JSON.stringify(s, null, 2)
        : format === "csv"
          ? csv(s)
          : reportHtml(s, { traceEdits, walkthrough: steps }),
    );
  };
action("import-session", () => $("session-picker").click());
$("session-picker").onchange = async (e) => {
  try {
    const f = e.target.files[0];
    if (!f || f.size > 32 * 1024 * 1024)
      throw new Error("Choose a JSON recording below 32 MiB.");
    const s = JSON.parse(await f.text());
    if (
      !Array.isArray(s.events) ||
      !Number.isFinite(s.startMs) ||
      s.events.length > 100000
    )
      throw new Error("Invalid recording.");
    for (const e of s.events) {
      if (
        !Number.isFinite(e.line) ||
        !Number.isFinite(e.dwellMs) ||
        e.dwellMs < 0
      )
        throw new Error("Invalid line visit.");
      e.inputKind ??= e.simulated
        ? "MOUSE_SIMULATION"
        : e.source === "WEBCAM"
          ? "WEBCAM"
          : "MOUSE_PROXY";
    }
    s.id ||= `import-${crypto.randomUUID()}`;
    s.schemaVersion = 3;
    s.configurationChanges ??= [];
    s.durationMs ??= Math.max(0, s.endMs - s.startMs);
    s.walkthrough ??= [];
    selected = s;
    steps = s.walkthrough;
    traceEdits = {};
    await persistSession(s);
    await library();
    showResults();
    renderStep();
    notice(
      "Recording imported locally. Source snapshots retain their original provenance.",
    );
  } catch (e) {
    notice(e.message);
  }
  e.target.value = "";
};
const uploadConfig = () => {
  if (!$("upload-consent").checked)
    throw new Error("Review study-specific data handling first.");
  for (const id of ["study-code", "remote-participant"])
    if (!/^[A-Za-z0-9_-]{1,64}$/.test($(id).value))
      throw new Error(
        "Use study and participant codes of 1–64 letters, numbers, underscores or hyphens.",
      );
  if (
    $("remote-task").value &&
    !/^[A-Za-z0-9_-]{1,64}$/.test($("remote-task").value)
  )
    throw new Error("Use a code for the remote task.");
  return {
    studyId: $("study-code").value,
    participantId: $("remote-participant").value,
    taskCode: $("remote-task").value,
  };
};
function previewUpload(kind = "session") {
  const c = uploadConfig(),
    s = resultSession();
  if (kind === "session" && (!s || recording))
    throw new Error("Stop and select a recording first.");
  uploadPreview = {
    endpoint: $("endpoint").value,
    payload: reduced(s, { ...c, kind }),
  };
  $("reduced-preview").textContent = JSON.stringify(
    uploadPreview.payload,
    null,
    2,
  );
}
action("preview-upload", () => previewUpload());
action("test-upload", async () => {
  previewUpload("connection_test");
  await sendUpload(uploadPreview, false);
});
async function sendUpload(item, retain = true) {
  uploadConfig();
  if (!/^[A-Za-z0-9_-]{32,256}$/.test($("upload-token").value))
    throw new Error(
      "Set a participant upload token of 32–256 letters, numbers, underscores or hyphens.",
    );
  if (!item) throw new Error("Preview reduced data first.");
  try {
    await api("upload", { ...item, token: $("upload-token").value });
    if (retain) {
      queue = null;
      prefs.uploadQueue = null;
      await savePrefs();
    }
    $("upload-status").textContent =
      "Receiver acknowledged " + item.payload.uploadId;
  } catch (e) {
    if (retain) {
      queue = item;
      prefs.uploadQueue = item;
      await savePrefs();
    }
    $("upload-status").textContent =
      "Upload failed. Reduced data retained for explicit retry; local recording unchanged.";
    throw e;
  }
}
action("send-upload", () => sendUpload(uploadPreview));
action("retry-upload", () => sendUpload(queue || prefs.uploadQueue));
function publish() {
  const body = {
    status: recording
      ? paused
        ? "Paused recording"
        : "Recording"
      : "Setup/results",
    inputKind: input(),
    recording,
    paused,
    prepared: captured,
    history: engine.history.recent(now(), input()),
    walkthrough: steps,
    agentContext: agentContext(),
  };
  sharedWrite = sharedWrite.catch(() => {}).then(() => api("shared", body));
}
function uiTick() {
  const t = now(),
    foreground =
      !document.hidden &&
      document.hasFocus() &&
      !document.querySelector("dialog[open]");
  if (!paused && foreground) {
    if (input() === "WEBCAM") {
      if (t - lastRaw > 1000) suspend();
      else engine.tick(t);
    } else routePointer();
  } else if (engine.visit) suspend();
  renderEffects();
  const current = session?.events || [];
  $("visit-count").textContent = current.length;
  $("exposure").textContent =
    (
      (current.reduce((n, e) => n + e.dwellMs, 0) +
        (engine.visit ? t - engine.visit.at : 0)) /
      1000
    ).toFixed(1) + " s";
  $("target-status").textContent = engine.visit
    ? `${file.path}:${engine.visit.line} · ${engine.active?.label || "Waiting for stability"}`
    : "No active content target";
  const o = getOptions();
  $("dwell").max = o.explainAfterMs;
  $("dwell").value = engine.dwell.elapsed(t);
  $("dwell-status").textContent = o.explanations
    ? `${Math.min(o.explainAfterMs, Math.round(engine.dwell.elapsed(t)))} / ${o.explainAfterMs} ms in current region`
    : "Context preparation is off in this scenario.";
}
function calibrationResult(samples, fit) {
  const all = samples.flatMap((s) =>
      s.samples.map((p) => {
        const c = correct(fit, p);
        return { x: c.x, y: c.y, tx: s.x, ty: s.y, target: s };
      }),
    ),
    errors = all.map((p) => Math.hypot(p.x - p.tx, p.y - p.ty)),
    vertical = all.map((p) => Math.abs(p.y - p.ty)),
    jitter = [];
  for (const s of samples) {
    const pts = all.filter((p) => p.target === s),
      mx = percentile(
        pts.map((p) => p.x),
        0.5,
      ),
      my = percentile(
        pts.map((p) => p.y),
        0.5,
      );
    jitter.push(...pts.map((p) => Math.hypot(p.x - mx, p.y - my)));
  }
  return {
    version: 2,
    checkedAtMs: Date.now(),
    displayWidth: innerWidth,
    displayHeight: innerHeight,
    medianErrorPx: percentile(errors, 0.5),
    p90ErrorPx: percentile(errors, 0.9),
    medianVerticalPx: percentile(vertical, 0.5),
    p90VerticalPx: percentile(vertical, 0.9),
    jitter90Px: percentile(jitter, 0.9),
    samples: all.length,
    targets: samples.length,
  };
}
let guideRun = 0,
  pendingSave = null,
  retryGuide = null;
async function guided(mode) {
  if ($("guide").open)
    throw new Error("Complete or cancel the current guided task first.");
  if (recording || !tracker || input() !== "WEBCAM")
    throw new Error(
      "Use a connected real webcam outside recording for guided setup.",
    );
  if (mode !== "calibrate" && !compatible())
    throw new Error("Calibrate in the current window geometry first.");
  paused = true;
  suspend();
  const run = ++guideRun,
    initialGeometry = geometry();
  pendingSave = null;
  $("guide-save").hidden = true;
  $("guide-result").textContent = "";
  $("guide-result").hidden = true;
  $("guide-retry").disabled = true;
  $("guide-status").textContent = "Waiting for samples…";
  $("guide-title").textContent =
    mode === "personalize"
      ? "Personal response profile"
      : mode === "accuracy"
        ? "Independent accuracy check"
        : "Calibrate webcam targeting";
  $("guide-text").textContent =
    "Look at the ring. Keep your head and window steady. Cancel retains the earlier calibration/profile.";
  $("guide").showModal();
  const training =
      mode === "personalize"
        ? [1, 7, 3, 8, 2, 6].map((row) => [0.5, (row + 0.5) / 10])
        : mode === "accuracy"
          ? []
          : [
              [0.12, 0.15],
              [0.5, 0.15],
              [0.88, 0.15],
              [0.12, 0.5],
              [0.5, 0.5],
              [0.88, 0.5],
              [0.12, 0.85],
              [0.5, 0.85],
              [0.88, 0.85],
            ],
    checks =
      mode === "personalize"
        ? [4, 9, 0, 5].map((row) => [0.5, (row + 0.5) / 10])
        : [
            [0.27, 0.28],
            [0.73, 0.28],
            [0.27, 0.72],
            [0.73, 0.72],
            [0.5, 0.43],
          ];
  let fit = prefs.calibration?.fit,
    chosen = null;
  try {
    const trials = await runGuideTargets({
      count: training.length + checks.length,
      current: () => run === guideRun,
      beforeTarget: (i, trials) => {
        if (!initialGeometry.every((v, n) => v === geometry()[n]))
          throw new Error("Window geometry changed. Restart guided setup.");
        if (i === training.length && mode === "calibrate") {
          fit = affine(
            trials.map((s) => ({
              x: s.estimate.x,
              y: s.estimate.y,
              tx: s.x,
              ty: s.y,
            })),
          );
          if (!fit)
            throw new Error(
              "Calibration targets did not produce a usable affine fit.",
            );
        }
        if (i === training.length && mode === "personalize")
          chosen = chooseResponse(trials.map(toPersonalTrial), rowHeight());
      },
      collect: async (i, attempt) => {
        const fraction =
          i < training.length ? training[i] : checks[i - training.length];
        const bounds = $("target-field").getBoundingClientRect(),
          localY =
            mode === "personalize"
              ? bounds.height / 2 + (fraction[1] * 10 - 5) * rowHeight()
              : bounds.height * fraction[1],
          x = bounds.left + bounds.width * fraction[0],
          y = bounds.top + localY;
        if (mode === "personalize" && bounds.height < rowHeight() * 10)
          throw new Error(
            "Enlarge the window to fit ten code rows for personalization.",
          );
        $("calibration-target").style.left = fraction[0] * 100 + "%";
        $("calibration-target").style.top = localY + "px";
        $("guide-text").textContent =
          `${i < training.length ? "Training" : "Fresh-target check"} ${i + 1} / ${training.length + checks.length}${attempt ? " · retry " + attempt : ""} · look at the ring`;
        const start = now(),
          samples = [],
          maxSpread = Math.max(
            25,
            Math.hypot(screen.width, screen.height) * 0.035,
          );
        let frameStatus = "Waiting for camera predictions",
          rejected = 0;
        rawStatus = (status) => {
          frameStatus = status;
          if (status !== "Face visible") rejected++;
        };
        rawTap = (p) => {
          if (document.hidden || !document.hasFocus()) {
            frameStatus = "Keep the calibration window in focus";
            return;
          }
          const elapsed = p.t - start;
          if (elapsed >= (mode === "personalize" ? 0 : 650)) samples.push(p);
        };
        let quality;
        do {
          await new Promise((resolve) => setTimeout(resolve, 250));
          if (run !== guideRun) return {};
          if (!initialGeometry.every((v, n) => v === geometry()[n]))
            throw new Error(
              "Window geometry changed. Close and restart guided setup.",
            );
          quality = targetQuality(samples, maxSpread);
          $("guide-status").textContent =
            `${frameStatus}${now() - lastRaw > 1000 ? " · no recent valid samples" : ""} · ${(now() - start).toFixed(0)} / 6000 ms\n${quality.estimate?.retained || 0} stable / ${samples.length} valid samples · ${Math.round(quality.estimate?.spanMs || 0)} ms stable span · ${rejected} invalid frames\n${quality.reasons.join("; ") || "Sample quality passed; finishing collection."}${quality.estimate ? ` · spread ${quality.estimate.spread90.toFixed(0)} / ${maxSpread.toFixed(0)} px` : ""}`;
        } while (
          now() - start < 6000 &&
          !(
            now() - start >= (mode === "personalize" ? 2400 : 2700) &&
            quality.ok
          )
        );
        rawTap = rawStatus = null;
        if (quality.ok) {
          return {
            trial: {
              x,
              y,
              start,
              samples,
              estimate: quality.estimate,
              row:
                mode === "personalize"
                  ? i < training.length
                    ? [1, 7, 3, 8, 2, 6][i]
                    : [4, 9, 0, 5][i - training.length]
                  : i,
            },
          };
        }
        return { quality };
      },
      blocked: ({ index, quality }) =>
        new Promise((resolve) => {
          $("guide-text").textContent =
            `Paused at target ${index + 1} / ${training.length + checks.length} · ${index} completed targets kept`;
          $("guide-status").textContent =
            `${quality.estimate?.retained || 0} stable / ${quality.count} valid samples. ${quality.reasons.join("; ")}.\nMake your face visible, improve front lighting and keep your head still. Look at the ring after clicking Retry this target. You can retry again as often as needed.\nNo targets are skipped; the previous saved calibration remains until Save & use.`;
          $("guide-retry").disabled = false;
          retryGuide = resolve;
        }),
    });
    if (!trials || run !== guideRun) return;
    if (!initialGeometry.every((v, n) => v === geometry()[n]))
      throw new Error("Window geometry changed. Restart guided setup.");
    if (mode === "personalize") {
      const held = trials.slice(training.length).map(toPersonalTrial),
        q = evaluate(held, chosen, rowHeight()),
        standard = evaluate(held, "STEADY", rowHeight());
      const profile = {
        version: 1,
        createdAtMs: Date.now(),
        response: chosen,
        calibrationKey: calibrationKey(),
        p90VerticalPx: q.p90VerticalPx,
        jitter90Px: q.jitter90Px,
        targetHitRate: q.hitRate,
        acquisitionMs: q.acquisitionMs,
        standardHitRate: standard.hitRate,
        standardAcquisitionMs: standard.acquisitionMs,
        rowHeight: rowHeight(),
        targets: 4,
        samples: q.samples,
      };
      $("guide-result").textContent =
        `Frozen selection: ${chosen}\nFresh-target row hit rate: ${(q.hitRate * 100).toFixed(1)}% (Steady: ${(standard.hitRate * 100).toFixed(1)}%)\nP90 vertical error: ${q.p90VerticalPx.toFixed(1)} px\nJitter: ${q.jitter90Px.toFixed(1)} px\nGuided acquisition: ${q.acquisitionMs.toFixed(0)} ms (includes human reaction)\nThis does not validate reading, comprehension or code-review accuracy.`;
      pendingSave = () => {
        prefs.profile = profile;
        $("use-profile").checked = true;
      };
    } else {
      const check = calibrationResult(trials.slice(training.length), fit);
      $("guide-result").textContent =
        `Independent check: ${check.samples} samples / ${check.targets} fresh targets\nMedian / P90 position error: ${check.medianErrorPx.toFixed(1)} / ${check.p90ErrorPx.toFixed(1)} px\nMedian / P90 vertical error: ${check.medianVerticalPx.toFixed(1)} / ${check.p90VerticalPx.toFixed(1)} px\nP90 vertical error: ${(check.p90VerticalPx / rowHeight()).toFixed(1)} code rows\nP90 within-target jitter: ${check.jitter90Px.toFixed(1)} px\nInspect these errors before saving. Filtering cannot correct several rows of targeting bias.`;
      pendingSave = () => {
        prefs.calibration = { fit, check, geometry: initialGeometry };
        if (mode === "calibrate") {
          $("use-profile").checked = false;
        }
      };
    }
    $("guide-text").textContent =
      "Check complete. Save only after reviewing the measurements.";
    $("guide-save").hidden = false;
    $("guide-status").textContent =
      "All targets collected. Raw task samples are discarded when you close this guide.";
    $("guide-result").hidden = false;
  } catch (e) {
    $("guide-result").textContent = e.message;
    $("guide-result").hidden = false;
  } finally {
    if (run === guideRun) {
      rawTap = rawStatus = null;
      $("guide-retry").disabled = true;
      filter.reset();
      lineTarget.reset();
    }
  }
}
const rowHeight = () =>
  parseFloat(
    getComputedStyle(document.documentElement).getPropertyValue("--row-height"),
  ) || 29;
const toPersonalTrial = (t) => ({
  row: t.row,
  y: t.y,
  start: t.start,
  samples: t.samples.map((p) => ({
    ...correct(prefs.calibration.fit, p),
    t: p.t,
  })),
});
action("calibrate", () => guided("calibrate"));
action("accuracy", () => guided("accuracy"));
action("personalize", () => guided("personalize"));
action("guide-retry", () => {
  const resume = retryGuide;
  retryGuide = null;
  $("guide-retry").disabled = true;
  resume?.(true);
});
function cancelGuide() {
  guideRun++;
  rawTap = rawStatus = null;
  pendingSave = null;
  retryGuide?.(false);
  retryGuide = null;
  filter.reset();
  lineTarget.reset();
}
action("guide-cancel", () => {
  cancelGuide();
  $("guide").close();
});
$("guide").addEventListener("cancel", () => {
  cancelGuide();
});
action("guide-save", async () => {
  if (!pendingSave) return;
  pendingSave();
  await savePrefs();
  pendingSave = null;
  $("guide").close();
  guideRun++;
  setup();
  notice("Guided setup saved. Raw task samples were discarded.");
});
action("reset-profile", async () => {
  delete prefs.profile;
  $("use-profile").checked = false;
  await savePrefs();
  setup();
});
action("help", () => $("help-dialog").showModal());
action("close-help", () => $("help-dialog").close());
async function initialize() {
  config = await api("config");
  prefs = config.preferences;
  queue = prefs.uploadQueue;
  $("project-name").textContent = config.project;
  $("project-path").textContent = config.projectRoot;
  $("mcp-command").textContent =
    `codex mcp add gazeaide -- node ${JSON.stringify(config.standaloneRoot + "/mcp.mjs")}${config.dataRoot !== config.standaloneRoot + "/.local" ? " --data " + JSON.stringify(config.dataRoot) : ""}`;
  const listing = await api("files");
  files = listing.files;
  if (listing.truncated)
    notice("Project file list was capped at 2,500 entries.");
  if (files.includes("GazeReviewDemo.java"))
    await loadFile("GazeReviewDemo.java");
  else if (files.length) await loadFile(files[0]);
  else $("example").click();
  setup();
  reviewMode = "conversation";
  await displayReview(makeReviewDocument(null, [], "Companion conversation"));
  await library();
  await refreshCodex();
  setInterval(() => {
    try {
      uiTick();
    } catch (e) {
      paused = true;
      suspend();
      notice(e.message);
    }
  }, 100);
  setInterval(() => {
    if (codexState.busy) refreshCodex().catch(() => {});
  }, 700);
  setInterval(() => {
    publish();
    checkExternalOutput();
    if (
      codexState.status !== "disconnected" ||
      $("codex").classList.contains("active")
    )
      refreshCodex().catch(() => {});
    if (recording)
      persistSession().catch((e) =>
        notice("Recording checkpoint failed: " + e.message),
      );
  }, 5000);
}
initialize().catch((e) => notice(e.message));
