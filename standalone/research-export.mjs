import { readFile, writeFile } from "node:fs/promises";
import { researchRows } from "./lib/agent-context.mjs";
const args = process.argv.slice(2),
  out = args.indexOf("--out"),
  inputs = out < 0 ? args : args.slice(0, out);
if (
  !inputs.length ||
  (out >= 0 && (out !== args.length - 2 || !args[out + 1]))
) {
  console.error(
    "Usage: node research-export.mjs recording.json examples.jsonl [more.json] --out dataset.jsonl",
  );
  process.exitCode = 1;
} else {
  const rows = [];
  for (const file of inputs) {
    const content = await readFile(file, "utf8");
    if (file.endsWith(".jsonl"))
      for (const line of content.split(/\r?\n/).filter((l) => l.trim()))
        rows.push(...researchRows(JSON.parse(line)));
    else rows.push(...researchRows(JSON.parse(content)));
  }
  const text =
    rows.map((r) => JSON.stringify(r)).join("\n") + (rows.length ? "\n" : "");
  if (out >= 0)
    await writeFile(args[out + 1], text, { flag: "wx", mode: 0o600 });
  else process.stdout.write(text);
}
