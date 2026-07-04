import { copyFile } from "node:fs/promises";
import path from "node:path";
import { assertNonEmptyFile, resolveFrom, safeFilename } from "../src/fetcher-api.mjs";

export async function fetch({ source, config, downloadsDir }) {
  const inputPath = resolveFrom(config.__dir, source.inputPath);
  const destination = path.join(
    downloadsDir,
    `${new Date().toISOString().slice(0, 10)}-${safeFilename(path.basename(inputPath))}`,
  );
  await copyFile(inputPath, destination);
  await assertNonEmptyFile(destination);
  return { files: [destination] };
}
