import { spawn } from "node:child_process";
import { start } from "./server.mjs";
const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i < 0 ? undefined : args[i + 1];
};
try {
  const app = await start({
    project: flag("--project"),
    data: flag("--data"),
    port: Number(flag("--port") || 8768),
  });
  console.log(
    `GAZEAIDE Standalone: ${app.browserUrl}\nProject: ${app.connection.project}\nKeep this terminal open. Ctrl+C stops the companion.`,
  );
  if (process.platform === "darwin") {
    const browser = spawn("/usr/bin/open", [app.browserUrl], {
      stdio: "ignore",
    });
    browser.on("error", () =>
      console.log("Open the URL above in your browser."),
    );
  }
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => app.close().then(() => process.exit()));
} catch (error) {
  console.error(error.message);
  console.error(
    "If GAZEAIDE is already running, open its existing URL or choose a different --port and --data folder.",
  );
  process.exitCode = 1;
}
