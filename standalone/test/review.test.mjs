import { test } from "node:test";
import assert from "node:assert/strict";
import { targetQuality, runGuideTargets } from "../lib/guide.mjs";
import { reviewDocument, reviewItems, documentRegion } from "../lib/review.mjs";
import { Engine, features, prompt, csv } from "../lib/core.mjs";
import { reportHtml, reduced } from "../lib/research.mjs";

const stable = Array.from({ length: 12 }, (_, i) => ({
  x: 400 + (i % 2),
  y: 200 + (i % 3),
  t: 1000 + i * 80,
}));
test("slow sample collection can pass unchanged quality gates; low count, short span and spread still fail", () => {
  assert.equal(targetQuality(stable, 25).ok, true);
  assert.equal(targetQuality(stable.slice(0, 9), 25).ok, false);
  assert.equal(
    targetQuality(
      stable.map((p) => ({ ...p, t: 1000 })),
      25,
    ).ok,
    false,
  );
  assert.equal(
    targetQuality(
      stable.map((p, i) => ({ ...p, x: i * 100, y: i * 100 })),
      25,
    ).ok,
    false,
  );
  assert.equal(targetQuality([], 25).estimate, null);
});
test("target 12 resumes after multiple blocked retry batches without losing targets or skipping checks", async () => {
  const attempts = [],
    blocked = [],
    ready = [];
  const result = await runGuideTargets({
    count: 14,
    current: () => true,
    beforeTarget: (index, trials) => ready.push([index, trials.length]),
    collect: async (index, attempt) => {
      attempts.push([index, attempt]);
      return index === 11 && attempt < 7
        ? { quality: { ok: false } }
        : { trial: { index, attempt } };
    },
    blocked: async ({ index, trials }) => {
      blocked.push([index, trials.length]);
      return true;
    },
  });
  assert.deepEqual(blocked, [
    [11, 11],
    [11, 11],
  ]);
  assert.equal(result.length, 14);
  assert.deepEqual(
    result.map((t) => t.index),
    Array.from({ length: 14 }, (_, i) => i),
  );
  assert.equal(result[11].attempt, 7);
  assert.equal(ready.length, 14);
  assert.equal(attempts.filter(([i]) => i === 0).length, 1);
});
test("cancel during a failed target returns no saveable incomplete run and a later run starts cleanly", async () => {
  let active = true;
  const result = await runGuideTargets({
    count: 14,
    current: () => active,
    beforeTarget: () => {},
    collect: async (i) =>
      i < 11 ? { trial: { i } } : { quality: { ok: false } },
    blocked: async () => {
      active = false;
      return false;
    },
  });
  assert.equal(result, null);
  const next = await runGuideTargets({
    count: 1,
    current: () => true,
    beforeTarget: () => {},
    collect: async () => ({ trial: { fresh: true } }),
    blocked: () => assert.fail(),
  });
  assert.deepEqual(next, [{ fresh: true }]);
});
test("Codex streaming merges into one answer and review blocks retain generated text, diffs, and command provenance", () => {
  const events = [
    {
      method: "item/agentMessage/delta",
      params: {
        itemId: "a",
        turnId: "t",
        delta: "Answer\n\n```js\nconst x = 1;\n```",
      },
    },
    {
      method: "item/completed",
      params: {
        turnId: "t",
        item: {
          id: "a",
          type: "agentMessage",
          text: "Answer\n\n```js\nconst x = 1;\n```",
        },
      },
    },
    {
      method: "item/completed",
      params: {
        turnId: "t",
        item: {
          id: "d",
          type: "fileChange",
          status: "completed",
          changes: [{ path: "/project/a.js", diff: "-old\n+new" }],
        },
      },
    },
    {
      method: "item/completed",
      params: {
        turnId: "t",
        item: {
          id: "c",
          type: "commandExecution",
          command: "npm test",
          aggregatedOutput: "3 passed",
          exitCode: 0,
        },
      },
    },
  ];
  const items = reviewItems(events);
  assert.equal(items.length, 3);
  assert.ok(
    !reviewDocument("thread", items).blocks.some(
      (b) => b.kind === "toolOutput",
    ),
  );
  const doc = {
    ...reviewDocument("thread", items, "Test", { includeTools: true }),
    version: 55,
  };
  for (const kind of ["prose", "generatedCode", "recordedDiff", "toolOutput"])
    assert.ok(doc.blocks.some((b) => b.kind === kind));
  const block = doc.blocks.find((b) => b.kind === "generatedCode");
  const r = documentRegion(doc, block.firstLine - 1);
  assert.deepEqual(r.provenance, {
    threadId: "thread",
    turnId: "t",
    itemId: "a",
    itemType: "agentMessage",
  });
  const engine = new Engine();
  engine.observe(
    doc,
    block.firstLine - 1,
    4,
    "MOUSE_PROXY",
    0,
    features("RELATIONSHIPS"),
  );
  engine.tick(3100);
  assert.equal(engine.prepared.region.kind, "generatedCode");
  assert.ok(engine.prepared.region.text.includes("const x"));
  assert.ok(!engine.prepared.region.text.includes("-old"));
  const question = prompt(
    { ...r, filePath: doc.path, source: "MOUSE_PROXY", documentStamp: 55 },
    "const x = 1;",
    null,
  );
  assert.ok(question.includes("codexSession / generatedCode"));
  assert.ok(question.includes('"itemId":"a"'));
});
test("recorded Codex content exports safely while reduced uploads omit messages and thread IDs", () => {
  const doc = reviewDocument("private-thread", [
    { id: "a", type: "agentMessage", text: "<script>private-text</script>" },
  ]);
  const r = {
    ...documentRegion(doc, 1),
    filePath: doc.path,
    documentStamp: 1,
    source: "MOUSE_PROXY",
  };
  const session = {
    schemaVersion: 3,
    id: "synthetic-review",
    startMs: 1000,
    endMs: 2000,
    durationMs: 1000,
    events: [
      {
        timestampMs: 2000,
        elapsedMs: 1000,
        dwellMs: 1000,
        file: doc.path,
        line: 2,
        column: 0,
        lineText: r.text,
        inputKind: "MOUSE_PROXY",
        region: r,
      },
    ],
    metadata: {},
    walkthrough: [],
    configurationChanges: [],
  };
  const html = reportHtml(session);
  assert.ok(!html.includes("<script>private-text</script>"));
  assert.ok(html.includes("private-text"));
  const table = csv(session);
  assert.ok(table.includes("content_kind,content_block_kind,codex_thread_id"));
  assert.ok(table.includes("codexSession"));
  assert.ok(table.includes("private-thread"));
  const upload = JSON.stringify(
    reduced(session, { studyId: "s", participantId: "p" }),
  );
  assert.ok(!upload.includes("private-text"));
  assert.ok(!upload.includes("private-thread"));
});
