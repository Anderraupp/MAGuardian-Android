import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import https from "node:https";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Disposable QA signing/TLS keys, never production keys. Everything private
// stays under app/build (ignored), outside the uploaded evidence directory.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const build = path.join(root, "app/build");
const dir = path.join(build, "catalogValidationFixtures");
fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
const keyPath = path.join(dir, "server-key.pem");
const certPath = path.join(dir, "server-cert.pem");
if (!fs.existsSync(keyPath) || !fs.existsSync(certPath)) {
  execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:3072", "-nodes",
    "-keyout", keyPath, "-out", certPath, "-days", "2",
    "-subj", "/CN=Disposable catalog validation",
    "-addext", "subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost"],
  { stdio: "ignore" });
  fs.chmodSync(keyPath, 0o600);
}
const key = crypto.createPrivateKey(fs.readFileSync(keyPath));
const spki = crypto.createPublicKey(key).export({ type: "spki", format: "der" });
const keyId = crypto.createHash("sha256").update(spki).digest("hex");
const rawDir = path.join(build, "generated/catalogValidationResources/raw");
const sourceDir = path.join(build, "generated/catalogValidationKeys/com/maguardian/security/data");
fs.mkdirSync(rawDir, { recursive: true });
fs.mkdirSync(sourceDir, { recursive: true });
fs.copyFileSync(certPath, path.join(rawDir, "catalog_test_ca.pem"));
fs.writeFileSync(path.join(sourceDir, "ValidationCatalogKeys.kt"), `package com.maguardian.security.data

// Generated QA-only policy; this source is not compiled into debug/release.
object ThreatCatalogKeys {
    data class Key(val spkiBase64: String, val minimumVersion: Int,
                   val maximumVersion: Int, val revoked: Boolean)
    val trusted: Map<String, Key> = linkedMapOf(
        "${keyId}" to Key("${spki.toString("base64")}", 1, Int.MAX_VALUE, false)
    )
}
`);
const payload = JSON.stringify({ schemaVersion: 1, version: 1,
  publishedAt: "2026-01-01T00:00:00Z", indicators: [] });
const hash = crypto.createHash("sha256").update(payload).digest("hex");
const signature = crypto.sign("sha256",
  Buffer.from(`maguardian/threat-catalog/v2\n${keyId}\n${hash}\n${payload}`), key);
const catalog = JSON.stringify({ format: 2, payload, sha256: hash,
  keyId, signature: signature.toString("base64") });

if (!process.argv.includes("--prepare")) {
  const maxBytes = 2 * 1024 * 1024;
  const chunkBytes = 8192;
  // Pace the disposable fixture so emulator scheduling and close-time draining
  // cannot turn server-side queued bytes into a false client-limit regression.
  const chunkDelayMs = 25;
  const totalBytes = maxBytes * 8;
  const records = new Map();
  let armed = null;
  const json = (res, status, value) => {
    res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    res.end(JSON.stringify(value));
  };
  const streamOversized = (res, record) => {
    const headers = { "Content-Type": "application/json", "Cache-Control": "no-store",
      "Transfer-Encoding": "chunked", "Connection": "close" };
    // Deliberately contradictory framing from an untrusted server: the actual
    // chunked body is 16 MiB, despite the claimed 1 KiB. Never used in production.
    if (record.mode === "misleading-length") headers["Content-Length"] = record.advertisedBytes;
    res.writeHead(200, headers);
    res.flushHeaders();
    const chunk = Buffer.alloc(chunkBytes, 0x20);
    let timer;
    const send = () => {
      if (res.destroyed) return;
      record.bytesSent += chunkBytes;
      const writable = res.write(chunk);
      if (record.bytesSent === record.totalBytes) {
        record.endCalled = true;
        res.end();
      } else if (writable) {
        timer = setTimeout(send, chunkDelayMs);
      } else {
        res.once("drain", () => { timer = setTimeout(send, chunkDelayMs); });
      }
    };
    res.on("error", () => {}); // A peer abort is the expected outcome.
    res.on("close", () => {
      clearTimeout(timer);
      record.closed = true;
      record.closedEarly = !res.writableFinished && !record.endCalled;
      fs.writeFileSync(path.join(dir, `download-${record.id}.json`), JSON.stringify(record, null, 2));
      console.log(`DOWNLOAD_BOUNDARY ${JSON.stringify(record)}`);
    });
    timer = setTimeout(send, chunkDelayMs);
  };
  const server = https.createServer({ key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) },
    (req, res) => {
      const url = new URL(req.url, "https://localhost");
      if (url.pathname === "/validation/download/arm" && req.method === "POST") {
        const mode = url.searchParams.get("mode");
        if (!["unknown-length", "misleading-length"].includes(mode)) {
          json(res, 400, { error: "Unknown disposable mode" }); return;
        }
        if (armed || [...records.values()].some(r => r.requests > 0 && !r.closed)) {
          json(res, 409, { error: "Download already pending" }); return;
        }
        const id = crypto.randomUUID();
        armed = { id, mode, maxBytes, chunkBytes, chunkDelayMs, totalBytes,
          advertisedBytes: mode === "misleading-length" ? 1024 : null,
          bytesSent: 0, requests: 0, endCalled: false, closed: false, closedEarly: false };
        records.set(id, armed);
        json(res, 200, armed); return;
      }
      if (url.pathname === "/validation/download/stats" && req.method === "GET") {
        const record = records.get(url.searchParams.get("id"));
        json(res, record ? 200 : 404, record ?? { error: "Unknown download" }); return;
      }
      if (url.pathname === "/validation/download/disarm" && req.method === "POST") {
        if (armed?.id === url.searchParams.get("id")) armed = null;
        json(res, 200, { disarmed: true }); return;
      }
      if (req.method !== "GET" || req.url !== "/threat-catalog.json") {
        res.writeHead(404); res.end(); return;
      }
      if (armed) {
        const record = armed;
        armed = null; // Only one updater request; controls never consume the body.
        record.requests++;
        streamOversized(res, record);
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(catalog), "Cache-Control": "no-store" });
      res.end(catalog);
    });
  server.listen(8443, "127.0.0.1", () => console.log("Disposable signed HTTPS catalog ready."));
  process.on("SIGTERM", () => server.close(() => process.exit(0)));
}
