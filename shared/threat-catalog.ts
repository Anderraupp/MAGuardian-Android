import { createHash, createPrivateKey, createPublicKey, sign, verify, type KeyObject } from "node:crypto";
import { readFileSync } from "node:fs";
import { PRODUCTION_TRUSTED_KEYS } from "./catalog-trust";
import { z } from "zod";

export const CATALOG_URL = "https://raw.githubusercontent.com/Anderraupp/MAGuardian-Android/main/threat-catalog/catalog.json";
export const MAX_CATALOG_BYTES = 2 * 1024 * 1024;
const text = (max: number) => z.string().trim().min(1).max(max);
const httpsUrl = z.string().url().max(1000).refine(s => {
  const url = new URL(s);
  return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password;
});
const instant = z.string().datetime().regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$/);
export const indicatorSchema = z.object({
  packageName: z.string().regex(/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/).max(255),
  appName: text(120),
  threatType: z.enum(["popup", "overlay", "notification"]),
  severity: z.enum(["low", "medium", "high"]),
  description: text(1000),
  apkSha256: z.array(z.string().regex(/^[0-9a-f]{64}$/)).min(1).max(100),
  status: z.literal("confirmed"),
  source: z.object({
    name: text(120), url: httpsUrl, license: text(120),
    licenseUrl: httpsUrl, attribution: text(4000),
  }).strict(),
  review: z.object({
    reviewer: text(120), reviewedAt: instant, evidence: text(2000),
  }).strict(),
}).strict();
export const payloadSchema = z.object({
  schemaVersion: z.literal(1),
  version: z.number().int().min(1).max(2147483647),
  publishedAt: instant,
  indicators: z.array(indicatorSchema).max(10000),
}).strict().superRefine((p, ctx) => {
  const names = new Set<string>();
  for (const entry of p.indicators) {
    if (names.has(entry.packageName)) ctx.addIssue({ code: "custom", message: "Duplicate package" });
    names.add(entry.packageName);
  }
});
export const trustedKeysSchema = z.object({
  schemaVersion: z.literal(1),
  keys: z.array(z.object({
    id: z.string().regex(/^[0-9a-f]{64}$/),
    spkiBase64: z.string().min(1).max(2000),
    minimumVersion: z.number().int().min(1).max(2147483647),
    maximumVersion: z.number().int().min(1).max(2147483647),
    status: z.enum(["active", "revoked"]),
  }).strict()).max(10),
}).strict();
export type TrustedKeys = z.infer<typeof trustedKeysSchema>;
export function loadTrustedKeys(file?: string | URL): TrustedKeys {
  return validateTrustedKeys(file ? JSON.parse(readFileSync(file, "utf8")) : PRODUCTION_TRUSTED_KEYS);
}
export function validateTrustedKeys(input: unknown): TrustedKeys {
  const trusted = trustedKeysSchema.parse(input);
  const ids = new Set<string>();
  for (const key of trusted.keys) {
    if (ids.has(key.id) || key.minimumVersion > key.maximumVersion) throw new Error("Invalid trusted key policy");
    ids.add(key.id);
    const der = strictBase64(key.spkiBase64);
    if (digestBytes(der) !== key.id) throw new Error("Public key ID mismatch");
    requireRsa3072(createPublicKey({ key: der, format: "der", type: "spki" }));
  }
  return trusted;
}
function strictBase64(value: string): Buffer {
  const bytes = Buffer.from(value, "base64");
  if (!bytes.length || bytes.toString("base64") !== value) throw new Error("Invalid base64");
  return bytes;
}
function requireRsa3072(key: KeyObject) {
  if (key.asymmetricKeyType !== "rsa" || key.asymmetricKeyDetails?.modulusLength !== 3072)
    throw new Error("Catalog signing requires RSA 3072");
}
export function publicSigningKey(privatePem: string) {
  let privateKey: KeyObject;
  try {
    try { privateKey = createPrivateKey(privatePem); }
    catch {
      // Terminal/Secret paste may replace PEM line breaks with spaces. Rewrap
      // the exact canonical DER bytes, never repair missing/invalid key data.
      const pem = privatePem.trim().match(/^-----BEGIN PRIVATE KEY-----([\s\S]+?)-----END PRIVATE KEY-----$/);
      if (!pem) throw new Error("Invalid PEM");
      const body = pem[1].replace(/\s/g, "");
      strictBase64(body);
      const wrapped = body.match(/.{1,64}/g)!.join("\n");
      privateKey = createPrivateKey(`-----BEGIN PRIVATE KEY-----\n${wrapped}\n-----END PRIVATE KEY-----\n`);
    }
    requireRsa3072(privateKey);
  }
  catch { throw new Error("Invalid THREAT_CATALOG_SIGNING_KEY: expected RSA 3072 PKCS#8 PEM"); }
  const der = createPublicKey(privateKey).export({ format: "der", type: "spki" }) as Buffer;
  return { privateKey, id: digestBytes(der), spkiBase64: der.toString("base64") };
}
export function signingInput(keyId: string, sha256: string, payload: string): Buffer {
  return Buffer.from(`maguardian/threat-catalog/v2\n${keyId}\n${sha256}\n${payload}`, "utf8");
}
const envelopeSchema = z.object({
  format: z.literal(2),
  payload: z.string().min(1).max(MAX_CATALOG_BYTES),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  keyId: z.string().regex(/^[0-9a-f]{64}$/),
  signature: z.string().length(512),
}).strict();
export type ThreatPayload = z.infer<typeof payloadSchema>;
export function makeEnvelope(payload: unknown, privatePem: string, trusted: TrustedKeys = loadTrustedKeys()): string {
  const content = JSON.stringify(payloadSchema.parse(payload));
  const key = publicSigningKey(privatePem);
  const sha256 = digest(content);
  const signature = sign("RSA-SHA256", signingInput(key.id, sha256, content), key.privateKey).toString("base64");
  const raw = JSON.stringify({ format: 2, payload: content, sha256, keyId: key.id, signature });
  if (Buffer.byteLength(raw) > MAX_CATALOG_BYTES) throw new Error("Catalog too large");
  validateCatalog(raw, undefined, Date.now(), trusted);
  return raw;
}
export function digest(content: string): string {
  return digestBytes(Buffer.from(content, "utf8"));
}
function digestBytes(content: Buffer): string {
  return createHash("sha256").update(content).digest("hex");
}
export function validateCatalog(raw: string, previous?: { version: number; sha256: string },
  now = Date.now(), trusted: TrustedKeys = loadTrustedKeys()) {
  if (Buffer.byteLength(raw) > MAX_CATALOG_BYTES) throw new Error("Catalog too large");
  const envelope = envelopeSchema.parse(JSON.parse(raw));
  if (digest(envelope.payload) !== envelope.sha256) throw new Error("Integrity check failed");
  const policy = validateTrustedKeys(trusted).keys.find(k => k.id === envelope.keyId);
  if (!policy || policy.status !== "active") throw new Error("Unknown or revoked signing key");
  const publicKey = createPublicKey({ key: strictBase64(policy.spkiBase64), format: "der", type: "spki" });
  if (!verify("RSA-SHA256", signingInput(envelope.keyId, envelope.sha256, envelope.payload),
    publicKey, strictBase64(envelope.signature))) throw new Error("Signature check failed");
  const payload = payloadSchema.parse(JSON.parse(envelope.payload));
  if (payload.version < policy.minimumVersion || payload.version > policy.maximumVersion)
    throw new Error("Version outside signing key policy");
  if (Date.parse(payload.publishedAt) > now + 86400000) throw new Error("Future publication");
  for (const indicator of payload.indicators) {
    if (Date.parse(indicator.review.reviewedAt) > Date.parse(payload.publishedAt))
      throw new Error("Review after publication");
  }
  if (previous && (payload.version < previous.version ||
    (payload.version === previous.version && envelope.sha256 !== previous.sha256))) {
    throw new Error("Rollback or reused version");
  }
  return { envelope, payload };
}
