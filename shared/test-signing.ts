// Ephemeral test keys only: never persisted and never accepted by release APKs.
import { generateKeyPairSync } from "node:crypto";
import { publicSigningKey, makeEnvelope as signedEnvelope, validateCatalog as check, type TrustedKeys } from "./threat-catalog";

export const privatePem = generateKeyPairSync("rsa", { modulusLength: 3072,
  privateKeyEncoding: { format: "pem", type: "pkcs8" },
  publicKeyEncoding: { format: "pem", type: "spki" } }).privateKey;
const publicKey = publicSigningKey(privatePem);
export const trusted: TrustedKeys = { schemaVersion: 1, keys: [{
  id: publicKey.id, spkiBase64: publicKey.spkiBase64,
  minimumVersion: 1, maximumVersion: 2147483647, status: "active",
}] };
export const makeEnvelope = (payload: unknown) => signedEnvelope(payload, privatePem, trusted);
export const validateCatalog = (raw: string, previous?: { version: number; sha256: string }, now?: number) =>
  check(raw, previous, now, trusted);
