import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { generateKeyPairSync, sign } from "node:crypto";
import { digest, signingInput, validateCatalog, makeEnvelope, publicSigningKey, validateTrustedKeys } from "./threat-catalog";
import { privatePem, trusted, makeEnvelope as signed } from "./test-signing";

const payload = () => JSON.parse(readFileSync(new URL("../threat-catalog/reviewed.json", import.meta.url), "utf8"));
const check = (raw: string, policy = trusted) => validateCatalog(raw, undefined, Date.now(), policy);
test("PEM paste whitespace preserves key identity without accepting invalid key material", () => {
  const expected = publicSigningKey(privatePem);
  const pasted = privatePem.replace(/\r?\n/g, " ");
  assert.equal(publicSigningKey(pasted).id, expected.id);
  assert.equal(publicSigningKey(pasted).spkiBase64, expected.spkiBase64);
  assert.throws(() => publicSigningKey(pasted.replace("BEGIN PRIVATE KEY", "BEGIN PUBLIC KEY")), /Invalid THREAT/);
  assert.throws(() => publicSigningKey(pasted.replace(/ (M)/, " !")), /Invalid THREAT/);
  assert.throws(() => publicSigningKey(pasted + pasted), /Invalid THREAT/);
});
test("independent signatures reject repo tampering even after checksum recomputation", () => {
  const e = JSON.parse(signed(payload()));
  const changed = JSON.parse(e.payload); changed.indicators = []; changed.version++;
  e.payload = JSON.stringify(changed); e.sha256 = digest(e.payload);
  assert.throws(() => check(JSON.stringify(e)), /Signature/);
});
test("unsigned legacy, missing signatures, added public keys and algorithm fields cannot create trust", () => {
  const e = JSON.parse(signed(payload()));
  for (const mutation of [
    (x: any) => { x.format = 1; delete x.signature; delete x.keyId; },
    (x: any) => { delete x.signature; },
    (x: any) => { x.publicKey = trusted.keys[0].spkiBase64; },
    (x: any) => { x.algorithm = "none"; },
    (x: any) => { x.keyId = "0".repeat(64); },
    (x: any) => { x.signature = "!".repeat(512); },
  ]) { const changed = { ...e }; mutation(changed); assert.throws(() => check(JSON.stringify(changed))); }
});
test("wrong key, revoked keys, key version bounds and unprovisioned production anchors fail closed", () => {
  const p = payload(), raw = signed(p);
  const other = generateKeyPairSync("rsa", { modulusLength: 3072 }).privateKey;
  const e = JSON.parse(raw);
  e.signature = sign("RSA-SHA256", signingInput(e.keyId, e.sha256, e.payload), other).toString("base64");
  assert.throws(() => check(JSON.stringify(e)), /Signature/);
  assert.throws(() => check(raw, { schemaVersion: 1, keys: [{ ...trusted.keys[0], status: "revoked" }] }), /revoked/);
  assert.throws(() => check(raw, { schemaVersion: 1, keys: [{ ...trusted.keys[0], minimumVersion: p.version + 1 }] }), /policy/);
  assert.throws(() => check(raw, { schemaVersion: 1, keys: [{ ...trusted.keys[0], maximumVersion: p.version - 1 }] }), /policy/);
  assert.throws(() => check(raw, { schemaVersion: 1, keys: [] }), /Unknown/);
  assert.throws(() => validateCatalog(raw), /Unknown/);
  assert.throws(() => publicSigningKey("not a private key"), /Invalid THREAT/);
  assert.throws(() => makeEnvelope(p, privatePem, { schemaVersion: 1, keys: [] }), /Unknown/);
  assert.throws(() => validateTrustedKeys({ schemaVersion: 1, keys: [trusted.keys[0], trusted.keys[0]] }));
});
test("rotation supports only explicitly APK-pinned successor keys", () => {
  const pem = generateKeyPairSync("rsa", { modulusLength: 3072,
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" } }).privateKey;
  const next = publicSigningKey(pem);
  const nextTrust = { schemaVersion: 1 as const, keys: [...trusted.keys, {
    id: next.id, spkiBase64: next.spkiBase64, minimumVersion: 1, maximumVersion: 2147483647, status: "active" as const,
  }] };
  const raw = makeEnvelope(payload(), pem, nextTrust);
  assert.throws(() => check(raw), /Unknown/);
  assert.doesNotThrow(() => check(raw, nextTrust));
  const revoked = { schemaVersion: 1 as const, keys: nextTrust.keys.map(k =>
    k.id === trusted.keys[0].id ? { ...k, status: "revoked" as const } : k) };
  assert.doesNotThrow(() => check(raw, revoked));
  assert.throws(() => check(signed(payload()), revoked), /revoked/);
});
