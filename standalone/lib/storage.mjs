import { promises as fs } from "node:fs";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
export const hash = (s) => createHash("sha256").update(s).digest("hex");
export async function atomic(file, data) {
  await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  const temp = file + "." + randomUUID() + ".tmp";
  try {
    await fs.writeFile(temp, JSON.stringify(data, null, 2), { mode: 0o600 });
    await fs.rename(temp, file);
  } finally {
    await fs.rm(temp, { force: true });
  }
}
export const validId = (id) =>
  typeof id === "string" && /^[a-zA-Z0-9_-]{1,80}$/.test(id);
const excluded = new Set([
  ".git",
  ".gradle",
  ".intellijPlatform",
  "node_modules",
  "build",
  "dist",
  ".local",
  ".idea",
  ".env",
  ".ssh",
  "__pycache__",
]);
const secret = (name) =>
  excluded.has(name) ||
  /^\.env(?:\.|$)/.test(name) ||
  /\.(?:pem|key|p12|pfx|sqlite|db)$/i.test(name);
export async function sourceFile(root, relative) {
  if (
    typeof relative !== "string" ||
    path.isAbsolute(relative) ||
    relative.split(/[\\/]/).some((s) => s === ".." || secret(s))
  )
    throw new Error("Choose a project source file.");
  const file = await fs.realpath(path.join(root, relative));
  if (!file.startsWith(root + path.sep))
    throw new Error("Source must remain within the project root.");
  const stat = await fs.stat(file);
  if (!stat.isFile() || stat.size > 500000)
    throw new Error("Open text files smaller than 500 KB.");
  const buffer = await fs.readFile(file);
  if (buffer.includes(0)) throw new Error("Binary files cannot be opened.");
  const text = buffer.toString("utf8");
  return {
    path: relative,
    text,
    version: parseInt(hash(buffer).slice(0, 13), 16),
  };
}
export async function tree(root) {
  const files = [];
  let truncated = false;
  async function walk(dir, depth = 0) {
    if (depth > 10) return;
    const entries = await fs.readdir(dir, { withFileTypes: true });
    for (const e of entries) {
      if (secret(e.name) || e.isSymbolicLink()) continue;
      if (files.length >= 2500) {
        truncated = true;
        return;
      }
      const file = path.join(dir, e.name);
      if (e.isDirectory()) await walk(file, depth + 1);
      else if (
        e.isFile() &&
        /\.(?:[cm]?[jt]sx?|java|kt|py|rs|go|c|cpp|h|hpp|cs|swift|rb|php|scala|sh|json|html|css|md|txt|xml|yaml|yml|toml|sql|vue|svelte)$/i.test(
          e.name,
        )
      )
        files.push(path.relative(root, file));
    }
  }
  await walk(root);
  return { files: files.sort(), truncated };
}
