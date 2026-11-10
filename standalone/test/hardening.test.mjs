import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtemp,
  rm,
  stat,
  readFile,
  appendFile,
  writeFile,
  mkdir,
  utimes,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  Engine,
  features,
  LineTarget,
  region,
  regionIndex,
  mask,
  csv,
} from "../lib/core.mjs";
import {
  RecordingClock,
  RecordingWriter,
  browserKey,
  textColumn,
  optionalRating,
  importedSession,
} from "../lib/browser.mjs";
import { Recordings } from "../lib/recordings.mjs";
import { reportHtml } from "../lib/research.mjs";
import { BlinkMonitor, blinkConfig, validBlinkData } from "../lib/blinks.mjs";
import { acquireLibrary } from "../lib/library-lock.mjs";
import { start } from "../server.mjs";
const file = { path: "demo.js", text: "alpha\nbeta", version: 1 };
const session = (id) => ({
  id,
  schemaVersion: 3,
  startMs: 1000,
  endMs: 1000,
  durationMs: 0,
  events: [],
  configurationChanges: [],
  walkthrough: [],
  inProgress: true,
});
test("recovered exports retain incomplete status", () => {
  const s = {
    ...session("recovered-export"),
    events: [{ timestampMs: 1100, elapsedMs: 100, line: 1, dwellMs: 100 }],
  };
  const output = csv(s);
  assert.ok(output.includes("session_in_progress"));
  assert.ok(output.endsWith(",true"));
  assert.ok(reportHtml(s).includes("Recovered checkpoint"));
});
async function temporary(fn) {
  const dir = await mkdtemp(path.join(os.tmpdir(), "gazeaide-hardening-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}
test("short visits never inherit a stable word from the preceding line", () => {
  let engine;
  const visits = [];
  engine = new Engine({
    onVisit: (v) => visits.push({ ...v, word: engine.token }),
  });
  engine.observe(file, 0, 1, "WEBCAM", 0, features("NAMES"));
  engine.observe(file, 0, 1, "WEBCAM", 600, features("NAMES"));
  engine.observe(file, 1, 1, "WEBCAM", 650, features("NAMES"));
  engine.exit(700);
  assert.equal(visits[0].word, "alpha");
  assert.equal(visits[1].word, null);
});
test("brief gaps retain only observed dwell; long gaps and changed regions reset residence", () => {
  let prepared = 0;
  const visits = [],
    e = new Engine({
      onPrepared: () => prepared++,
      onVisit: (v) => visits.push(v),
    });
  for (let t = 0; t <= 10000; t += 50) {
    if (t % 2000 >= 1800) e.trackingGap(t);
    else e.observe(file, 0, 1, "WEBCAM", t, features("RELATIONSHIPS"));
  }
  assert.equal(prepared, 1);
  e.exit(10000);
  assert.ok(visits.reduce((n, v) => n + v.dwellMs, 0) < 10000);
  e.reset(11000);
  e.observe(file, 0, 1, "WEBCAM", 11000, features("RELATIONSHIPS"));
  e.trackingGap(12000);
  e.observe(file, 0, 1, "WEBCAM", 13000, features("RELATIONSHIPS"));
  assert.equal(e.dwell.elapsed(13000), 0);
});
test("wrapped paragraph height cannot enlarge the heading hysteresis area", () => {
  const t = new LineTarget();
  assert.equal(t.update(0, 10, 10, 20, 0, 0), 0);
  assert.equal(t.update(1, 120, 120, 200, 100, 0), 1);
});
test("text columns use actual browser text offsets across tabs, spans and fonts", () => {
  const node = {},
    source = { contains: (n) => n === node };
  let end;
  const doc = {
    caretPositionFromPoint: () => ({ offsetNode: node, offset: 6 }),
    createRange: () => ({
      selectNodeContents: (s) => assert.equal(s, source),
      setEnd: (n, o) => {
        end = o;
      },
      toString: () => "\talpha".slice(0, end),
    }),
  };
  assert.equal(textColumn(doc, source, 123, 45), 6);
  assert.equal(
    textColumn(
      { ...doc, caretPositionFromPoint: () => ({ offsetNode: {}, offset: 5 }) },
      source,
      0,
      0,
    ),
    null,
  );
});
test("private fragment bootstrap never needs a secret in public HTML and survives a reload", () => {
  const storage = new Map(),
    api = {
      setItem: (k, v) => storage.set(k, v),
      getItem: (k) => storage.get(k),
    },
    calls = [],
    key = "a".repeat(64);
  assert.equal(
    browserKey({ hash: "#key=" + key, pathname: "/", search: "" }, api, {
      replaceState: (...args) => calls.push(args),
    }),
    key,
  );
  assert.equal(calls[0][2], "/");
  assert.equal(
    browserKey({ hash: "", pathname: "/", search: "" }, api, {}),
    key,
  );
  assert.throws(
    () => browserKey({ hash: "" }, { getItem: () => null }, {}),
    /private launch URL/,
  );
});
test("clock adjustments do not change capture-relative durations or invalidate blink exports", () => {
  const c = new RecordingClock(100000, 500),
    m = new BlinkMonitor({ startMs: c.at(600), config: blinkConfig({}) });
  for (let t = 600; t <= 2600; t += 40)
    m.sample(
      c.at(t),
      t >= 1600 && t < 1760 ? 0.1 : 0.25,
      t >= 1600 && t < 1760 ? 0.1 : 0.25,
      true,
    );
  assert.equal(c.at(2600) - c.at(600), 2000);
  assert.equal(validBlinkData(m.snapshot(c.at(2600))), true);
});
test("missing self-ratings remain missing and imported IDs cannot replace originals", () => {
  assert.equal(optionalRating(""), null);
  assert.equal(optionalRating("0"), 0);
  const s = importedSession(
    {
      ...session("original"),
      events: [
        {
          line: 1,
          dwellMs: 10,
          elapsedMs: 10,
          source: "WEBCAM",
          simulated: true,
        },
      ],
    },
    "import-copy",
  );
  assert.equal(s.id, "import-copy");
  assert.equal(s.originalId, "original");
  assert.equal(s.events[0].source, "MOUSE_SIMULATION");
  assert.throws(() =>
    importedSession(
      { ...s, events: [{ line: 1, dwellMs: 10, elapsedMs: "<script>" }] },
      "other",
    ),
  );
});
test("every imported report field is escaped, including fields normally holding numbers", () => {
  const attack = "<img src=x onerror=alert(1)>",
    s = {
      ...session("s"),
      events: [
        {
          line: attack,
          dwellMs: attack,
          elapsedMs: attack,
          file: "a",
          inputKind: "WEBCAM",
        },
      ],
      walkthrough: [
        {
          region: {
            firstLine: attack,
            lastLine: attack,
            label: "a",
            filePath: "a",
            text: "",
          },
        },
      ],
    };
  const html = reportHtml(s);
  assert.ok(!html.includes(attack));
  assert.ok(html.includes("&lt;img"));
});
test("Rust lifetimes and JS private fields do not hide the rest of the document", () => {
  const rust = "fn f<'a>(x: &'a str) {\n work();\n}",
    js = "class A {\n #value = 1;\n method() { return this.#value; }\n}";
  assert.ok(mask(rust, "demo.rs").includes("work()"));
  assert.ok(mask(js, "demo.js").includes("method()"));
  assert.equal(region(js, 2, "demo.js").firstLine, 3);
  assert.ok(mask("x = 1 # comment", "demo.py").endsWith("         "));
  assert.ok(mask("let x = '\nnext();", "demo.js").includes("next()"));
});
test("indexed regions match structural selection and stay bounded on a large plain file", () => {
  const s = "class A {\n one() {\n run();\n }\n two() { done(); }\n}",
    index = regionIndex(s, "a.js");
  for (let i = 0; i < s.split("\n").length; i++)
    assert.deepEqual(index(i), region(s, i, "a.js"));
  const plain = regionIndex("line\n".repeat(40000), "a.txt");
  for (let i = 0; i < 40000; i++)
    assert.ok(plain(i).lastLine - plain(i).firstLine < 8);
});
test("sub-25Hz blink windows cannot be used as evidence of absent short closures", () => {
  for (const hz of [10, 15, 20, 25, 30]) {
    const m = new BlinkMonitor({ startMs: 0, config: blinkConfig({}) });
    for (let n = 0; n <= hz * 60; n++) {
      const t = (n * 1000) / hz,
        closed = t % 5000 >= 1020 && t % 5000 < 1100;
      m.sample(t, closed ? 0.1 : 0.25, closed ? 0.1 : 0.25);
    }
    assert.equal(m.snapshot(60000).windows[0].usable, hz >= 25);
  }
});
test("append storage exceeds old visit and payload limits without duplicate content", () =>
  temporary(async (dir) => {
    const store = new Recordings(dir),
      writer = new RecordingWriter((route, b) =>
        route.endsWith("start")
          ? store.begin(b)
          : route.endsWith("append")
            ? store.append(b)
            : store.checkpoint(b),
      );
    const s = session("long-session"),
      region = {
        firstLine: 1,
        lastLine: 1,
        text: "x".repeat(6600),
        filePath: "demo",
        documentStamp: 1,
      };
    await writer.begin(s);
    for (let i = 0; i < 100128; i++)
      s.events.push({ line: 1, elapsedMs: i + 1, dwellMs: 1, region });
    await writer.checkpoint(s);
    s.inProgress = false;
    await writer.checkpoint(s);
    assert.equal((await store.load(s.id)).events.length, 100128);
    assert.ok((await stat(store.file(s.id))).size < 32 * 1024 * 1024);
    const text = await readFile(store.file(s.id), "utf8");
    assert.equal(text.split("x".repeat(6600)).length - 1, 1);
  }));
test("a lost acknowledgement retries the exact same batch even when more visits arrive", () =>
  temporary(async (dir) => {
    const store = new Recordings(dir);
    let fail = true;
    const writer = new RecordingWriter(async (route, b) => {
      if (route.endsWith("start")) return store.begin(b);
      if (route.endsWith("state")) return store.checkpoint(b);
      await store.append(b);
      if (fail) {
        fail = false;
        throw new Error("lost acknowledgement");
      }
    });
    const s = session("retry");
    await writer.begin(s);
    s.events.push({ line: 1, elapsedMs: 1, dwellMs: 1 });
    await assert.rejects(writer.checkpoint(s));
    s.events.push({ line: 1, elapsedMs: 2, dwellMs: 1 });
    await writer.checkpoint(s);
    assert.equal((await store.load("retry")).events.length, 2);
  }));
test("crash recovery retains completed visits and the last active checkpoint, ignoring a torn tail", () =>
  temporary(async (dir) => {
    const store = new Recordings(dir),
      s = session("crash");
    await store.begin(s);
    await store.append({
      id: s.id,
      sequence: 0,
      regions: {},
      events: [{ line: 1, dwellMs: 20, elapsedMs: 20 }],
    });
    await store.checkpoint({
      ...s,
      activeVisit: { line: 2, dwellMs: 10, elapsedMs: 30 },
    });
    await appendFile(store.file(s.id), "{torn");
    const recovered = await new Recordings(dir).load(s.id);
    assert.equal(recovered.events.length, 2);
    assert.equal(recovered.inProgress, true);
  }));
test("an incomplete old lock and an abandoned guard do not permanently prevent startup", () =>
  temporary(async (dir) => {
    await writeFile(path.join(dir, ".library-lock"), "");
    await mkdir(path.join(dir, ".library-recovery"));
    const past = new Date(Date.now() - 60000);
    await utimes(path.join(dir, ".library-lock"), past, past);
    await utimes(path.join(dir, ".library-recovery"), past, past);
    const release = await acquireLibrary(dir);
    await release();
  }));
test("unauthenticated clients cannot discover the capability or use the recording API", () =>
  temporary(async (dir) => {
    const app = await start({
      project: dir,
      data: path.join(dir, "data"),
      port: 0,
    });
    try {
      const html = await (await fetch(app.connection.url)).text();
      assert.ok(!html.includes(app.connection.token));
      assert.equal(
        (
          await fetch(app.connection.url + "/api/recording/start", {
            method: "POST",
            body: "{}",
          })
        ).status,
        403,
      );
    } finally {
      await app.close();
    }
  }));
test("authenticated HTTP recording batches and checkpoints survive a server restart", () =>
  temporary(async (dir) => {
    const options = { project: dir, data: path.join(dir, "data"), port: 0 };
    let app = await start(options);
    try {
      const request = async (route, body) => {
        const response = await fetch(app.connection.url + "/api/" + route, {
          method: body ? "POST" : "GET",
          headers: {
            "x-gazeaide-key": app.connection.token,
            "content-type": "application/json",
          },
          body: body ? JSON.stringify(body) : undefined,
        });
        assert.equal(response.status, 200);
        return response.json();
      };
      const s = session("http-roundtrip"),
        writer = new RecordingWriter(request);
      await writer.begin(s);
      for (let i = 0; i < 300; i++)
        s.events.push({
          line: 1,
          dwellMs: 10,
          elapsedMs: (i + 1) * 10,
          region: { text: "one shared block" },
        });
      await writer.checkpoint({
        ...s,
        endMs: 4000,
        activeVisit: { line: 2, dwellMs: 20, elapsedMs: 3020 },
      });
      await app.close();
      app = await start(options);
      const recovered = await request("session?id=" + s.id);
      assert.equal(recovered.events.length, 301);
      assert.equal(recovered.recovered, true);
      assert.equal(recovered.events[299].region.text, "one shared block");
      const listing = await request("sessions");
      assert.equal(listing[0].eventCount, 301);
    } finally {
      await app.close();
    }
  }));
