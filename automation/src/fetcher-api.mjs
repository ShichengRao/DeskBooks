import { createHash } from "node:crypto";
import { mkdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";

const FORBIDDEN_ACTION_RE =
  /\b(pay|payment|transfer|wire|bill pay|zelle|send money|ach|trade|buy|sell|redeem|withdraw|deposit check|external account|profile|settings|password|security|open account|close account)\b/i;

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

export function assertSafeAction(label) {
  const text = String(label ?? "");
  if (FORBIDDEN_ACTION_RE.test(text)) {
    throw new Error(`refusing forbidden financial-site action: ${text}`);
  }
}

export function assertAllowedUrl(urlLike, source) {
  const allowedHosts = source.allowedHosts ?? [];
  const allowedHostSuffixes = source.allowedHostSuffixes ?? [];
  if (!allowedHosts.length && !allowedHostSuffixes.length) {
    // Fail closed: an absent (or typo'd) allowlist must never mean "anywhere".
    throw new Error(
      `no allowedHosts/allowedHostSuffixes configured for ${source.name ?? "source"}; refusing ${urlLike}`,
    );
  }
  const url = new URL(urlLike);
  const host = url.hostname.toLowerCase();
  const exactAllowed = allowedHosts.map((h) => String(h).toLowerCase()).includes(host);
  const suffixAllowed = allowedHostSuffixes
    .map((h) => String(h).toLowerCase().replace(/^\./, ""))
    .some((suffix) => host === suffix || host.endsWith(`.${suffix}`));
  if (!exactAllowed && !suffixAllowed) {
    throw new Error(`refusing URL outside allowedHosts: ${url.hostname}`);
  }
}

export async function gotoAllowed(page, url, source, options = {}) {
  assertAllowedUrl(url, source);
  await page.goto(url, { waitUntil: "domcontentloaded", ...options });
  assertAllowedUrl(page.url(), source);
}

async function exactlyOne(locator, label) {
  const count = await locator.count();
  if (count !== 1) {
    throw new Error(`expected exactly one ${label}, found ${count}`);
  }
  return locator.first();
}

export async function clickByRole(page, role, name, source, options = {}) {
  assertSafeAction(name);
  const locator = await exactlyOne(page.getByRole(role, { name }), `${role} named ${name}`);
  await locator.click(options);
  assertAllowedUrl(page.url(), source);
}

export async function downloadByRole(page, role, name, source, destinationDir, filenameHint) {
  assertSafeAction(name);
  await ensureDir(destinationDir);
  const locator = await exactlyOne(page.getByRole(role, { name }), `${role} named ${name}`);
  const [download] = await Promise.all([page.waitForEvent("download"), locator.click()]);
  assertAllowedUrl(page.url(), source);
  const suggested = download.suggestedFilename() || filenameHint || "download.csv";
  const destination = path.join(destinationDir, safeFilename(suggested));
  await download.saveAs(destination);
  await assertNonEmptyFile(destination);
  return destination;
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

export async function savePageDiagnostics(page, destinationDir, label, { enabled = false } = {}) {
  // Opt-in only: a full screenshot + HTML dump of a logged-in bank page is
  // sensitive material and should never be captured by default.
  if (!enabled) {
    return null;
  }
  await ensureDir(destinationDir);
  const prefix = `${new Date().toISOString().replace(/[:.]/g, "-")}-${safeFilename(label)}`;
  const screenshotPath = path.join(destinationDir, `${prefix}.png`);
  const htmlPath = path.join(destinationDir, `${prefix}.html`);
  await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
  await writeFile(htmlPath, await page.content(), { encoding: "utf8", mode: 0o600 }).catch(() => {});
  const { chmod } = await import("node:fs/promises");
  await chmod(screenshotPath, 0o600).catch(() => {});
  return { screenshotPath, htmlPath };
}
