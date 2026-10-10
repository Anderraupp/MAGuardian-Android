import { createHash } from "node:crypto";
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
const envelopeSchema = z.object({
  format: z.literal(1),
  payload: z.string().min(1).max(MAX_CATALOG_BYTES),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
}).strict();
export type ThreatPayload = z.infer<typeof payloadSchema>;
export function makeEnvelope(payload: unknown): string {
  const content = JSON.stringify(payloadSchema.parse(payload));
  const raw = JSON.stringify({ format: 1, payload: content, sha256: digest(content) });
  if (Buffer.byteLength(raw) > MAX_CATALOG_BYTES) throw new Error("Catalog too large");
  return raw;
}
export function digest(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex");
}
export function validateCatalog(raw: string, previous?: { version: number; sha256: string }, now = Date.now()) {
  if (Buffer.byteLength(raw) > MAX_CATALOG_BYTES) throw new Error("Catalog too large");
  const envelope = envelopeSchema.parse(JSON.parse(raw));
  if (digest(envelope.payload) !== envelope.sha256) throw new Error("Integrity check failed");
  const payload = payloadSchema.parse(JSON.parse(envelope.payload));
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
