import { readFile, writeFile } from "node:fs/promises";
const root = new URL("../", import.meta.url),
  version = (await readFile(new URL("VERSION", root), "utf8")).trim();
if (!/^\d+\.\d+\.\d+(?:[-+][\w.-]+)?$/.test(version))
  throw new Error("Invalid VERSION");
const pkgURL = new URL("standalone/package.json", root),
  pkg = JSON.parse(await readFile(pkgURL, "utf8"));
const moduleURL = new URL("standalone/lib/version.mjs", root),
  module = `// Generated from /VERSION by scripts/sync-version.mjs.\nexport const VERSION=${JSON.stringify(version)};\n`;
if (process.argv.includes("--check")) {
  if (pkg.version !== version || (await readFile(moduleURL, "utf8")) !== module)
    throw new Error("Run node scripts/sync-version.mjs");
} else {
  pkg.version = version;
  await writeFile(pkgURL, JSON.stringify(pkg, null, 2) + "\n");
  await writeFile(moduleURL, module);
}
