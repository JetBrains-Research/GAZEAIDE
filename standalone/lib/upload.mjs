import { readFile } from "node:fs/promises";
import vm from "node:vm";
const schema = vm.createContext({});
vm.runInContext(
  await readFile(
    new URL("../docs/google-drive-receiver.js", import.meta.url),
    "utf8",
  ),
  schema,
  { timeout: 1000 },
);
export function validateReduced(payload) {
  try {
    schema.validatePayload_(payload);
  } catch {
    throw new Error(
      "Reduced upload failed the receiver schema; no data was sent.",
    );
  }
}
export function endpointUrl(value) {
  let endpoint;
  try {
    endpoint = new URL(value);
  } catch {
    throw new Error("Enter a valid HTTPS receiver URL.");
  }
  if (
    endpoint.protocol !== "https:" ||
    endpoint.username ||
    endpoint.password ||
    endpoint.hash ||
    /^(localhost$|127\.|0\.|169\.254\.|10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|\[)/.test(
      endpoint.hostname,
    )
  )
    throw new Error("Use a research receiver HTTPS endpoint.");
  if (
    endpoint.hostname === "script.google.com" &&
    !/^\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(endpoint.pathname)
  )
    throw new Error("Use the deployed Apps Script /exec endpoint.");
  return endpoint;
}
export async function upload(
  { endpoint: address, token, payload },
  exchange = fetch,
) {
  const endpoint = endpointUrl(address);
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token || ""))
    throw new Error("Set a participant upload token.");
  validateReduced(payload);
  const raw = JSON.stringify(payload);
  if (Buffer.byteLength(raw) > 8 * 1024 * 1024)
    throw new Error("Reduced upload exceeds 8 MiB");
  const drive = endpoint.hostname === "script.google.com",
    headers = {
      "Content-Type": "application/json",
      Accept: "application/json",
    };
  if (!drive) headers.Authorization = `Bearer ${token}`;
  let response;
  try {
    response = await exchange(endpoint, {
      method: "POST",
      headers,
      body: drive ? JSON.stringify({ token, payload }) : raw,
      redirect: "manual",
      signal: AbortSignal.timeout(20000),
    });
    if (drive && [302, 303].includes(response.status)) {
      const target = new URL(response.headers.get("location"));
      if (
        target.protocol !== "https:" ||
        target.hostname !== "script.googleusercontent.com" ||
        target.pathname !== "/macros/echo" ||
        target.username ||
        target.password ||
        target.hash ||
        target.port
      )
        throw new Error("Unexpected Google acknowledgement redirect.");
      response = await exchange(target, {
        method: "GET",
        headers: { Accept: "application/json" },
        redirect: "error",
        signal: AbortSignal.timeout(20000),
      });
    }
    if (!response.ok) throw new Error("HTTP " + response.status);
    let bytes = 0,
      chunks = [];
    for await (const chunk of response.body) {
      bytes += chunk.length;
      if (bytes > 16384) {
        await response.body.cancel?.().catch(() => {});
        throw new Error("Acknowledgement too large");
      }
      chunks.push(chunk);
    }
    const receipt = JSON.parse(Buffer.concat(chunks).toString());
    if (receipt.ok !== true || receipt.uploadId !== payload.uploadId)
      throw new Error("Invalid acknowledgement");
    return { ok: true, uploadId: payload.uploadId };
  } catch {
    throw new Error(
      "Receiver did not acknowledge this upload. Check the endpoint, token and network; local data is retained.",
    );
  }
}
