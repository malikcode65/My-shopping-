// Jarvis proxy: keeps your Claude API key on your own server instead of the phone.
// The phone sends `Authorization: Bearer <JARVIS_TOKEN>`; the proxy checks it, applies a
// per-token rate limit and a model allow-list, then forwards to api.anthropic.com and
// streams the response back unchanged.
//
// Run:  ANTHROPIC_API_KEY=sk-ant-... JARVIS_TOKENS=long-random-token node server.js
// Node 18+ (uses the built-in fetch). No dependencies.

const http = require("node:http");
const { Readable } = require("node:stream");
const crypto = require("node:crypto");

const PORT = Number(process.env.PORT || 8787);
const API_KEY = process.env.ANTHROPIC_API_KEY;
const TOKENS = (process.env.JARVIS_TOKENS || "").split(",").map((t) => t.trim()).filter(Boolean);
const RPM = Number(process.env.JARVIS_RPM || 30);
const ALLOWED_MODELS = (process.env.JARVIS_MODELS || "claude-sonnet-5-5,claude-opus-5-5,claude-haiku-5-5").split(",");
const MAX_BODY = 20 * 1024 * 1024; // screenshots are large

if (!API_KEY || TOKENS.length === 0) {
  console.error("Set ANTHROPIC_API_KEY and JARVIS_TOKENS");
  process.exit(1);
}

const hits = new Map(); // token -> timestamps

function tokenOk(header) {
  const m = /^Bearer (.+)$/.exec(header || "");
  if (!m) return null;
  const given = Buffer.from(m[1]);
  for (const t of TOKENS) {
    const want = Buffer.from(t);
    if (given.length === want.length && crypto.timingSafeEqual(given, want)) return t;
  }
  return null;
}

function rateLimited(token) {
  const now = Date.now();
  const list = (hits.get(token) || []).filter((t) => now - t < 60_000);
  if (list.length >= RPM) return true;
  list.push(now);
  hits.set(token, list);
  return false;
}

function send(res, status, type, message) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify({ type: "error", error: { type, message } }));
}

http
  .createServer(async (req, res) => {
    if (req.method !== "POST" || req.url !== "/v1/messages") return send(res, 404, "not_found_error", "Only POST /v1/messages");
    const token = tokenOk(req.headers.authorization);
    if (!token) return send(res, 401, "authentication_error", "Bad proxy token");
    if (rateLimited(token)) return send(res, 429, "rate_limit_error", "Proxy rate limit");

    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > MAX_BODY) return send(res, 413, "request_too_large", "Body too large");
      chunks.push(c);
    }
    let body;
    try {
      body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      return send(res, 400, "invalid_request_error", "Invalid JSON");
    }
    if (!ALLOWED_MODELS.includes(body.model)) return send(res, 400, "invalid_request_error", "Model not allowed by proxy");

    const upstream = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": API_KEY,
        "anthropic-version": req.headers["anthropic-version"] || "2026-10-21",
        ...(req.headers["anthropic-beta"] ? { "anthropic-beta": req.headers["anthropic-beta"] } : {}),
      },
      body: JSON.stringify(body),
    }).catch((e) => ({ error: e }));
    if (upstream.error) return send(res, 502, "api_error", "Upstream unreachable");

    const headers = { "content-type": upstream.headers.get("content-type") || "application/json" };
    const retry = upstream.headers.get("retry-after");
    if (retry) headers["retry-after"] = retry;
    res.writeHead(upstream.status, headers);
    if (!upstream.body) return res.end();
    const stream = Readable.fromWeb(upstream.body);
    req.on("close", () => stream.destroy()); // phone cancelled (kill switch) -> stop upstream
    stream.pipe(res);
  })
  .listen(PORT, () => console.log(`Jarvis proxy on :${PORT}`));
