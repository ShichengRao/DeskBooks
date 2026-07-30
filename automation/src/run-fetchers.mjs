import { appendFile, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  assertNonEmptyFile,
  ensureDir,
  expandHome,
  fileSha256,
  resolveFrom,
} from "./fetcher-api.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const automationRoot = path.resolve(here, "..");
const repoRoot = path.resolve(automationRoot, "..");

export const ENTRY_KINDS = new Set(["statement", "balances"]);

function parseArgs(argv) {
  const args = {
    config: process.env.DESKBOOKS_FETCH_CONFIG || path.join(automationRoot, "config.local.json"),
    source: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--config") {
      args.config = argv[++i];
    } else if (arg.startsWith("--config=")) {
      args.config = arg.slice("--config=".length);
    } else if (arg === "--source") {
      args.source = argv[++i];
    } else if (arg.startsWith("--source=")) {
      args.source = arg.slice("--source=".length);
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  return args;
}

async function loadConfig(configPath) {
  const resolved = resolveFrom(process.cwd(), configPath);
  const raw = await readFile(resolved, "utf8");
  const config = JSON.parse(raw);
  config.__path = resolved;
  config.__dir = path.dirname(resolved);
  return config;
}

function defaultStagingDir() {
  // Match backend/app/app_paths.py: PFA_DATA_DIR wins so the fetch and
  // import halves of the pipeline always agree on one staging tree.
  if (process.env.PFA_DATA_DIR) {
    return path.join(expandHome(process.env.PFA_DATA_DIR), "import-staging");
  }
  if (process.platform === "darwin") {
    return path.join(process.env.HOME, "Library", "Application Support", "DeskBooks", "import-staging");
  }
  return path.join(process.env.HOME, ".local", "share", "deskbooks", "import-staging");
}

function stagingDirFor(config) {
  return resolveFrom(config.__dir, config.stagingDir || defaultStagingDir());
}

function sourceDownloadDir(stagingDir, sourceName) {
  return path.join(stagingDir, "downloads", sourceName);
}

async function appendManifest(stagingDir, entry) {
  const manifestPath = path.join(stagingDir, "manifest.jsonl");
  await appendFile(manifestPath, `${JSON.stringify(entry)}\n`, { mode: 0o600 });
  return manifestPath;
}

async function writeLatestManifest(stagingDir, entries) {
  const manifestPath = path.join(stagingDir, "latest-manifest.jsonl");
  const body = entries.map((entry) => JSON.stringify(entry)).join("\n");
  await writeFile(manifestPath, body ? `${body}\n` : "", { mode: 0o600 });
  return manifestPath;
}

function validateSource(source) {
  if (!source.name || !source.module) {
    throw new Error("each source needs name and module");
  }
  // Browser sources must pin the hosts they may visit. Refusing to run is
  // deliberate: a missing (or typo'd) allowlist must never mean "anywhere".
  if (source.browser !== false) {
    const hosts = source.allowedHosts ?? [];
    const suffixes = source.allowedHostSuffixes ?? [];
    if (!hosts.length && !suffixes.length) {
      throw new Error(
        `${source.name}: browser sources must set allowedHosts or allowedHostSuffixes`,
      );
    }
  }
}

function normalizeEntries(source, result) {
  // Back-compat: `{ files: [...] }` means statement files using the
  // source-level accountId/importerName. Connectors that stage multiple
  // kinds return `{ entries: [{ path, kind, accountId?, importerName? }] }`.
  if (Array.isArray(result?.entries)) {
    return result.entries.map((entry) => ({
      path: entry.path,
      kind: entry.kind ?? "statement",
      accountId: entry.accountId ?? source.accountId ?? null,
      importerName: entry.importerName ?? source.importerName ?? null,
    }));
  }
  const files = Array.isArray(result?.files) ? result.files : [];
  return files.map((file) => ({
    path: file,
    kind: "statement",
    accountId: source.accountId ?? null,
    importerName: source.importerName ?? null,
  }));
}

function validateEntry(source, entry) {
  if (!ENTRY_KINDS.has(entry.kind)) {
    throw new Error(`${source.name}: unknown entry kind: ${entry.kind}`);
  }
  if (entry.kind === "statement") {
    if (!Number.isInteger(entry.accountId)) {
      throw new Error(`${source.name}: statement entries need an integer accountId`);
    }
    if (!entry.importerName) {
      throw new Error(`${source.name}: statement entries need an importerName`);
    }
  }
}

async function runSource(config, source, browserContext) {
  const modulePath = resolveFrom(config.__dir, source.module);
  const fetcher = await import(pathToFileURL(modulePath).href);
  if (typeof fetcher.fetch !== "function") {
    throw new Error(`${source.name}: fetcher module must export async function fetch(context)`);
  }

  const stagingDir = stagingDirFor(config);
  const downloadsDir = sourceDownloadDir(stagingDir, source.name);
  await ensureDir(downloadsDir);

  const page = browserContext ? await browserContext.newPage() : null;
  try {
    const result = await fetcher.fetch({
      source,
      config,
      page,
      downloadsDir,
      automationRoot,
      repoRoot,
    });
    const staged = normalizeEntries(source, result);
    if (staged.length === 0) {
      throw new Error(`${source.name}: fetcher returned no files`);
    }

    const entries = [];
    for (const item of staged) {
      validateEntry(source, item);
      const filePath = path.resolve(expandHome(item.path));
      await assertNonEmptyFile(filePath);
      const sha256 = await fileSha256(filePath);
      const entry = {
        source: source.name,
        kind: item.kind,
        account_id: item.kind === "statement" ? item.accountId : (item.accountId ?? null),
        importer_name: item.kind === "statement" ? item.importerName : (item.importerName ?? null),
        path: filePath,
        sha256,
        downloaded_at: new Date().toISOString(),
      };
      entries.push(entry);
      await appendManifest(stagingDir, entry);
      console.log(`[fetch] ${source.name}: staged ${item.kind} ${filePath}`);
    }
    return entries;
  } finally {
    if (page) {
      await page.close().catch(() => {});
    }
  }
}

export async function runFetchers({ configPath, sourceFilter = null } = {}) {
  const config = await loadConfig(configPath);
  const enabledSources = (config.sources ?? []).filter((source) => {
    if (source.enabled === false) {
      return false;
    }
    return !sourceFilter || source.name === sourceFilter;
  });
  if (enabledSources.length === 0) {
    throw new Error("no enabled fetch sources matched");
  }
  for (const source of enabledSources) {
    validateSource(source);
  }

  const needsBrowser = enabledSources.some((source) => source.browser !== false);
  let browserContext = null;
  if (needsBrowser) {
    const { chromium } = await import("playwright");
    const profileDir = resolveFrom(
      config.__dir,
      config.browserProfileDir || path.join(automationRoot, "browser-profiles", "default"),
    );
    await ensureDir(profileDir);
    browserContext = await chromium.launchPersistentContext(profileDir, {
      acceptDownloads: true,
      headless: config.headless === true,
    });
  }

  const runEntries = [];
  const failures = [];
  try {
    // One failing source must not abort the run: later sources still fetch,
    // and the latest manifest is still written for whatever succeeded.
    for (const source of enabledSources) {
      try {
        const entries = await runSource(config, source, browserContext);
        runEntries.push(...entries);
      } catch (error) {
        failures.push({ source: source.name, error });
        console.error(`[fetch] ${source.name} failed: ${error.message}`);
      }
    }
    const latestManifestPath = await writeLatestManifest(stagingDirFor(config), runEntries);
    console.log(`[fetch] latest manifest: ${latestManifestPath}`);
  } finally {
    if (browserContext) {
      await browserContext.close();
    }
  }
  return { entries: runEntries, failures };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const { entries, failures } = await runFetchers({
    configPath: args.config,
    sourceFilter: args.source,
  });
  if (failures.length > 0) {
    const names = failures.map((f) => f.source).join(", ");
    throw new Error(`${failures.length} source(s) failed (${names}); staged ${entries.length} file(s) from the rest`);
  }
}

const isCli = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
  main().catch((error) => {
    console.error(`[fetch] failed: ${error.message}`);
    process.exit(1);
  });
}
