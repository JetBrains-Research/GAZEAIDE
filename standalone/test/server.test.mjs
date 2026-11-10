import { fileURLToPath } from "node:url";
import { test } from "node:test";
import assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import path from "node:path";
import os from "node:os";
import { spawn, spawnSync } from "node:child_process";
import { createInterface } from "node:readline";
import { start } from "../server.mjs";
import { sourceFile } from "../lib/storage.mjs";
import { reduced } from "../lib/research.mjs";
import { upload, validateReduced } from "../lib/upload.mjs";
import { CodexClient } from "../lib/codex.mjs";
import { acquireLibrary } from "../lib/library-lock.mjs";
const root = await fs.realpath(
  await fs.mkdtemp(path.join(os.tmpdir(), "gazeaide-test-")),
);
process.on("exit", () => {});
test("loopback app enforces capability, origin, project boundary and persistent sessions", async () => {
  const project = path.join(root, "project"),
    data = path.join(root, "data");
  await fs.mkdir(project);
  await fs.writeFile(
    path.join(project, "demo.py"),
    "def test():\n    return 3",
  );
  await fs.writeFile(path.join(root, "outside.txt"), "secret");
  await fs.symlink(
    path.join(root, "outside.txt"),
    path.join(project, "escape.txt"),
  );
  await fs.writeFile(path.join(project, ".env"), "TOKEN=secret");
  const app = await start({ project, data, port: 0 });
  try {
    const { url, token } = app.connection,
      h = { "x-gazeaide-key": token };
    assert.equal((await fetch(url + "/api/config")).status, 403);
    assert.equal(
      (
        await fetch(url + "/api/config", {
          headers: { ...h, Origin: "https://evil.example" },
        })
      ).status,
      403,
    );
    const config = await (
      await fetch(url + "/api/config", { headers: h })
    ).json();
    assert.ok(path.isAbsolute(config.standaloneRoot));
    assert.ok(
      (await fs.stat(path.join(config.standaloneRoot, "mcp.mjs"))).isFile(),
    );
    assert.equal(config.dataRoot, data);
    const files = await (
      await fetch(url + "/api/files", { headers: h })
    ).json();
    assert.deepEqual(files.files, ["demo.py"]);
    await assert.rejects(sourceFile(project, "escape.txt"));
    await assert.rejects(sourceFile(project, "../outside.txt"));
    await assert.rejects(sourceFile(project, ".env"));
    const content = await (
      await fetch(url + "/api/file?path=demo.py", { headers: h })
    ).json();
    assert.ok(Number.isSafeInteger(content.version));
    const original = await (await fetch(url + "/")).text();
    assert.ok(!original.includes("__GAZEAIDE_TOKEN__"));
    assert.ok(!original.includes(token));
    assert.equal(new URL(app.browserUrl).hash,"#key="+token);
    const s = { id: "test-123", schemaVersion: 3, events: [], startMs: 1000 };
    assert.equal(
      (
        await fetch(url + "/api/session", {
          method: "POST",
          headers: { ...h, "Content-Type": "application/json" },
          body: JSON.stringify(s),
        })
      ).status,
      200,
    );
    assert.deepEqual(
      await (
        await fetch(url + "/api/session?id=test-123", { headers: h })
      ).json(),
      s,
    );
    assert.equal(
      (
        await fetch(url + "/api/session", {
          method: "POST",
          headers: h,
          body: JSON.stringify({ ...s, id: "../unsafe" }),
        })
      ).status,
      400,
    );
    assert.equal(
      (await fs.stat(path.join(data, "connection.json"))).mode & 0o777,
      0o600,
    );
  } finally {
    await app.close();
  }
});
test("MCP stdio exposes four read-only tools and captured context from the live app", async () => {
  const data = path.join(root, "mcp"),
    app = await start({ data, port: 0 });
  const child = spawn(
    process.execPath,
    [fileURLToPath(new URL("../mcp.mjs", import.meta.url)), "--data", data],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  const pending = new Map(),
    lines = createInterface({ input: child.stdout });
  lines.on("line", (line) => {
    const m = JSON.parse(line);
    pending.get(m.id)?.(m);
  });
  let serial = 0;
  const rpc = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++serial,
        timer = setTimeout(() => reject(new Error("MCP timeout")), 3000);
      pending.set(id, (m) => {
        clearTimeout(timer);
        resolve(m);
      });
      child.stdin.write(
        JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n",
      );
    });
  try {
    await fetch(app.connection.url + "/api/shared", {
      method: "POST",
      headers: { "x-gazeaide-key": app.connection.token },
      body: JSON.stringify({
        prepared: { prompt: "captured fixture" },
        inputKind: "MOUSE_PROXY",
        recording: true,
        agentContext: {
          schemaVersion: 1,
          recordType: "agent-context",
          regions: [],
        },
      }),
    });
    assert.equal((await rpc("initialize")).result.serverInfo.name, "gazeaide");
    const tools = (await rpc("tools/list")).result.tools;
    assert.equal(tools.length, 4);
    assert.ok(tools.every((t) => t.annotations.readOnlyHint));
    const result = await rpc("tools/call", { name: "get_gaze_context" });
    assert.ok(result.result.content[0].text.includes("captured fixture"));
    const packet = await rpc("tools/call", { name: "get_agent_context" });
    assert.ok(packet.result.content[0].text.includes("agent-context"));
    assert.ok((await rpc("tools/call", { name: "bad" })).error);
  } finally {
    child.kill();
    lines.close();
    await app.close();
  }
});
test("second launches cannot recover or rewrite an active library, even on a different port or alias", async () => {
  const data = path.join(root, "single-owner"),
    alias = path.join(root, "single-owner-alias"),
    app = await start({ data, port: 0 });
  await fs.symlink(data, alias, "dir");
  const record = JSON.stringify({
      id: "live",
      recordType: "agent-example",
      status: "in_progress",
    }),
    file = path.join(data, "agent-examples", "live.json"),
    connection = await fs.readFile(path.join(data, "connection.json"), "utf8");
  try {
    await fs.writeFile(file, record);
    for (const options of [
      { data, port: app.server.address().port },
      { data: alias, port: 0 },
    ]) {
      await assert.rejects(start(options), /already open/);
      assert.equal(await fs.readFile(file, "utf8"), record);
      assert.equal(
        await fs.readFile(path.join(data, "connection.json"), "utf8"),
        connection,
      );
    }
  } finally {
    await app.close();
  }
  const restarted = await start({ data, port: 0 });
  try {
    assert.equal(
      JSON.parse(await fs.readFile(file, "utf8")).status,
      "outcome_unknown",
    );
  } finally {
    await restarted.close();
  }
});
test("port conflicts do not recover records and release library ownership for the next launch", async () => {
  const app = await start({ data: path.join(root, "port-owner"), port: 0 }),
    data = path.join(root, "port-conflict"),
    file = path.join(data, "agent-examples", "pending.json"),
    record = JSON.stringify({
      id: "pending",
      recordType: "agent-example",
      status: "in_progress",
    });
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, record);
  try {
    await assert.rejects(start({ data, port: app.server.address().port }), {
      code: "EADDRINUSE",
    });
    assert.equal(await fs.readFile(file, "utf8"), record);
    await assert.rejects(fs.stat(path.join(data, ".library-lock")), {
      code: "ENOENT",
    });
    const retry = await start({ data, port: 0 });
    await retry.close();
  } finally {
    await app.close();
  }
});
test("concurrent launches have one owner and a dead process lock can be safely reclaimed", async () => {
  const data = path.join(root, "concurrent-owner");
  const attempts = await Promise.allSettled([
    start({ data, port: 0 }),
    start({ data, port: 0 }),
  ]);
  const owners = attempts.filter((r) => r.status === "fulfilled");
  try {
    assert.equal(owners.length, 1);
    assert.equal(attempts.filter((r) => r.status === "rejected").length, 1);
  } finally {
    await Promise.all(owners.map((r) => r.value.close()));
  }
  const dead = spawnSync(process.execPath, [
    "-e",
    "process.stdout.write(String(process.pid))",
  ]);
  assert.equal(dead.status, 0);
  await fs.writeFile(
    path.join(data, ".library-lock"),
    JSON.stringify({ pid: Number(dead.stdout), token: "dead-owner" }),
  );
  const claims = await Promise.allSettled([
    acquireLibrary(data),
    acquireLibrary(data),
  ]);
  const claimed = claims.filter((r) => r.status === "fulfilled");
  try {
    assert.equal(claimed.length, 1);
    assert.equal(
      JSON.parse(await fs.readFile(path.join(data, ".library-lock"), "utf8"))
        .pid,
      process.pid,
    );
  } finally {
    await Promise.all(claimed.map((r) => r.value()));
  }
});
test("very fast replies retain research turn IDs without leaving a stale active turn", async () => {
  const client = new CodexClient(),
    calls = [];
  client.connect = async () => {};
  client.rpc = async (method) => {
    calls.push(method);
    if (method === "thread/start") return { thread: { id: "fast-thread" } };
    if (method === "turn/start") {
      client.receive({
        method: "turn/started",
        params: { turn: { id: "fast-turn" } },
      });
      client.receive({
        method: "turn/completed",
        params: { turn: { id: "fast-turn", status: "completed" } },
      });
      return { turn: { id: "fast-turn" } };
    }
    return {};
  };
  const result = await client.send("fast question");
  assert.equal(result.turnId, "fast-turn");
  assert.equal(client.busy, false);
  assert.equal(client.turnId, null);
  await client.interrupt();
  assert.ok(!calls.includes("turn/interrupt"));
  client.turnId = "disconnected-turn";
  client.fail(new Error("disconnected"));
  assert.equal(client.turnId, null);
});
test("upload keeps token out of payload and only follows Google response redirect using GET", async () => {
  const payload = reduced(null, {
      studyId: "s",
      participantId: "p",
      kind: "connection_test",
    }),
    token = "a".repeat(64),
    calls = [];
  const fake = async (url, options) => {
    calls.push({ url: String(url), ...options });
    if (calls.length === 1)
      return new Response(null, {
        status: 302,
        headers: {
          location: "https://script.googleusercontent.com/macros/echo?x=ok",
        },
      });
    return new Response(
      JSON.stringify({ ok: true, uploadId: payload.uploadId }),
      { status: 200 },
    );
  };
  await upload(
    {
      endpoint: "https://script.google.com/macros/s/test/exec",
      token,
      payload,
    },
    fake,
  );
  assert.equal(calls[0].redirect, "manual");
  assert.equal(JSON.parse(calls[0].body).token, token);
  assert.equal(calls[1].method, "GET");
  assert.ok(!JSON.stringify(calls[1]).includes(token));
  assert.ok(!JSON.stringify(payload).includes(token));
});
test("upload rejects unknown fields, wrong receipts, unsafe redirects and oversized responses", async () => {
  const payload = reduced(null, {
      studyId: "s",
      participantId: "p",
      kind: "connection_test",
    }),
    base = {
      endpoint: "https://receiver.example/upload",
      token: "a".repeat(64),
      payload,
    };
  assert.throws(() => validateReduced({ ...payload, source: "private code" }));
  await assert.rejects(
    upload(
      base,
      async () => new Response(JSON.stringify({ ok: true, uploadId: "wrong" })),
    ),
    /did not acknowledge/,
  );
  await assert.rejects(
    upload(
      { ...base, endpoint: "https://script.google.com/macros/s/test/exec" },
      async () =>
        new Response(null, {
          status: 302,
          headers: { location: "https://evil.example/upload" },
        }),
    ),
    /did not acknowledge/,
  );
  await assert.rejects(
    upload(base, async () => new Response("x".repeat(20000))),
    /did not acknowledge/,
  );
});
test("Codex client handles streaming, explicit requests, cancellation and permission denial over real subprocess transport", async () => {
  const fake = path.join(root, "fake-codex");
  await fs.writeFile(
    fake,
    `#!/usr/bin/env node
const readline=require('node:readline');readline.createInterface({input:process.stdin}).on('line',l=>{const m=JSON.parse(l);const send=x=>process.stdout.write(JSON.stringify(x)+'\\n');if(m.method==='initialize')send({id:m.id,result:{}});if(m.method==='account/read')send({id:m.id,result:{account:{type:'chatgpt',planType:'test'}}});if(m.method==='thread/start')send({id:m.id,result:{thread:{id:'fixture-thread'}}});if(m.method==='turn/start'){send({id:m.id,result:{turn:{id:'fixture-turn'}}});send({method:'item/agentMessage/delta',params:{itemId:'message-1',delta:'fixture answer'}});send({id:900,method:'item/commandExecution/requestApproval',params:{}});}if(m.id===900)send({method:'test/decision',params:m.result});if(m.method==='turn/interrupt'){send({id:m.id,result:{}});send({method:'turn/completed',params:{turn:{id:'fixture-turn',status:'interrupted'}}});}});
`,
    { mode: 0o700 },
  );
  const client = new CodexClient({ cwd: root, command: fake });
  try {
    assert.equal((await client.connect()).status, "connected");
    assert.equal(
      (await client.send("test question")).threadId,
      "fixture-thread",
    );
    await new Promise((r) => setTimeout(r, 50));
    assert.ok(
      client.events.some((e) => e.method === "item/agentMessage/delta"),
    );
    assert.ok(
      client.events.some(
        (e) => e.method === "test/decision" && e.params.decision === "decline",
      ),
    );
    await client.interrupt();
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(client.busy, false);
  } finally {
    client.close();
  }
});
test("new recording artifacts never depend on source-tree paths after copying the standalone folder", async () => {
  const manifest = [
    "server.mjs",
    "mcp.mjs",
    "lib/core.mjs",
    "lib/agent-context.mjs",
    "lib/agent-research.mjs",
    "lib/library-lock.mjs",
    "research-export.mjs",
    "lib/codex.mjs",
    "lib/upload.mjs",
    "lib/storage.mjs",
    "lib/research.mjs",
    "lib/review.mjs",
    "lib/guide.mjs",
    "public/index.html",
    "public/app.mjs",
    "public/style.css",
    "public/vendor/uniteye-core.js",
    "public/vendor/uniteye-cv.js",
    "public/vendor/models/eyemu.onnx",
    "docs/google-drive-receiver.js",
  ];
  for (const f of manifest)
    assert.ok((await fs.stat(new URL("../" + f, import.meta.url))).isFile());
});
test("opening local Codex sessions pages history without resuming or replacing the companion chat", async () => {
  const fake = path.join(root, "history-codex"),
    log = path.join(root, "history-rpc.jsonl");
  await fs.writeFile(
    fake,
    `#!/usr/bin/env node
const fs=require('node:fs');
require('node:readline').createInterface({input:process.stdin}).on('line',l=>{
 const m=JSON.parse(l); fs.appendFileSync(${JSON.stringify(log)},l+'\\n');
 const replies={initialize:{},'account/read':{account:{type:'chatgpt'}},
 'thread/list':{data:[{id:'external-session',name:'External review',cwd:'/project'}],nextCursor:'more'},
 'thread/read':{thread:{id:'external-session',name:'External review',cwd:'/project'}},
 'thread/turns/list':{data:[{id:m.params?.cursor?'older':'latest',items:[{id:'change',type:'fileChange',changes:[{path:'a.js',diff:'-a\\n+b'}]}]}],nextCursor:m.params?.cursor?null:'older-page'}};
 if(m.id!==undefined) process.stdout.write(JSON.stringify({id:m.id,result:replies[m.method]||{}})+'\\n');
});`,
    { mode: 0o700 },
  );
  const app = await start({
    data: path.join(root, "history-data"),
    port: 0,
    codexCommand: fake,
  });
  try {
    const url = app.connection.url,
      headers = { "x-gazeaide-key": app.connection.token };
    assert.equal((await fetch(url + "/api/codex/threads")).status, 403);
    const list = await (
      await fetch(url + "/api/codex/threads?search=External", { headers })
    ).json();
    assert.equal(list.data[0].name, "External review");
    assert.equal(list.nextCursor, "more");
    const latest = await (
      await fetch(url + "/api/codex/thread?id=external-session", { headers })
    ).json();
    assert.equal(latest.turns[0].items[0].type, "fileChange");
    const older = await (
      await fetch(
        url + "/api/codex/thread?id=external-session&cursor=older-page",
        { headers },
      )
    ).json();
    assert.equal(older.turns[0].id, "older");
    const state = await (await fetch(url + "/api/codex", { headers })).json();
    assert.equal(state.threadId, null);
    const methods = (await fs.readFile(log, "utf8"))
      .trim()
      .split("\n")
      .map((l) => JSON.parse(l).method);
    for (const forbidden of [
      "thread/start",
      "thread/resume",
      "thread/fork",
      "turn/start",
    ])
      assert.ok(!methods.includes(forbidden));
    assert.equal(
      (await fetch(url + "/api/codex/thread?id=../bad", { headers })).status,
      400,
    );
  } finally {
    await app.close();
  }
});
