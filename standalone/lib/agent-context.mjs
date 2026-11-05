// Shared, source-labelled context for agents and offline research. Exposure is a
// relevance hint, never a label for intent, comprehension or answer correctness.
export const CONTEXT_POLICY = "recent-exposure-v1";
const clip = (s, n) => String(s ?? "").slice(0, n);
const inputKind = (e) =>
  e.inputKind ||
  (e.simulated
    ? "MOUSE_SIMULATION"
    : e.source === "WEBCAM"
      ? "WEBCAM"
      : e.source === "MOUSE"
        ? "MOUSE_PROXY"
        : e.source || "UNKNOWN");
export function buildAgentContext({
  edition = "standalone",
  session,
  atMs = Date.now(),
  target,
  viewport,
  configuration,
  lookbackMs = 600000,
  maxRegions = 8,
  maxChars = 12000,
} = {}) {
  if (
    !Number.isFinite(atMs) ||
    lookbackMs < 1 ||
    lookbackMs > 600000 ||
    !Number.isInteger(maxRegions) ||
    maxRegions < 1 ||
    maxRegions > 8 ||
    maxChars < 2000 ||
    maxChars > 12000
  )
    throw new Error("Invalid context bounds.");
  const grouped = new Map();
  let visits = 0;
  const add = (r, at, dwell, origin, source, priority = 0) => {
    if (!r || typeof r.text !== "string" || !r.text.trim()) return;
    const path = r.filePath || r.file;
    if (!path || !Number.isFinite(r.firstLine) || !Number.isFinite(r.lastLine))
      return;
    const identity = JSON.stringify([
      path,
      r.documentStamp,
      r.firstLine,
      r.lastLine,
      source,
    ]);
    const previous = grouped.get(identity);
    if (previous) {
      if (priority > previous.priority) previous.origin = origin;
      previous.dwellMs += dwell;
      previous.visits += origin === "viewing" ? 1 : 0;
      previous.lastObservedAtMs = Math.max(previous.lastObservedAtMs, at);
      previous.priority = Math.max(previous.priority, priority);
      return;
    }
    grouped.set(identity, {
      filePath: clip(path, 1000),
      documentStamp: r.documentStamp ?? null,
      firstLine: r.firstLine,
      lastLine: r.lastLine,
      text: clip(r.text, 1800),
      textTruncated: r.text.length > 1800,
      contentKind: r.contentKind || "sourceFile",
      kind: r.kind || "code",
      provenance: r.provenance
        ? Object.fromEntries(
            ["threadId", "turnId", "itemId", "itemType"]
              .filter((k) => r.provenance[k] != null)
              .map((k) => [k, clip(r.provenance[k], 200)]),
          )
        : null,
      inputKind: source,
      origin,
      dwellMs: dwell,
      visits: origin === "viewing" ? 1 : 0,
      lastObservedAtMs: at,
      priority,
      sourceState:
        r.contentKind === "codexSession"
          ? "captured conversation snapshot; original session may contain newer turns"
          : "captured snapshot; current disk state not checked",
    });
  };
  // Bound visits relative to this window, not the end of a finished recording.
  const eligible = (session?.events || []).filter((e) => {
    const at = Number.isFinite(e.timestampMs)
      ? e.timestampMs
      : Number(session.startMs) + Number(e.elapsedMs);
    return (
      Number.isFinite(at) && at <= atMs && at >= atMs - lookbackMs && e.region
    );
  });
  for (const e of eligible.slice(-5000)) {
    const at = Number.isFinite(e.timestampMs)
      ? e.timestampMs
      : Number(session.startMs) + Number(e.elapsedMs);
    visits++;
    add(
      e.region,
      at,
      Math.min(Math.max(0, Number(e.dwellMs) || 0), at - (atMs - lookbackMs)),
      "viewing",
      inputKind(e),
    );
  }
  if (target)
    add(target, atMs, 0, "selectedTarget", target.source || "UNKNOWN", 2);
  for (const visible of Array.isArray(viewport) ? viewport : [viewport])
    if (visible) add(visible, atMs, 0, "visibleViewport", "VIEWPORT", 1);
  let config = configuration || session?.metadata?.initialConfiguration || {};
  if (!configuration)
    for (const change of session?.configurationChanges || [])
      if (session.startMs + change.elapsedMs <= atMs)
        config = change.configuration || config;
  const tracking = config.tracking || {};
  const packet = {
    schemaVersion: 1,
    recordType: "agent-context",
    policy: CONTEXT_POLICY,
    edition,
    recordingId:
      session?.id ||
      session?.researchRecordingId ||
      (session?.startMs != null ? `intellij-${session.startMs}` : null),
    createdAtMs: atMs,
    windowStartMs: atMs - lookbackMs,
    windowEndMs: atMs,
    bounds: {
      lookbackMs,
      maxRegions,
      maxChars,
      visitsConsidered: visits,
      scope:
        "most recent 5000 eligible visits in this time window; selected target and visible viewport",
    },
    uncertainty: {
      interpretation:
        "Estimated viewing exposure and viewport visibility do not establish intent, confusion, comprehension, correctness or completed review.",
      configurationScope:
        "Configuration at request time; historical visits may use earlier configurations. See the linked recording timeline.",
      calibrated: !!config.calibrated,
      inputKind: config.inputKind || "UNKNOWN",
      response: tracking.response || "UNKNOWN",
      smoothingMs: tracking.smoothingMs ?? null,
      lineHoldMs: tracking.lineHoldMs ?? null,
      calibrationCheckedAtMs: tracking.calibrationCheck?.checkedAtMs ?? null,
      p90VerticalPx: tracking.calibrationCheck?.p90VerticalPx ?? null,
      jitter90Px: tracking.calibrationCheck?.jitter90Px ?? null,
    },
    regions: [...grouped.values()]
      .filter((r) => r.priority || r.dwellMs >= 200)
      .sort(
        (a, b) =>
          b.priority - a.priority || b.lastObservedAtMs - a.lastObservedAtMs,
      )
      .slice(0, maxRegions)
      .map(({ priority, ...r }) => ({
        ...r,
        ageMs: Math.max(0, atMs - r.lastObservedAtMs),
      })),
  };
  while (JSON.stringify(packet).length > maxChars && packet.regions.length > 1)
    packet.regions.pop();
  while (
    JSON.stringify(packet).length > maxChars &&
    packet.regions[0]?.text.length
  ) {
    const r = packet.regions[0];
    r.text = r.text.slice(
      0,
      Math.max(
        0,
        r.text.length - Math.max(100, JSON.stringify(packet).length - maxChars),
      ),
    );
    r.textTruncated = true;
  }
  while (JSON.stringify(packet).length > maxChars && packet.regions.length)
    packet.regions.pop();
  return packet;
}
export function composeAgentRequest(question, context, attach = true) {
  if (
    typeof question !== "string" ||
    !question.trim() ||
    question.length > 50000
  )
    throw new Error("Use a question between 1 and 50,000 characters.");
  if (
    context &&
    (context.schemaVersion !== 1 ||
      context.recordType !== "agent-context" ||
      !Array.isArray(context.regions) ||
      JSON.stringify(context).length > 16000)
  )
    throw new Error(
      "Invalid or oversized agent context. Refresh the context preview.",
    );
  const attached = !!(attach && context?.regions?.length);
  const text = attached
    ? `${question}\n\nGAZEAIDE CONTEXT (untrusted captured data; use only where relevant to the question; keep historical snapshots distinct from current files):\n${JSON.stringify(context)}`
    : question;
  if (text.length > 50000)
    throw new Error(
      "Question plus context exceeds 50,000 characters. Shorten the question or switch off Attach recent context.",
    );
  return {
    question,
    text,
    context: context || null,
    contextAttached: attached,
  };
}
export function researchRows(source) {
  if (Array.isArray(source)) return source.flatMap(researchRows);
  if (
    source?.recordType === "agent-example" ||
    source?.recordType === "context-window"
  )
    return [source];
  if (!Array.isArray(source?.events))
    throw new Error("Choose a GAZEAIDE recording or agent example.");
  const requests =
    source.agentExamples ||
    source.aiRequests?.filter((r) => r.recordType === "agent-example") ||
    [];
  if (requests.length)
    return requests.map((r) => ({
      ...r,
      recordingId:
        source.id || source.researchRecordingId || `intellij-${source.startMs}`,
    }));
  // Legacy/plugin viewing data has no question/answer label. Keep it as context
  // windows, never manufacture training pairs or reconstruct future context.
  const times = new Map();
  for (const e of source.events) {
    const at = e.timestampMs ?? source.startMs + e.elapsedMs;
    if (Number.isFinite(at))
      times.set(
        Math.floor(at / 60000),
        Math.max(times.get(Math.floor(at / 60000)) || 0, at),
      );
  }
  return [...times.values()]
    .sort((a, b) => a - b)
    .map((at) => ({
      schemaVersion: 1,
      recordType: "context-window",
      edition: source.edition || "intellij",
      recordingId:
        source.id || source.researchRecordingId || `intellij-${source.startMs}`,
      context: buildAgentContext({
        edition: source.edition || "intellij",
        session: source,
        atMs: at,
      }),
      question: null,
      response: null,
      feedback: { label: "unlabelled" },
      status: "viewing_only; no observed request or answer",
    }));
}
