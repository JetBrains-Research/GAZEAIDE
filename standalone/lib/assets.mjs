import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
const root = new URL("../public/vendor/runtime/", import.meta.url);
export async function verifyAssets() {
  const manifest = JSON.parse(
    await readFile(new URL("MANIFEST.json", root), "utf8"),
  );
  for (const asset of manifest.files) {
    if (asset.path.includes("..") || asset.path.startsWith("/"))
      throw new Error("Invalid runtime asset path");
    const bytes = await readFile(new URL(asset.path, root));
    if (
      bytes.length !== asset.bytes ||
      createHash("sha256").update(bytes).digest("hex") !== asset.sha256
    )
      throw new Error("Bundled runtime checksum failed: " + asset.path);
  }
  return manifest;
}
