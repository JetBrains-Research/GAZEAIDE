import { fileURLToPath } from "node:url";
import { test } from "node:test";
import assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawnSync } from "node:child_process";
import {
  buildAgentContext,
  composeAgentRequest,
  researchRows,
} from "../lib/agent-context.mjs";
import { AgentResearchStore } from "../lib/agent-research.mjs";
import { PointFilter, RESPONSES } from "../lib/core.mjs";
import { start } from "../server.mjs";
const r = {
  filePath: "a.js",
  firstLine: 1,
  lastLine: 3,
  text: "function answer() { return 42; }",
  documentStamp: 1,
  source: "WEBCAM",
};
const event = (at, region = r, inputKind = "WEBCAM", dwellMs = 300) => ({
  timestampMs: at,
  region,
  inputKind,
  dwellMs,
});
const session = {
  startMs: 1000,
  events: [
    event(2000),
    event(2500),
    event(3001),
    event(-999999),
    event(2400, { ...r, documentStamp: 2 }),
    event(2450, r, "MOUSE_SIMULATION"),
  ],
};
test("agent context excludes future/stale visits and keeps input and source versions separate", () => {
  const p = buildAgentContext({ session, atMs: 3000 });
  assert.equal(p.bounds.visitsConsidered, 4);
  assert.equal(p.regions.length, 3);
  const webcam = p.regions.find(
    (x) => x.documentStamp === 1 && x.inputKind === "WEBCAM",
  );
  assert.equal(webcam.visits, 2);
  assert.equal(webcam.dwellMs, 600);
  assert.equal(p.uncertainty.calibrated, false);
  assert.ok(p.uncertainty.interpretation.includes("do not establish"));
  assert.equal(
    researchRows({ ...session, endMs: 4000 })[0].recordingId,
    "intellij-1000",
  );
});
test("long historical recordings select the last 5000 eligible visits without losing early windows", () => {
  const startMs = 1700000000000;
  const long = {
    startMs,
    events: Array.from({ length: 6000 }, (_, i) =>
      event(startMs + i * 100, { ...r, documentStamp: i < 1000 ? 1 : 2 }),
    ),
  };
  const early = buildAgentContext({ session: long, atMs: startMs + 30000 });
  assert.equal(early.bounds.visitsConsidered, 301);
  assert.equal(early.regions.length, 1);
  assert.equal(early.regions[0].documentStamp, 1);
  assert.equal(early.regions[0].lastObservedAtMs, startMs + 30000);
  const latest = buildAgentContext({ session: long, atMs: startMs + 600000 });
  assert.equal(latest.bounds.visitsConsidered, 5000);
  assert.equal(latest.regions.length, 1);
  assert.equal(latest.regions[0].documentStamp, 2);
  const rows = researchRows(long);
  assert.ok(rows.length > 1);
  assert.ok(rows.every((row) => row.context.regions.length > 0));
  assert.ok(
    rows.every((row) =>
      row.context.regions.every(
        (region) => region.lastObservedAtMs <= row.context.windowEndMs,
      ),
    ),
  );
});
test("viewport and selected target take priority, with bounded text and an exact opt-out baseline", () => {
  const p = buildAgentContext({
    session,
    atMs: 3000,
    target: { ...r, text: "target" },
    viewport: { ...r, text: "visible only", firstLine: 20, lastLine: 21 },
  });
  assert.equal(p.regions[0].origin, "selectedTarget");
  assert.equal(p.regions[1].inputKind, "VIEWPORT");
  assert.equal(p.regions[1].dwellMs, 0);
  const base = "  What should I inspect?\n";
  assert.equal(composeAgentRequest(base, p, false).text, base);
  assert.equal(composeAgentRequest(base, p, false).contextAttached, false);
  assert.equal(composeAgentRequest(base, p, true).contextAttached, true);
  assert.ok(
    composeAgentRequest(base, p, true).text.includes("untrusted captured data"),
  );
  const big = buildAgentContext({
    atMs: 3000,
    session: {
      events: Array.from({ length: 60 }, (_, i) =>
        event(2000 + i, {
          ...r,
          filePath: "x".repeat(1000) + i,
          text: "hello\n".repeat(1000),
          documentStamp: i,
        }),
      ),
    },
  });
  assert.ok(JSON.stringify(big).length <= 12000);
  assert.ok(big.regions.length <= 8);
  assert.throws(() =>
    composeAgentRequest("q", {
      schemaVersion: 1,
      recordType: "agent-context",
      regions: null,
    }),
  );
  assert.throws(() => composeAgentRequest("x".repeat(50000), p, true));
});
test("legacy viewing exports never manufacture question/answer pairs; JSONL converter preserves examples", async () => {
  const rows = researchRows({ ...session, endMs: 4000 });
  assert.ok(
    rows.every(
      (x) =>
        x.recordType === "context-window" &&
        x.question === null &&
        x.response === null,
    ),
  );
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "gazeaide-convert-"));
  try {
    const example = {
      recordType: "agent-example",
      id: "example-1",
      question: "q",
      response: null,
    };
    await fs.writeFile(
      path.join(dir, "examples.jsonl"),
      JSON.stringify(example) + "\n",
    );
    const out = path.join(dir, "dataset.jsonl");
    const result = spawnSync(process.execPath, [
      fileURLToPath(new URL("../research-export.mjs", import.meta.url)),
      path.join(dir, "examples.jsonl"),
      "--out",
      out,
    ]);
    assert.equal(result.status, 0, result.stderr.toString());
    assert.deepEqual(JSON.parse(await fs.readFile(out, "utf8")), example);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});
