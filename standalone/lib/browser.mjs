// Browser-side contracts shared with the UI and tested without starting a camera.
export class RecordingClock {
  constructor(epoch = Date.now(), mono = performance.now()) {
    this.epoch = epoch;
    this.mono = mono;
  }
  at(mono = performance.now()) {
    return Math.round(this.epoch + mono - this.mono);
  }
}
export function optionalRating(value) {
  if (String(value).trim() === "") return null;
  const n = Number(value);
  return Number.isInteger(n) && n >= 0 && n <= 10 ? n : null;
}
export function browserKey(location, storage, history) {
  const key = new URLSearchParams(location.hash.slice(1)).get("key");
  if (key && /^[a-f0-9]{64}$/.test(key)) storage.setItem("gazeaide-key", key);
  if (key) history.replaceState(null, "", location.pathname + location.search);
  const saved = storage.getItem("gazeaide-key");
  if (!saved)
    throw new Error(
      "Open the private launch URL printed by GAZEAIDE in the terminal.",
    );
  return saved;
}
// Returns a UTF-16 text offset, matching JavaScript strings, including tabs and wraps.
export function textColumn(document, source, x, y) {
  const position = document.caretPositionFromPoint?.(x, y);
  const range = position ? null : document.caretRangeFromPoint?.(x, y);
  const node = position?.offsetNode || range?.startContainer;
  const offset = position?.offset ?? range?.startOffset;
  if (!node || !source.contains(node)) return null;
  const prefix = document.createRange();
  prefix.selectNodeContents(source);
  prefix.setEnd(node, offset);
  return prefix.toString().length;
}
export function importedSession(s, id) {
  if (
    !s ||
    !Array.isArray(s.events) ||
    !Number.isFinite(s.startMs) ||
    s.events.length > 100000
  )
    throw new Error("Invalid recording.");
  for (const e of s.events) {
    if (
      !e ||
      !Number.isInteger(e.line) ||
      e.line < 1 ||
      !Number.isFinite(e.dwellMs) ||
      e.dwellMs < 0 ||
      !Number.isFinite(e.elapsedMs)
    )
      throw new Error("Invalid line visit.");
    e.inputKind ??= e.simulated
      ? "MOUSE_SIMULATION"
      : e.source === "WEBCAM"
        ? "WEBCAM"
        : "MOUSE_PROXY";
    e.source = e.inputKind;
  }
  return {
    ...s,
    originalId: s.id ?? null,
    id,
    storage: "snapshot",
    schemaVersion: 4,
    configurationChanges: s.configurationChanges || [],
    durationMs: Number.isFinite(s.durationMs)
      ? s.durationMs
      : Math.max(0, (s.endMs || s.startMs) - s.startMs),
    walkthrough: s.walkthrough || [],
  };
}
// Incremental, retryable batches. A region snapshot is transmitted once per recording.
export class RecordingWriter {
  constructor(send) {
    this.send = send;
    this.reset();
  }
  reset() {
    this.id = null;
    this.sequence = 0;
    this.offset = 0;
    this.regions = new Map();
    this.chain = Promise.resolve();
    this.pending = null;
  }
  begin(session) {
    this.reset();
    this.id = session.id;
    return (this.chain = this.send("recording/start", {
      ...session,
      events: [],
    }));
  }
  checkpoint(session) {
    this.chain = this.chain
      .catch(() => {})
      .then(async () => {
        while (this.pending || this.offset < session.events.length) {
          if (!this.pending) {
            const regions = {},
              pending = new Map(this.regions),
              events = session.events
                .slice(this.offset, this.offset + 128)
                .map((e) => {
                  const { region, ...visit } = e;
                  if (!region) return visit;
                  const key = JSON.stringify(region);
                  let id = pending.get(key);
                  if (!id) {
                    id = "r" + pending.size;
                    pending.set(key, id);
                    regions[id] = region;
                  }
                  return { ...visit, regionRef: id };
                });
            this.pending = {
              batch: { id: this.id, sequence: this.sequence, events, regions },
              regions: pending,
              length: events.length,
            };
          }
          const p = this.pending;
          await this.send("recording/append", p.batch);
          this.offset += p.length;
          this.sequence++;
          this.regions = p.regions;
          this.pending = null;
        }
        const { events, ...state } = session;
        return this.send("recording/state", state);
      });
    return this.chain;
  }
}
