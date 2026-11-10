import { promises as fs } from "node:fs";
import path from "node:path";
import { createInterface } from "node:readline";
import { createReadStream } from "node:fs";
import { atomic, validId, hash } from "./storage.mjs";

// Closed visits are durable append records. Only the small mutable checkpoint is replaced.
export class Recordings {
  constructor(directory) {
    this.directory = directory;
    this.queues = new Map();
    this.open = new Map();
  }
  file(id, suffix = ".ndjson") {
    if (!validId(id)) throw new Error("Invalid recording ID");
    return path.join(this.directory, id + suffix);
  }
  serial(id, fn) {
    const next = (this.queues.get(id) || Promise.resolve())
      .catch(() => {})
      .then(fn);
    this.queues.set(id, next);
    return next;
  }
  async begin(s) {
    if (
      !validId(s.id) ||
      ![3, 4].includes(s.schemaVersion) ||
      !Number.isFinite(s.startMs)
    )
      throw new Error("Invalid recording");
    return this.serial(s.id, async () => {
      await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
      const h = await fs.open(this.file(s.id), "wx", 0o600);
      try {
        await h.writeFile(
          JSON.stringify({
            kind: "start",
            session: { ...s, events: [], inProgress: true },
          }) + "\n",
        );
        await h.sync();
      } finally {
        await h.close();
      }
      this.open.set(s.id, {
        sequence: 0,
        regions: new Set(),
        count: 0,
        lastHash: null,
      });
      return { ok: true };
    });
  }
  async append(batch) {
    return this.serial(batch.id, async () => {
      const current = this.open.get(batch.id);
      if (!current) throw new Error("Recording is not open in this server");
      if (
        !Number.isSafeInteger(batch.sequence) ||
        !Array.isArray(batch.events) ||
        batch.events.length > 128 ||
        !batch.regions ||
        typeof batch.regions !== "object"
      )
        throw new Error("Invalid recording batch");
      const digest = hash(JSON.stringify(batch));
      if (
        batch.sequence === current.sequence - 1 &&
        digest === current.lastHash
      )
        return { ok: true };
      if (batch.sequence !== current.sequence)
        throw new Error("Recording batch out of order");
      const known = new Set(current.regions);
      for (const [id, r] of Object.entries(batch.regions)) {
        if (
          !/^r\d+$/.test(id) ||
          known.has(id) ||
          !r ||
          typeof r.text !== "string" ||
          r.text.length > 200000
        )
          throw new Error("Invalid region snapshot");
        known.add(id);
      }
      for (const e of batch.events)
        if (
          !e ||
          !Number.isInteger(e.line) ||
          e.line < 1 ||
          !Number.isFinite(e.dwellMs) ||
          e.dwellMs < 0 ||
          !Number.isFinite(e.elapsedMs) ||
          (e.regionRef && !known.has(e.regionRef))
        )
          throw new Error("Invalid line visit");
      const h = await fs.open(this.file(batch.id), "a");
      try {
        await h.writeFile(JSON.stringify({ kind: "batch", ...batch }) + "\n");
        await h.sync();
      } finally {
        await h.close();
      }
      current.sequence++;
      current.lastHash = digest;
      current.regions = known;
      current.count += batch.events.length;
      return { ok: true };
    });
  }
  async checkpoint(s) {
    return this.serial(s.id, async () => {
      await fs.access(this.file(s.id));
      const { events, ...state } = s;
      await atomic(this.file(s.id, ".state.json"), state);
      return { ok: true };
    });
  }
  async load(id) {
    let session,
      count = 0;
    const regions = new Map();
    const input = createReadStream(this.file(id), { encoding: "utf8" });
    // Ignore only a torn final record; corruption before another complete record is an error.
    let torn = false;
    for await (const line of createInterface({ input, crlfDelay: Infinity })) {
      let record;
      try {
        record = JSON.parse(line);
      } catch {
        torn = true;
        continue;
      }
      if (torn) throw new Error("Recording journal is damaged");
      if (record.kind === "start" && !session)
        session = { ...record.session, events: [] };
      else if (
        record.kind === "batch" &&
        session &&
        record.sequence === count++
      ) {
        for (const [key, r] of Object.entries(record.regions))
          regions.set(key, r);
        for (const e of record.events) {
          const { regionRef, ...event } = e;
          session.events.push(
            regionRef ? { ...event, region: regions.get(regionRef) } : event,
          );
        }
      } else throw new Error("Invalid recording journal");
    }
    if (!session) throw new Error("Recording header is missing");
    try {
      const state = JSON.parse(
        await fs.readFile(this.file(id, ".state.json"), "utf8"),
      );
      delete state.events;
      Object.assign(session, state);
    } catch (e) {
      if (e.code !== "ENOENT") throw e;
    }
    if (session.inProgress && session.activeVisit) {
      const last = session.events.at(-1),
        active = session.activeVisit;
      if (!last || last.elapsedMs < active.elapsedMs)
        session.events.push(active);
    }
    delete session.activeVisit;
    const end = session.events.at(-1)?.elapsedMs || 0;
    session.endMs = Math.max(
      session.endMs || session.startMs,
      session.startMs + end,
    );
    session.durationMs = session.endMs - session.startMs;
    session.recovered = !!session.inProgress;
    session.storage = "journal";
    return session;
  }
  async list() {
    let names;
    try {
      names = await fs.readdir(this.directory);
    } catch (e) {
      if (e.code === "ENOENT") return [];
      throw e;
    }
    const sessions = [];
    for (const n of names.filter((n) => n.endsWith(".ndjson")))
      try {
        sessions.push(await this.load(n.slice(0, -7)));
      } catch {}
    return sessions;
  }
}
