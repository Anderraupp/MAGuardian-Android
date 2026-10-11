import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import https from "node:https";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";

// Server contract only. Android evidence must come from the instrumented tests.
test("disposable HTTPS oversized fixtures report peer abort and stop writes", { timeout: 20000 }, async () => {
  const server = spawn(process.execPath,
    [fileURLToPath(new URL("./catalog-validation-server.mjs", import.meta.url))],
    { stdio: ["ignore", "pipe", "pipe"] });
  let output = "";
  server.stdout.on("data", data => { output += data; });
  server.stderr.on("data", data => { output += data; });
  try {
    const deadline = Date.now() + 10000;
    while (!output.includes("HTTPS catalog ready")) {
      assert.equal(server.exitCode, null, output);
      assert.ok(Date.now() < deadline, `Server startup timed out: ${output}`);
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    const ca = fs.readFileSync(new URL("../app/build/catalogValidationFixtures/server-cert.pem", import.meta.url));
    const request = (route, method = "GET") => new Promise((resolve, reject) => {
      const req = https.request(`https://localhost:8443${route}`, { method, ca }, res => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", data => { body += data; });
        res.on("end", () => resolve({ status: res.statusCode, body }));
        res.on("error", reject);
      });
      req.on("error", reject);
      req.end();
    });
    const control = async (route, method = "GET") => {
      const response = await request(`/validation/download/${route}`, method);
      assert.equal(response.status, 200, response.body);
      return JSON.parse(response.body);
    };
    assert.equal((await request("/validation/download/arm?mode=invalid", "POST")).status, 400);
    for (const mode of ["unknown-length", "misleading-length"]) {
      const armed = await control(`arm?mode=${mode}`, "POST");
      assert.equal(armed.totalBytes, 16 * 1024 * 1024);
      assert.equal(armed.advertisedBytes, mode === "unknown-length" ? null : 1024);
      assert.equal((await request(`/validation/download/arm?mode=${mode}`, "POST")).status, 409);
      let consumed = 0;
      await new Promise((resolve, reject) => {
        // Node normally rejects contradictory framing before exposing the body.
        // This flag is ONLY for this malicious-response server contract test;
        // Android's default TLS/HTTP implementation is never replaced.
        const req = https.get("https://localhost:8443/threat-catalog.json",
          { ca, insecureHTTPParser: mode === "misleading-length" }, res => {
            assert.equal(res.headers["transfer-encoding"], "chunked");
            assert.equal(res.headers["content-length"], mode === "unknown-length" ? undefined : "1024");
            res.on("data", data => {
              consumed += data.length;
              if (consumed > armed.maxBytes) {
                req.destroy();
                resolve();
              }
            });
            res.on("end", () => reject(new Error("Fixture unexpectedly ended before abort")));
          });
        req.on("error", reject);
      });
      let stats = await control(`stats?id=${armed.id}`);
      const closedDeadline = Date.now() + 3000;
      while (!stats.closed && Date.now() < closedDeadline) {
        await new Promise(resolve => setTimeout(resolve, 25));
        stats = await control(`stats?id=${armed.id}`);
      }
      assert.equal(stats.closed, true);
      assert.equal(stats.closedEarly, true);
      assert.equal(stats.endCalled, false);
      assert.equal(stats.requests, 1);
      assert.ok(consumed > armed.maxBytes && consumed <= armed.maxBytes + armed.chunkBytes);
      assert.ok(stats.bytesSent >= consumed && stats.bytesSent < stats.totalBytes);
      await new Promise(resolve => setTimeout(resolve, 150));
      assert.equal((await control(`stats?id=${armed.id}`)).bytesSent, stats.bytesSent);
      const disk = JSON.parse(fs.readFileSync(new URL(
        `../app/build/catalogValidationFixtures/download-${armed.id}.json`, import.meta.url)));
      assert.deepEqual(disk, stats);
      // The armed oversized response is one-shot: ordinary signed QA still works.
      const normal = await request("/threat-catalog.json");
      assert.equal(normal.status, 200);
      assert.equal(JSON.parse(normal.body).format, 2);
    }
    const armed = await control("arm?mode=unknown-length", "POST");
    await control(`disarm?id=${armed.id}`, "POST");
    assert.equal(JSON.parse((await request("/threat-catalog.json")).body).format, 2);
  } finally {
    const stopped = once(server, "exit");
    server.kill("SIGTERM");
    await stopped;
  }
});
