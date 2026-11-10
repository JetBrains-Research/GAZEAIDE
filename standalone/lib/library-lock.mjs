import { promises as fs } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    // Permission errors are not evidence that the owner has exited.
    return e.code !== "ESRCH";
  }
};

// Exclusive ownership precedes recovery or other library writes. The short
// recovery guard prevents simultaneous restarts from removing each other's lock.
export async function acquireLibrary(data) {
  const file = path.join(data, ".library-lock"),
    guard = path.join(data, ".library-recovery"),
    owner = { pid: process.pid, token: randomUUID() };
  const read = async () => {
    const value = JSON.parse(await fs.readFile(file, "utf8"));
    if (
      !Number.isSafeInteger(value.pid) ||
      value.pid < 1 ||
      typeof value.token !== "string"
    )
      throw new Error("Invalid library owner");
    return value;
  };
  const stale = async (file) => {
    try {
      return Date.now() - (await fs.stat(file)).mtimeMs > 10000;
    } catch (e) {
      if (e.code === "ENOENT") return false;
      throw e;
    }
  };
  const recoverGuard = async () => {
    try {
      await fs.mkdir(guard, { mode: 0o700 });
      await fs.writeFile(
        path.join(guard, "owner.json"),
        JSON.stringify(owner),
        { mode: 0o600 },
      );
      return;
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
    }
    let previous;
    try {
      previous = JSON.parse(
        await fs.readFile(path.join(guard, "owner.json"), "utf8"),
      );
    } catch {}
    if (
      previous && Number.isSafeInteger(previous.pid) && previous.pid > 0
        ? alive(previous.pid)
        : !(await stale(guard))
    )
      throw new Error(
        "Library recovery is already in progress. Retry after it finishes.",
      );
    const retired = guard + "-" + randomUUID();
    try {
      await fs.rename(guard, retired);
    } catch (e) {
      if (e.code !== "ENOENT") throw e;
    }
    await fs.rm(retired, { recursive: true, force: true });
    await fs.mkdir(guard, { mode: 0o700 });
    await fs.writeFile(path.join(guard, "owner.json"), JSON.stringify(owner), {
      mode: 0o600,
    });
  };
  const release = async () => {
    if ((await read()).token === owner.token) await fs.unlink(file);
  };
  const claim = async () => {
    let handle;
    try {
      handle = await fs.open(file, "wx", 0o600);
    } catch (e) {
      if (e.code === "EEXIST") return false;
      throw e;
    }
    try {
      await handle.writeFile(JSON.stringify(owner));
    } catch (e) {
      await fs.unlink(file);
      throw e;
    } finally {
      await handle.close();
    }
    return true;
  };
  while (!(await claim())) {
    let previous;
    try {
      previous = await read();
    } catch (e) {
      if (e.code === "ENOENT") continue;
      if (await stale(file)) {
        await recoverGuard();
        try {
          try {
            await read();
          } catch (readError) {
            if (readError.code !== "ENOENT" && (await stale(file)))
              await fs.unlink(file);
          }
        } finally {
          await fs.rm(guard, { recursive: true, force: true });
        }
        continue;
      }
      throw new Error(
        `The data folder is being opened or has an incomplete lock: ${file}. Close other copies before retrying; remove this file only after confirming none is running.`,
      );
    }
    if (alive(previous.pid))
      throw new Error(
        "This data folder is already open in GAZEAIDE. Use its existing window, or choose a separate --data folder.",
      );
    await recoverGuard();
    try {
      let current;
      try {
        current = await read();
      } catch (e) {
        if (e.code === "ENOENT") continue;
        throw e;
      }
      if (current.token !== previous.token || alive(current.pid)) continue;
      await fs.unlink(file);
      if (await claim()) return release;
    } finally {
      await fs.rm(guard, { recursive: true, force: true });
    }
  }
  return release;
}
