// Codex items are data. Preserve their identity and distinguish generated text,
// recorded diffs and command output from files currently on disk.
export function reviewItems(events = []) {
  const items = new Map();
  for (const e of events) {
    const p = e.params || {};
    if (e.method === "item/agentMessage/delta") {
      const previous = items.get(p.itemId) || {
        id: p.itemId,
        type: "agentMessage",
        text: "",
        turnId: p.turnId,
      };
      items.set(p.itemId, { ...previous, text: previous.text + p.delta });
    } else if (
      ["item/started", "item/completed"].includes(e.method) &&
      p.item
    ) {
      const previous = items.get(p.item.id);
      items.set(p.item.id, {
        ...previous,
        ...p.item,
        turnId: p.turnId || previous?.turnId,
      });
    }
  }
  return [...items.values()];
}

export function reviewDocument(
  threadId,
  items,
  title = "Codex conversation",
  { includeTools = false } = {},
) {
  const rows = [],
    blocks = [];
  const add = (text, meta) => {
    const limit = meta.kind === "toolOutput" ? 6000 : 40000;
    text = String(text);
    if (text.length > limit)
      text =
        text.slice(0, limit) +
        "\n[Display shortened; full content remains in the original Codex session.]";
    const firstLine = rows.length + 1;
    const content = String(text).replace(/\r\n?/g, "\n").split("\n");
    rows.push(...content);
    blocks.push({
      ...meta,
      firstLine,
      lastLine: rows.length,
      text: content.join("\n"),
    });
  };
  for (const item of items) {
    if (
      !includeTools &&
      (item.type === "commandExecution" ||
        (item.type === "agentMessage" && item.phase === "commentary"))
    )
      continue;
    const provenance = {
      threadId,
      turnId: item.turnId,
      itemId: item.id,
      itemType: item.type,
    };
    const meta = {
      provenance,
      name: item.type,
      kind: "prose",
      phase: item.phase,
    };
    if (item.type === "userMessage" || item.type === "agentMessage") {
      const role = item.type === "userMessage" ? "You" : "Codex";
      const text =
        item.type === "agentMessage"
          ? item.text
          : item.content
              ?.map(
                (c) =>
                  c.text ||
                  (c.type === "image" || c.type === "localImage"
                    ? "[Image attachment — view in Codex]"
                    : ""),
              )
              .join("\n");
      if (!text?.trim()) continue;
      add(
        `${role.toUpperCase()} · ${role === "You" ? "REQUEST" : item.phase === "commentary" ? "PROGRESS" : "ANSWER"}`,
        { ...meta, kind: "heading", role },
      );
      let code = false,
        fence = "";
      const lines = text.split("\n");
      let buffer = [];
      const flush = () => {
        if (buffer.length) {
          add(buffer.join("\n"), {
            ...meta,
            role,
            kind: code ? "generatedCode" : "prose",
            language: fence,
          });
          buffer = [];
        }
      };
      for (const line of lines) {
        if (/^\s*(```+|~~~+)/.test(line)) {
          flush();
          add(line, { ...meta, role, kind: "fence" });
          code = !code;
          fence = code
            ? line
                .trim()
                .replace(/^(```+|~~~+)/, "")
                .trim()
            : "";
        } else {
          buffer.push(line);
          if (!line.trim() || buffer.length >= 12) flush();
        }
      }
      flush();
      add("", meta);
    } else if (item.type === "fileChange") {
      for (const change of item.changes || []) {
        const detail = {
          ...meta,
          kind: "recordedDiff",
          path: change.path,
          status: item.status,
        };
        add(`RECORDED DIFF · ${change.path} · ${item.status || "recorded"}`, {
          ...detail,
          kind: "heading",
        });
        add(change.diff || "[No diff recorded]", detail);
        add("", meta);
      }
    } else if (item.type === "commandExecution") {
      add(
        `COMMAND · ${item.command || ""} · ${item.status || "recorded"} · exit ${item.exitCode ?? "unknown"}`,
        { ...meta, kind: "heading" },
      );
      add(item.aggregatedOutput || "[No command output recorded]", {
        ...meta,
        kind: "toolOutput",
      });
      add("", meta);
    }
  }
  if (!rows.length)
    add(
      "Connect Codex and send a question, or open a local Codex session to review its conversation here.",
      { name: "empty", kind: "prose" },
    );
  return {
    path: `codex-session/${threadId || "new"}`,
    title,
    text: rows.join("\n"),
    contentKind: "codexSession",
    blocks,
    imported: true,
  };
}

export function documentRegion(file, line) {
  const block = file.blocks?.find(
    (b) => b.firstLine <= line + 1 && b.lastLine >= line + 1,
  );
  if (!block) return null;
  return {
    ...block,
    label:
      block.kind === "heading"
        ? block.text
        : `${block.role || "Codex"} ${{ prose: "passage", generatedCode: "generated code", recordedDiff: "recorded diff", toolOutput: "command output", fence: "code fence" }[block.kind] || block.kind}${block.path ? " · " + block.path : ""}`,
    contentKind: file.contentKind,
  };
}
