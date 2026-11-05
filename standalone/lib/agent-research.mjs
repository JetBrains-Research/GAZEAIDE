import { promises as fs } from "node:fs";
import path from "node:path";
import { atomic, validId } from "./storage.mjs";
import { reviewItems } from "./review.mjs";
export class AgentResearchStore {
  constructor(directory) {
    this.directory = directory;
    this.pending = Promise.resolve();
    this.active = new Map();
    this.sequence = 0;
    this.error = null;
  }
  save(example) {
    if (!validId(example.id)) throw new Error("Invalid research example ID.");
    const snapshot = structuredClone(example);
    this.pending = this.pending
      .catch(() => {})
      .then(() =>
        atomic(path.join(this.directory, snapshot.id + ".json"), snapshot),
      );
    this.pending.catch(() => {
      this.error = "A local research record could not be saved.";
    });
    return this.pending;
  }
  async recover() {
    await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
    for (const file of (await fs.readdir(this.directory)).filter((f) =>
      f.endsWith(".json"),
    )) {
      let record;
      try {
        record = JSON.parse(
          await fs.readFile(path.join(this.directory, file), "utf8"),
        );
      } catch {
        continue;
      }
      if (
        validId(record?.id) &&
        record.recordType === "agent-example" &&
        ["starting", "in_progress"].includes(record.status)
      ) {
        record.status = "outcome_unknown";
        record.error = "The companion restarted before observing completion.";
        await this.save(record);
      }
    }
  }
  finishUnknown(reason) {
    for (const record of this.active.values()) {
      record.status = "outcome_unknown";
      record.error = reason;
      this.save(record);
    }
    this.active.clear();
  }
  track(example) {
    this.active.set(example.turnId, example);
    return this.save(example);
  }
  observe(snapshot, replay = false) {
    const fresh = snapshot.events.filter(
      (e) => replay || e.sequence > this.sequence,
    );
    for (const e of fresh) {
      if (e.method === "gazeaide/error" && !replay) {
        this.finishUnknown(
          "Codex disconnected before completion was observed.",
        );
        continue;
      }
      if (e.method !== "turn/completed") continue;
      const turn = e.params?.turn,
        record = this.active.get(turn?.id);
      if (!record) continue;
      this.active.delete(turn.id);
      const answers = reviewItems(snapshot.events).filter(
        (i) =>
          i.turnId === turn.id &&
          i.type === "agentMessage" &&
          i.phase !== "commentary",
      );
      record.status = turn.status || "completion_observed; status_unknown";
      record.completedAtMs = Date.now();
      record.response = answers.length
        ? {
            text: answers.map((i) => i.text || "").join("\n\n"),
            items: answers.map((i) => ({
              id: i.id,
              phase: i.phase,
              text: i.text,
            })),
            observed: true,
            complete: turn.status === "completed",
            model: null,
          }
        : null;
      record.error = turn.error?.message || null;
      this.save(record);
    }
    this.sequence = Math.max(this.sequence, ...fresh.map((e) => e.sequence), 0);
  }
  async list() {
    await this.pending.catch(() => {});
    await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
    const records = [];
    for (const f of (await fs.readdir(this.directory)).filter((f) =>
      f.endsWith(".json"),
    )) {
      try {
        records.push(
          JSON.parse(await fs.readFile(path.join(this.directory, f), "utf8")),
        );
      } catch {}
    }
    return records.sort((a, b) => b.createdAtMs - a.createdAtMs);
  }
  async get(id) {
    if (!validId(id)) throw new Error("Invalid research example ID.");
    await this.pending.catch(() => {});
    return JSON.parse(
      await fs.readFile(path.join(this.directory, id + ".json"), "utf8"),
    );
  }
  async feedback(id, label, note) {
    if (
      !["unlabelled", "helpful", "not_helpful", "mixed"].includes(label) ||
      typeof note !== "string" ||
      note.length > 5000
    )
      throw new Error(
        "Choose a feedback label and a note below 5,000 characters.",
      );
    const r = await this.get(id);
    if ([...this.active.values()].some((e) => e.id === id))
      throw new Error("Wait for this answer to finish before adding feedback.");
    r.feedback = { label, note, origin: "user", atMs: Date.now() };
    await this.save(r);
    return r;
  }
}
