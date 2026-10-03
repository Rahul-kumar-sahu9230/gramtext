// Same backend calls as the Android app (android/.../data/ApiClient.kt).
//
// Server address: empty = same origin (the Vite dev server forwards /api to the backend, see
// vite.config.js). It can be changed in Settings, like the APK; VITE_API_BASE_URL sets the default.

const SERVER_KEY = "gramtext.serverUrl";
const CLIENT_KEY = "gramtext.clientId";

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch {
    return null; // private mode / blocked storage: fall back to defaults
  }
  return value;
}

export function getServerUrl() {
  return (store(SERVER_KEY) ?? import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
}

export function setServerUrl(url) {
  store(SERVER_KEY, url.trim().replace(/\/$/, ""));
}

/** Random anonymous id, used only to count unique devices in /api/metrics. */
function clientId() {
  let id = store(CLIENT_KEY);
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    store(CLIENT_KEY, id);
  }
  return id;
}

export class ApiError extends Error {
  /** kind: "network" | "timeout" | "server" (server messages are already friendly). */
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

async function request(path, options = {}, timeoutMs = 90_000, base = getServerUrl()) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  options.signal?.addEventListener("abort", () => controller.abort());
  try {
    return await fetch(`${base}${path}`, {
      ...options,
      headers: { "X-Client-Id": clientId(), ...(options.headers || {}) },
      signal: controller.signal,
    });
  } catch (err) {
    throw new ApiError(err.name === "AbortError" ? "timeout" : "network", err.message);
  } finally {
    clearTimeout(timer);
  }
}

async function failure(response) {
  try {
    const data = await response.json();
    if (typeof data.detail === "string" && data.detail) return new ApiError("server", data.detail);
  } catch {
    // body was not JSON
  }
  return new ApiError("server", `Server error (${response.status})`);
}

export async function checkHealth(base = getServerUrl()) {
  try {
    const response = await request("/api/health", {}, 8_000, base.replace(/\/$/, ""));
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * OCR + speech in one streamed request. Calls onEvent for every server event
 * ({type: "text" | "audio" | "error" | "done", ...}) as soon as it arrives.
 */
export async function readAloud(blob, onEvent, signal) {
  const form = new FormData();
  form.append("file", blob, "photo.jpg");
  const response = await request("/api/read", { method: "POST", body: form, signal });
  if (!response.ok) throw await failure(response);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line) onEvent(JSON.parse(line));
      }
    }
  } catch (err) {
    if (err instanceof SyntaxError) throw err;
    throw new ApiError("network", err.message);
  }
  if (buffer.trim()) onEvent(JSON.parse(buffer));
}

export async function generateSpeech(text) {
  const response = await request("/api/tts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (!response.ok) throw await failure(response);
  return response.blob();
}

export async function sendFeedback(scanId, helpful, correctedText) {
  const body = { helpful };
  if (scanId != null) body.scan_id = scanId;
  if (correctedText) body.corrected_text = correctedText;
  const response = await request("/api/feedback", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await failure(response);
}
