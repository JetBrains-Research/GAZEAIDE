import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { SYSTEM } from "./core.mjs";
export class CodexClient {
  constructor({
    cwd,
    command = "codex",
    onChange = () => {},
    savedThread = null,
  } = {}) {
    Object.assign(this, { cwd, command, onChange });
    this.pending = new Map();
    this.serial = 0;
    this.events = [];
    this.status = "disconnected";
    this.threadId = savedThread;
    this.turnId = null;
    this.busy = false;
  }
  event(method, params) {
    this.events.push({
      sequence: (this.sequence = (this.sequence || 0) + 1),
      method,
      params,
    });
    this.events = this.events.slice(-1500);
    this.onChange();
  }
  rpc(method, params = {}) {
    return new Promise((resolve, reject) => {
      if (!this.process) return reject(new Error("Connect Codex first"));
      const id = ++this.serial;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Codex timed out: ${method}`));
      }, 30000);
      this.pending.set(id, { resolve, reject, timer });
      this.process.stdin.write(JSON.stringify({ id, method, params }) + "\n");
    });
  }
  async connect() {
    if (this.status === "connected") return this.snapshot();
    if (this.connecting) return this.connecting;
    this.connecting = this.open();
    try {
      return await this.connecting;
    } finally {
      this.connecting = null;
    }
  }
  async open() {
    this.status = "connecting";
    this.onChange();
    this.process = spawn(this.command, ["app-server", "--stdio"], {
      cwd: this.cwd,
      stdio: ["pipe", "pipe", "pipe"],
      shell: false,
    });
    const child = this.process;
    child.on("error", (e) => this.fail(e));
    child.on("exit", () => {
      if (this.process === child) this.fail(new Error("Codex disconnected"));
    });
    child.stdin.on("error", (e) => this.fail(e));
    child.stderr.on("data", () => {}); // never forward auth/config diagnostics to browser logs
    createInterface({ input: child.stdout }).on("line", (line) => {
      try {
        this.receive(JSON.parse(line));
      } catch {
        /* ignore nonprotocol lines */
      }
    });
    try {
      await this.rpc("initialize", {
        clientInfo: {
          name: "gazeaide_standalone",
          title: "GAZEAIDE Standalone",
          version: "1.0.0",
        },
      });
      child.stdin.write(
        JSON.stringify({ method: "initialized", params: {} }) + "\n",
      );
      const a = await this.rpc("account/read", { refreshToken: false });
      this.account = a.account
        ? { type: a.account.type, planType: a.account.planType }
        : null;
      if (this.threadId) {
        try {
          const history = await this.rpc("thread/turns/list", {
            threadId: this.threadId,
            limit: 50,
            sortDirection: "desc",
            itemsView: "full",
          });
          for (const turn of [...history.data].reverse())
            for (const item of turn.items || [])
              if (
                [
                  "userMessage",
                  "agentMessage",
                  "fileChange",
                  "commandExecution",
                ].includes(item.type)
              )
                this.event("item/completed", {
                  threadId: this.threadId,
                  turnId: turn.id,
                  item,
                });
        } catch {
          this.event("gazeaide/historyUnavailable", {
            message:
              "Earlier messages could not be loaded. The saved thread can still be resumed.",
          });
        }
      }
      this.status = "connected";
      this.onChange();
      return this.snapshot();
    } catch (e) {
      this.fail(e);
      child.kill();
      throw e;
    }
  }
  receive(msg) {
    if (msg.id !== undefined && !msg.method) {
      const p = this.pending.get(msg.id);
      if (!p) return;
      clearTimeout(p.timer);
      this.pending.delete(msg.id);
      msg.error
        ? p.reject(new Error(msg.error.message))
        : p.resolve(msg.result);
      return;
    }
    if (msg.id !== undefined && msg.method) {
      // This edition is a source-review assistant. Do not silently grant escalations,
      // file changes, command execution or tools requested by a configured connector.
      let result;
      if (
        msg.method === "item/commandExecution/requestApproval" ||
        msg.method === "item/fileChange/requestApproval"
      )
        result = { decision: "decline" };
      else if (msg.method === "item/permissions/requestApproval")
        result = { permissions: {}, scope: "turn" };
      else if (msg.method === "item/tool/call")
        result = {
          contentItems: [
            {
              type: "inputText",
              text: "Tool execution is disabled in this review companion.",
            },
          ],
          success: false,
        };
      if (result)
        this.process.stdin.write(JSON.stringify({ id: msg.id, result }) + "\n");
      else
        this.process.stdin.write(
          JSON.stringify({
            id: msg.id,
            error: {
              code: -32601,
              message:
                "Interactive request unsupported in GAZEAIDE review mode",
            },
          }) + "\n",
        );
      this.event("gazeaide/requestDenied", { method: msg.method });
      return;
    }
    if (msg.method === "turn/started") {
      this.busy = true;
      this.turnId = msg.params.turn.id;
    }
    if (msg.method === "turn/completed") {
      this.busy = false;
      this.turnId = null;
    }
    if (msg.method) this.event(msg.method, msg.params);
  }
  fail(e) {
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(e);
    }
    this.pending.clear();
    this.status = "disconnected";
    this.busy = false;
    this.turnId = null;
    this.resumed = false;
    this.process = null;
    this.event("gazeaide/error", { message: e.message });
  }
  async send(text) {
    if (this.busy)
      throw new Error("Wait for the current answer or interrupt it.");
    if (!text?.trim() || text.length > 50000)
      throw new Error("Use a question between 1 and 50,000 characters.");
    await this.connect();
    this.busy = true;
    this.onChange();
    try {
      if (!this.threadId) {
        const r = await this.rpc("thread/start", {
          cwd: this.cwd,
          sandbox: "read-only",
          approvalPolicy: "on-request",
          developerInstructions: SYSTEM,
        });
        this.threadId = r.thread.id;
        this.onChange();
      } else if (!this.resumed) {
        await this.rpc("thread/resume", {
          threadId: this.threadId,
          cwd: this.cwd,
          sandbox: "read-only",
          approvalPolicy: "on-request",
          developerInstructions: SYSTEM,
        });
      }
      this.resumed = true;
      const r = await this.rpc("turn/start", {
        threadId: this.threadId,
        input: [{ type: "text", text }],
        approvalPolicy: "on-request",
        sandboxPolicy: { type: "readOnly" },
      });
      // Completion may arrive before the start RPC resolves. Return the ID for
      // research linkage without resurrecting an already completed active turn.
      if (this.busy) this.turnId = r.turn.id;
      this.onChange();
      return { threadId: this.threadId, turnId: r.turn.id };
    } catch (e) {
      this.busy = false;
      this.onChange();
      throw e;
    }
  }
  async interrupt() {
    if (this.turnId)
      return this.rpc("turn/interrupt", {
        threadId: this.threadId,
        turnId: this.turnId,
      });
  }
  async listThreads({ cursor, searchTerm, archived = false } = {}) {
    await this.connect();
    const result = await this.rpc("thread/list", {
      limit: 50,
      cursor,
      searchTerm,
      archived,
      sortKey: "updated_at",
      sortDirection: "desc",
    });
    // Listing a session does not resume it, subscribe, or create a turn.
    return {
      data: result.data.map((t) => ({
        id: t.id,
        name: t.name || t.preview || "Untitled session",
        cwd: t.cwd,
        updatedAt: t.updatedAt,
        status: t.status,
      })),
      nextCursor: result.nextCursor,
    };
  }
  async readThread(threadId, cursor) {
    if (
      typeof threadId !== "string" ||
      !/^[a-zA-Z0-9_-]{1,100}$/.test(threadId)
    )
      throw new Error("Choose a local Codex session.");
    await this.connect();
    const [{ thread }, history] = await Promise.all([
      this.rpc("thread/read", { threadId }),
      this.rpc("thread/turns/list", {
        threadId,
        cursor,
        limit: 20,
        sortDirection: "desc",
        itemsView: "full",
      }),
    ]);
    return {
      thread: {
        id: thread.id,
        name: thread.name || thread.preview || "Codex session",
        cwd: thread.cwd,
        updatedAt: thread.updatedAt,
        status: thread.status,
      },
      turns: history.data,
      nextCursor: history.nextCursor,
    };
  }
  async threadSummary(threadId) {
    if (
      typeof threadId !== "string" ||
      !/^[a-zA-Z0-9_-]{1,100}$/.test(threadId)
    )
      throw new Error("Choose a local Codex session.");
    await this.connect();
    const { thread } = await this.rpc("thread/read", { threadId });
    return {
      id: thread.id,
      updatedAt: thread.updatedAt,
      status: thread.status,
    };
  }
  async newThread() {
    if (this.busy) throw new Error("Interrupt the current turn first");
    this.threadId = null;
    this.resumed = false;
    this.events = [];
    this.onChange();
  }
  snapshot() {
    return {
      status: this.status,
      account: this.account,
      threadId: this.threadId,
      busy: this.busy,
      events: this.events,
    };
  }
  close() {
    const child = this.process;
    if (child) this.fail(new Error("Codex connection closed"));
    child?.kill();
  }
}
