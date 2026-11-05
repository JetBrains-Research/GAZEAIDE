import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
const here = path.dirname(fileURLToPath(import.meta.url)),
  output = path.join(here, "artifacts"),
  name = "gazeaide-standalone-1.0.0",
  stage = path.join(output, name);
await fs.mkdir(output, { recursive: true });
await fs.rm(stage, { recursive: true, force: true });
await fs.mkdir(stage);
for (const entry of [
  "package.json",
  ".gitignore",
  "README.md",
  "Start GAZEAIDE.command",
  "server.mjs",
  "launch.mjs",
  "mcp.mjs",
  "research-export.mjs",
  "lib",
  "public",
  "docs",
  "test",
  "LICENSE",
  "NOTICE.md",
  "LICENSE-uniteye.txt",
  "LICENSE-EyeMU.txt",
  "PRIVACY.md",
  "LICENSING.md",
  "CHANGELOG.md",
  "THIRD-PARTY-NOTICES.md",
  "build-release.mjs",
])
  await fs.cp(path.join(here, entry), path.join(stage, entry), {
    recursive: true,
  });
const manifest = [];
async function walk(dir) {
  for (const e of (await fs.readdir(dir, { withFileTypes: true })).sort(
    (a, b) => a.name.localeCompare(b.name),
  )) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) await walk(f);
    else {
      const b = await fs.readFile(f);
      manifest.push({
        path: path.relative(stage, f),
        bytes: b.length,
        sha256: createHash("sha256").update(b).digest("hex"),
      });
    }
  }
}
await walk(stage);
await fs.writeFile(
  path.join(stage, "MANIFEST.json"),
  JSON.stringify(
    {
      name,
      version: "1.0.0",
      sourceEdition: "GAZEAIDE Standalone 1.0.0",
      files: manifest,
    },
    null,
    2,
  ),
);
await fs.rm(path.join(output, name + ".zip"), { force: true });
const result = spawnSync("/usr/bin/zip", ["-qr", name + ".zip", name], {
  cwd: output,
  stdio: "inherit",
});
if (result.status !== 0) throw new Error("ZIP packaging failed");
const b = await fs.readFile(path.join(output, name + ".zip"));
await fs.writeFile(
  path.join(output, name + ".zip.sha256"),
  createHash("sha256").update(b).digest("hex") + "  " + name + ".zip\n",
);
console.log(path.join(output, name + ".zip"));
