import { generateKeyPairSync } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

// Operator-only provisioning utility. Never run automatically during builds,
// publication, task merges or app startup. Never print the private material.
const directory = path.join(homedir(), ".maguardian-signing");
mkdirSync(directory, { recursive: true, mode: 0o700 });
const target = path.join(directory, "catalog-rsa3072.pem");
const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 3072,
  privateKeyEncoding: { format: "pem", type: "pkcs8" },
  publicKeyEncoding: { format: "pem", type: "spki" } });
writeFileSync(target, privateKey, { mode: 0o600, flag: "wx" });
console.log(`Chave criada fora do repositório em ${target}. Não envie esse arquivo ao Git.`);
console.log("O operador deve guardar uma cópia segura e inserir seu conteúdo somente no Secret THREAT_CATALOG_SIGNING_KEY.");
