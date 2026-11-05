import http from "node:http";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, timingSafeEqual, randomUUID } from "node:crypto";
import { composeAgentRequest } from "./lib/agent-context.mjs";
import { AgentResearchStore } from "./lib/agent-research.mjs";
import { acquireLibrary } from "./lib/library-lock.mjs";
import { CodexClient } from "./lib/codex.mjs";
import { upload } from "./lib/upload.mjs";
import { atomic, validId, sourceFile, tree, hash } from "./lib/storage.mjs";
const HERE = path.dirname(fileURLToPath(import.meta.url));
export async function start({
  project = path.join(HERE, "public"),
  data = path.join(HERE, ".local"),
  port = 8768,
  codexCommand = process.env.GAZEAIDE_CODEX_BIN || "codex",
} = {}) {
  project = await fs.realpath(project);
  data = path.resolve(data);
  await fs.mkdir(data, { recursive: true, mode: 0o700 });
  data = await fs.realpath(data);
  const releaseLibrary = await acquireLibrary(data);
  let server, codex;
  try {
    await fs.mkdir(path.join(data, "sessions"), {
      recursive: true,
      mode: 0o700,
    });
    const token = randomBytes(32).toString("hex");
    let shared = {
      status: "No viewing context yet",
      prepared: null,
      walkthrough: [],
      recording: false,
    };
    let pref = {};
    try {
      pref = JSON.parse(
        await fs.readFile(path.join(data, "preferences.json"), "utf8"),
      );
    } catch {}
    let saved = {};
    try {
      saved = JSON.parse(
        await fs.readFile(path.join(data, "codex-thread.json"), "utf8"),
      );
    } catch {}
    let persist = Promise.resolve();
    const research = new AgentResearchStore(path.join(data, "agent-examples"));
    let sending = false;
    codex = new CodexClient({
      cwd: project,
      command: codexCommand,
      savedThread: saved.project === project ? saved.threadId : null,
      onChange: () => {
        research.observe(codex.snapshot());
        persist = persist
          .then(() =>
            atomic(path.join(data, "codex-thread.json"), {
              project,
              threadId: codex.threadId,
            }),
          )
          .catch(() => {});
      },
    });
    let ready = false;
    server = http.createServer(async (req, res) => {
      if (!ready) {
        res.writeHead(503, { "Retry-After": "1" });
        res.end("Opening the local library. Please retry.");
        return;
      }
      const fail = (status, message) => {
        res.writeHead(status, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: message }));
      };
      const json = (value) => {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(value));
      };
      try {
        const origin = `http://127.0.0.1:${server.address().port}`;
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cache-Control", "no-store");
        if (req.headers.host !== `127.0.0.1:${server.address().port}`)
          return fail(403, "Invalid host");
        if (req.headers.origin && req.headers.origin !== origin)
          return fail(403, "Cross-origin requests are disabled");
        const url = new URL(req.url, origin),
          route = url.pathname;
        if (route.startsWith("/api/")) {
          const supplied = Buffer.from(req.headers["x-gazeaide-key"] || "");
          if (
            supplied.length !== token.length ||
            !timingSafeEqual(supplied, Buffer.from(token))
          )
            return fail(403, "Open the local GAZEAIDE app first.");
          let body = {};
          if (req.method === "POST") {
            let bytes = 0,
              chunks = [];
            for await (const chunk of req) {
              bytes += chunk.length;
              if (bytes > 32 * 1024 * 1024)
                return fail(413, "Request exceeds 32 MiB");
              chunks.push(chunk);
            }
            try {
              body = JSON.parse(Buffer.concat(chunks).toString());
            } catch {
              return fail(400, "Invalid JSON");
            }
          } else if (req.method !== "GET") return fail(405, "Use GET or POST");
          if (req.method === "GET") {
            if (route === "/api/config")
              return json({
                project: path.basename(project),
                projectRoot: project,
                version: "1.0.0",
                standaloneRoot: HERE,
                dataRoot: data,
                preferences: pref,
                port: server.address().port,
              });
            if (route === "/api/files") return json(await tree(project));
            if (route === "/api/file")
              return json(
                await sourceFile(project, url.searchParams.get("path")),
              );
            if (route === "/api/shared") return json(shared);
            if (route === "/api/codex") return json(codex.snapshot());
            if (route === "/api/research")
              return json({
                records: await research.list(),
                error: research.error,
              });
            if (route === "/api/codex/threads")
              return json(
                await codex.listThreads({
                  cursor: url.searchParams.get("cursor") || undefined,
                  searchTerm: url.searchParams.get("search") || undefined,
                  archived: url.searchParams.get("archived") === "true",
                }),
              );
            if (route === "/api/codex/thread") {
              if (url.searchParams.get("metadata") === "true")
                return json(
                  await codex.threadSummary(url.searchParams.get("id")),
                );
              return json(
                await codex.readThread(
                  url.searchParams.get("id"),
                  url.searchParams.get("cursor") || undefined,
                ),
              );
            }
            if (route === "/api/sessions") {
              const names = (await fs.readdir(path.join(data, "sessions")))
                .filter((n) => n.endsWith(".json"))
                .sort()
                .reverse();
              const out = [];
              for (const name of names) {
                try {
                  const s = JSON.parse(
                    await fs.readFile(
                      path.join(data, "sessions", name),
                      "utf8",
                    ),
                  );
                  out.push({
                    id: s.id,
                    startMs: s.startMs,
                    metadata: s.metadata,
                    eventCount: s.events.length,
                    project: s.project,
                  });
                } catch {}
              }
              return json(out);
            }
            if (route === "/api/session") {
              const id = url.searchParams.get("id");
              if (!validId(id)) return fail(400, "Invalid session ID");
              return json(
                JSON.parse(
                  await fs.readFile(
                    path.join(data, "sessions", id + ".json"),
                    "utf8",
                  ),
                ),
              );
            }
          } else {
            if (route === "/api/preferences") {
              pref = body;
              await atomic(path.join(data, "preferences.json"), pref);
              return json({ ok: true });
            }
            if (route === "/api/shared") {
              shared = { ...body, updatedAtMs: Date.now() };
              return json({ ok: true });
            }
            if (route === "/api/session") {
              if (
                !validId(body.id) ||
                body.schemaVersion !== 3 ||
                !Array.isArray(body.events) ||
                body.events.length > 100000
              )
                return fail(400, "Invalid session");
              await atomic(
                path.join(data, "sessions", body.id + ".json"),
                body,
              );
              return json({ ok: true });
            }
            if (route === "/api/codex/connect")
              return json(await codex.connect());
            if (route === "/api/codex/send") {
              if (sending)
                return fail(400, "A question is already being sent.");
              sending = true;
              let record;
              try {
                const request = composeAgentRequest(
                  body.text,
                  body.context,
                  body.attach !== false,
                );
                if (body.recordResearch === true) {
                  record = {
                    schemaVersion: 1,
                    recordType: "agent-example",
                    edition: "standalone",
                    id: randomUUID(),
                    createdAtMs: Date.now(),
                    recordingId:
                      typeof body.recordingId === "string"
                        ? body.recordingId.slice(0, 100)
                        : null,
                    questionOrigin:
                      body.questionOrigin === "prepared_or_edited_prompt"
                        ? "prepared_or_edited_prompt"
                        : "composer",
                    ...request,
                    status: "starting",
                    response: null,
                    feedback: { label: "unlabelled" },
                  };
                  await research.save(record);
                }
                const r = await codex.send(request.text);
                if (record) {
                  Object.assign(record, r, { status: "in_progress" });
                  await research.track(record).catch(() => {});
                  research.observe(codex.snapshot(), true);
                }
                return json({
                  ...r,
                  exampleId: record?.id || null,
                  contextAttached: request.contextAttached,
                });
              } catch (e) {
                if (record) {
                  record.status = "failed";
                  record.error = e.message;
                  await research.save(record);
                }
                throw e;
              } finally {
                sending = false;
              }
            }
            if (route === "/api/research/feedback")
              return json(
                await research.feedback(body.id, body.label, body.note),
              );
            if (route === "/api/codex/interrupt") {
              await codex.interrupt();
              return json({ ok: true });
            }
            if (route === "/api/codex/new") {
              await codex.newThread();
              return json({ ok: true });
            }
            if (route === "/api/upload") {
              return json(await upload(body));
            }
          }
          return fail(404, "Unknown API");
        }
        if (req.method !== "GET") return fail(405, "Use GET");
        let file =
          route === "/"
            ? path.join(HERE, "public/index.html")
            : route.startsWith("/lib/")
              ? path.join(HERE, route)
              : path.join(HERE, "public", route);
        file = await fs.realpath(file);
        if (
          !file.startsWith(path.join(HERE, "public") + path.sep) &&
          !file.startsWith(path.join(HERE, "lib") + path.sep)
        )
          return fail(403, "Invalid asset");
        const ext = path.extname(file),
          types = {
            ".html": "text/html; charset=utf-8",
            ".mjs": "text/javascript",
            ".js": "text/javascript",
            ".css": "text/css",
            ".onnx": "application/octet-stream",
            ".java": "text/plain",
          };
        // HTML has no remote scripts; only the explicitly enabled webcam runtime loads pinned CDNs.
        res.setHeader(
          "Content-Security-Policy",
          "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://cdn.jsdelivr.net https://storage.googleapis.com; worker-src 'self' blob:; img-src 'self' data:; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        );
        res.setHeader(
          "Permissions-Policy",
          "camera=(self), microphone=(), geolocation=()",
        );
        const content = await fs.readFile(file);
        res.writeHead(200, {
          "Content-Type": types[ext] || "application/octet-stream",
        });
        res.end(
          route === "/"
            ? content.toString().replace("__GAZEAIDE_TOKEN__", token)
            : content,
        );
      } catch (e) {
        fail(
          e.code === "ENOENT" ? 404 : 400,
          e.code === "ENOENT" ? "File not found" : e.message,
        );
      }
    });
    await new Promise((resolve, reject) => {
      const onError = (e) => reject(e);
      server.once("error", onError);
      server.listen(port, "127.0.0.1", () => {
        server.removeListener("error", onError);
        resolve();
      });
    });
    await research.recover();
    const connection = {
      url: `http://127.0.0.1:${server.address().port}`,
      token,
      project,
    };
    await atomic(path.join(data, "connection.json"), connection);
    ready = true;
    let closing;
    return {
      server,
      codex,
      connection,
      data,
      close() {
        return (closing ||= (async () => {
          try {
            research.finishUnknown(
              "The companion closed before completion was observed.",
            );
            codex.close();
            await new Promise((r) => server.close(r));
            await research.pending.catch(() => {});
            await persist.catch(() => {});
            try {
              const current = JSON.parse(
                await fs.readFile(path.join(data, "connection.json"), "utf8"),
              );
              if (current.token === token)
                await fs.rm(path.join(data, "connection.json"));
            } catch {}
          } finally {
            await releaseLibrary();
          }
        })());
      },
    };
  } catch (e) {
    codex?.close();
    if (server?.listening) await new Promise((r) => server.close(r));
    await releaseLibrary();
    throw e;
  }
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const args = process.argv.slice(2),
    get = (name, fallback) => {
      const i = args.indexOf(name);
      return i < 0 ? fallback : args[i + 1];
    };
  try {
    const app = await start({
      project: get("--project", path.join(HERE, "public")),
      data: get("--data", path.join(HERE, ".local")),
      port: Number(get("--port", "8768")),
    });
    console.log(
      `GAZEAIDE Standalone: ${app.connection.url}\nProject: ${app.connection.project}\nLocal recordings: ${app.data}\nOpen the URL to begin. Camera and Codex connect only when requested.`,
    );
    for (const signal of ["SIGINT", "SIGTERM"])
      process.on(signal, () => app.close().then(() => process.exit()));
  } catch (e) {
    console.error(e.message);
    process.exitCode = 1;
  }
}
