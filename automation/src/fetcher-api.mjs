import { createHash } from "node:crypto";
import { mkdir, stat } from "node:fs/promises";
import path from "node:path";

export function expandHome(rawPath) {
  if (!rawPath || typeof rawPath !== "string") {
    throw new Error("path must be a non-empty string");
  }
  if (rawPath === "~") {
    return process.env.HOME;
  }
  if (rawPath.startsWith("~/")) {
    return path.join(process.env.HOME, rawPath.slice(2));
  }
  return rawPath;
}

export function resolveFrom(baseDir, rawPath) {
  const expanded = expandHome(rawPath);
  return path.resolve(path.isAbsolute(expanded) ? expanded : path.join(baseDir, expanded));
}

export async function ensureDir(dir) {
  await mkdir(dir, { recursive: true, mode: 0o700 });
}

export async function fileSha256(filePath) {
  const { createReadStream } = await import("node:fs");
  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    createReadStream(filePath)
      .on("data", (chunk) => hash.update(chunk))
      .on("error", reject)
      .on("end", resolve);
  });
  return hash.digest("hex");
}

export async function assertNonEmptyFile(filePath) {
  const info = await stat(filePath);
  if (!info.isFile() || info.size <= 0) {
    throw new Error(`downloaded file is empty: ${filePath}`);
  }
}

export function safeFilename(name) {
  const cleaned = String(name).replace(/[^a-zA-Z0-9._-]+/g, "_").replace(/^_+|_+$/g, "");
  return cleaned || "download.csv";
}