test("research persistence captures only the matching answer and keeps manual feedback durable", async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "gazeaide-store-"));
  try {
    const store = new AgentResearchStore(dir),
      e = {
        id: "example-1",
        recordType: "agent-example",
        turnId: "t1",
        createdAtMs: 1,
        status: "in_progress",
        feedback: { label: "unlabelled" },
      };
    await store.track(e);
    await assert.rejects(store.feedback(e.id, "helpful", "early"), /Wait/);
    store.observe({
      events: [
        {
          sequence: 1,
          method: "item/completed",
          params: {
            turnId: "other",
            item: { type: "agentMessage", id: "other", text: "wrong answer" },
          },
        },
        {
          sequence: 2,
          method: "item/completed",
          params: {
            turnId: "t1",
            item: {
              type: "agentMessage",
              id: "progress",
              phase: "commentary",
              text: "progress",
            },
          },
        },
        {
          sequence: 3,
          method: "item/completed",
          params: {
            turnId: "t1",
            item: {
              type: "agentMessage",
              id: "a",
              phase: "final_answer",
              text: "actual answer",
            },
          },
        },
        {
          sequence: 4,
          method: "turn/completed",
          params: { turn: { id: "t1", status: "completed" } },
        },
      ],
    });
    assert.equal((await store.get(e.id)).response.text, "actual answer");
    await store.feedback(e.id, "helpful", "Checked by user");
    const again = new AgentResearchStore(dir);
    await again.recover();
    assert.equal((await again.get(e.id)).feedback.label, "helpful");
    await again.track({ ...e, id: "unfinished", status: "in_progress" });
    const restarted = new AgentResearchStore(dir);
    await restarted.recover();
    assert.equal((await restarted.get("unfinished")).status, "outcome_unknown");
    await restarted.track({
      ...e,
      id: "interrupted",
      turnId: "t2",
      status: "in_progress",
    });
    restarted.observe({
      events: [
        {
          sequence: 5,
          method: "turn/completed",
          params: { turn: { id: "t2", status: "interrupted" } },
        },
      ],
    });
    assert.equal((await restarted.get("interrupted")).status, "interrupted");
    assert.equal((await restarted.get("interrupted")).response, null);
    await restarted.track({
      ...e,
      id: "disconnected",
      turnId: "t3",
      status: "in_progress",
    });
    restarted.observe({
      events: [
        {
          sequence: 6,
          method: "gazeaide/error",
          params: { message: "lost connection" },
        },
      ],
    });
    assert.equal(
      (await restarted.get("disconnected")).status,
      "outcome_unknown",
    );
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});
test("extra steady attenuates alternating jitter more and still converges on a deliberate step", () => {
  const energy = (preset) => {
    const f = new PointFilter();
    let sum = 0;
    for (let i = 0; i < 100; i++) {
      const p = f.update(0, i % 2 ? 40 : -40, i * 40, RESPONSES[preset][0]);
      if (i > 30) sum += p.y * p.y;
    }
    return sum;
  };
  assert.ok(energy("EXTRA_STEADY") < energy("STEADY"));
  const f = new PointFilter();
  f.update(0, 0, 0, 260);
  let p;
  for (let t = 40; t <= 2000; t += 40) p = f.update(100, 300, t, 260);
  assert.ok(Math.abs(p.y - 300) < 1);
  assert.deepEqual(RESPONSES.EXTRA_STEADY, [260, 260]);
});
test("explicit send attaches the captured packet, links actual answers, supports baseline and recording opt-out", async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "gazeaide-agent-api-")),
    fake = path.join(dir, "fake-codex"),
    log = path.join(dir, "sent.jsonl");
  await fs.writeFile(
    fake,
    `#!/usr/bin/env node
 const fs=require('node:fs');let n=0;const send=x=>process.stdout.write(JSON.stringify(x)+'\\n');require('node:readline').createInterface({input:process.stdin}).on('line',l=>{const m=JSON.parse(l);if(m.method==='initialize')send({id:m.id,result:{}});if(m.method==='account/read')send({id:m.id,result:{account:{type:'chatgpt'}}});if(m.method==='thread/start')send({id:m.id,result:{thread:{id:'thread-1'}}});if(m.method==='turn/start'){fs.appendFileSync(${JSON.stringify(log)},JSON.stringify(m.params.input)+'\\n');const id='turn-'+(++n);send({id:m.id,result:{turn:{id}}});send({method:'item/completed',params:{turnId:id,item:{id:'answer-'+n,type:'agentMessage',phase:'final_answer',text:'Fixture answer '+n}}});send({method:'turn/completed',params:{turn:{id,status:'completed'}}});}});`,
    { mode: 0o700 },
  );
  const app = await start({
    data: path.join(dir, "data"),
    port: 0,
    codexCommand: fake,
  });
  try {
    const { url, token } = app.connection,
      headers = { "x-gazeaide-key": token };
    const send = async (body) => {
      const res = await fetch(url + "/api/codex/send", {
        method: "POST",
        headers,
        body: JSON.stringify(body),
      });
      assert.equal(res.status, 200);
      return res.json();
    };
    const packet = buildAgentContext({ session, atMs: 3000 });
    const sent = await send({
      text: "Question",
      context: packet,
      attach: true,
      recordResearch: true,
      recordingId: "recording-1",
    });
    let records;
    for (let i = 0; i < 30; i++) {
      records = await (await fetch(url + "/api/research", { headers })).json();
      if (records.records[0]?.status === "completed") break;
      await new Promise((r) => setTimeout(r, 10));
    }
    const e = records.records[0];
    assert.equal(e.response.text, "Fixture answer 1");
    assert.equal(e.turnId, sent.turnId);
    assert.equal(e.recordingId, "recording-1");
    assert.equal(e.contextAttached, true);
    assert.equal(e.response.model, null);
    await send({
      text: "Baseline",
      context: packet,
      attach: false,
      recordResearch: false,
    });
    const inputs = (await fs.readFile(log, "utf8"))
      .trim()
      .split("\n")
      .map((l) => JSON.parse(l));
    assert.equal(
      inputs[0][0].text,
      composeAgentRequest("Question", packet, true).text,
    );
    assert.equal(inputs[1][0].text, "Baseline");
    const final = await (
      await fetch(url + "/api/research", { headers })
    ).json();
    assert.equal(final.records.length, 1);
  } finally {
    await app.close();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test("failed sends are recorded as failures without an invented answer", async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "gazeaide-failed-"));
  const app = await start({
    data: dir,
    port: 0,
    codexCommand: path.join(dir, "missing-codex"),
  });
  try {
    const headers = { "x-gazeaide-key": app.connection.token };
    const reply = await fetch(app.connection.url + "/api/codex/send", {
      method: "POST",
      headers,
      body: JSON.stringify({ text: "question", recordResearch: true }),
    });
    assert.equal(reply.status, 404);
    const saved = await (
      await fetch(app.connection.url + "/api/research", { headers })
    ).json();
    assert.equal(saved.records[0].status, "failed");
    assert.equal(saved.records[0].response, null);
  } finally {
    await app.close();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test("shared context fixture matches IntelliJ exactly", async () => {
  const fixture = JSON.parse(
    await fs.readFile(
      new URL("./agent-context-v1.json", import.meta.url),
      "utf8",
    ),
  );
  assert.deepEqual(
    buildAgentContext({ edition: "intellij", ...fixture }),
    fixture.expected,
  );
});
