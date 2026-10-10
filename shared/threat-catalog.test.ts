import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { digest, MAX_CATALOG_BYTES } from "./threat-catalog";
import { makeEnvelope, validateCatalog } from "./test-signing";

const initial = () => JSON.parse(readFileSync(new URL("../threat-catalog/reviewed.json", import.meta.url), "utf8"));
const reject = (change: (p: any) => void) => {
  const p = initial(); change(p); assert.throws(() => makeEnvelope(p));
};
test("reviewed catalog round trips and contains licensed exact APK indicators", () => {
  const { payload } = validateCatalog(makeEnvelope(initial()));
  assert.equal(payload.indicators.length, 2);
  assert.equal(payload.indicators[0].source.license, "BSD-2-Clause");
  assert.equal(payload.indicators[0].apkSha256.length, 3);
});
test("corrupted payload and checksum are rejected", () => {
  const envelope = JSON.parse(makeEnvelope(initial()));
  envelope.payload += " ";
  assert.throws(() => validateCatalog(JSON.stringify(envelope)));
});
test("versions cannot go backwards or reuse a version for changed data", () => {
  const raw = makeEnvelope(initial());
  const { payload, envelope } = validateCatalog(raw);
  const old = { version: payload.version, sha256: envelope.sha256 };
  assert.doesNotThrow(() => validateCatalog(raw, old));
  const changed = initial(); changed.indicators = [];
  assert.throws(() => validateCatalog(makeEnvelope(changed), old));
  assert.throws(() => validateCatalog(raw, { version: payload.version + 1, sha256: envelope.sha256 }));
  changed.version = payload.version + 1;
  assert.doesNotThrow(() => validateCatalog(makeEnvelope(changed), old));
});
test("candidates, URLs to APKs, unknown fields and malformed metadata are rejected", () => {
  reject(p => p.indicators[0].status = "candidate");
  reject(p => p.indicators[0].apkUrl = "https://example.com/malware.apk");
  reject(p => p.indicators[0].apkSha256 = []);
  reject(p => p.indicators[0].apkSha256 = ["invalid"]);
  reject(p => p.indicators[0].source.license = "");
  reject(p => p.indicators[0].source.url = "http://example.com");
  reject(p => delete p.indicators[0].review);
  reject(p => p.indicators[0].packageName = "com.*");
  reject(p => p.indicators[0].severity = "critical");
  reject(p => p.indicators.push(p.indicators[0]));
  reject(p => p.version = 1.5);
  reject(p => p.version = 2147483648);
  reject(p => p.schemaVersion = 2);
});
test("oversized, future and invalid envelopes fail explicitly", () => {
  assert.throws(() => validateCatalog(" ".repeat(MAX_CATALOG_BYTES + 1)));
  assert.throws(() => validateCatalog("{"));
  const p = initial(); p.publishedAt = "2099-01-01T00:00:00Z";
  assert.throws(() => validateCatalog(makeEnvelope(p)));
  p.publishedAt = "2026-10-10T00:00:00Z";
  p.indicators[0].review.reviewedAt = "2026-10-11T00:00:00Z";
  assert.throws(() => validateCatalog(makeEnvelope(p)));
  const valid = JSON.parse(makeEnvelope(initial()));
  valid.extra = true;
  assert.throws(() => validateCatalog(JSON.stringify(valid)));
  assert.equal(digest("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});
