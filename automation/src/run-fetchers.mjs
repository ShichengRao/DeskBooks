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

// `--only` splits one config across two runs by entry kind. A net-worth
// mark wants balances stamped on the day it belongs to, while the rows for
// that same month are better fetched a few days later, once the card
// transactions that post 1-3 days late have arrived. The flag speaks in the
// words a config uses ("transactions") rather than the manifest's kind
// ("statement"), so the two stay translatable in one place.
export const ONLY_KINDS = new Map([
  ["balances", "balances"],
  ["transactions", "statement"],
]);

function parseArgs(argv) {
  const args = {
    config: process.env.DESKBOOKS_FETCH_CONFIG || path.join(automationRoot, "config.local.json"),
    source: null,
    only: null,
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
    } else if (arg === "--only") {
      args.only = argv[++i];
    } else if (arg.startsWith("--only=")) {
      args.only = arg.slice("--only=".length);
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
  // Connectors are API/file based; the browser-automation lane was removed.
  if (source.browser === true) {
    throw new Error(`${source.name}: browser connectors are not supported`);
  }
}

function profileFor(config, source) {
  // Account ids in mappings belong to exactly one profile's database, so a
  // config (or a single source) can pin the profile its staged files are for.
  const profile = source.profile ?? config.profile ?? null;
  if (profile != null && (typeof profile !== "string" || !profile.trim())) {
    throw new Error(`${source.name}: profile must be a non-empty string`);
  }
  return profile;
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

async function runSource(config, source, only) {
  const modulePath = resolveFrom(config.__dir, source.module);
  const fetcher = await import(pathToFileURL(modulePath).href);
  if (typeof fetcher.fetch !== "function") {
    throw new Error(`${source.name}: fetcher module must export async function fetch(context)`);
  }

  const stagingDir = stagingDirFor(config);
  const downloadsDir = sourceDownloadDir(stagingDir, source.name);
  await ensureDir(downloadsDir);

  const profile = profileFor(config, source);
  const result = await fetcher.fetch({
    source,
    config,
    profile,
    downloadsDir,
    automationRoot,
    repoRoot,
    only,
  });
  // The kind filter is enforced here rather than trusted to each fetcher: a
  // fetcher may read `only` to skip the other half's calls, but one that
  // ignores it still cannot put the wrong kind in the manifest.
  const produced = normalizeEntries(source, result);
  const wantedKind = only ? ONLY_KINDS.get(only) : null;
  const staged = wantedKind ? produced.filter((item) => item.kind === wantedKind) : produced;
  if (staged.length === 0) {
    if (!only) {
      throw new Error(`${source.name}: fetcher returned no files`);
    }
    // Narrowing a source to nothing is a skip, not a failure: a source can
    // have no balance to mark, or no rows to stage, and failing it here
    // would bury the sources that did produce.
    console.log(`[fetch] ${source.name}: nothing to stage for --only ${only}`);
    return [];
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
      profile,
      path: filePath,
      sha256,
      downloaded_at: new Date().toISOString(),
    };
    entries.push(entry);
    await appendManifest(stagingDir, entry);
    console.log(`[fetch] ${source.name}: staged ${item.kind} ${filePath}`);
  }
  return entries;
}

export async function runFetchers({ configPath, sourceFilter = null, only = null } = {}) {
  if (only != null && !ONLY_KINDS.has(only)) {
    throw new Error(`only must be ${[...ONLY_KINDS.keys()].join(" or ")}, got: ${only}`);
  }
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

  const runEntries = [];
  const failures = [];
  // One failing source must not abort the run: later sources still fetch,
  // and the latest manifest is still written for whatever succeeded.
  for (const source of enabledSources) {
    try {
      const entries = await runSource(config, source, only);
      runEntries.push(...entries);
    } catch (error) {
      failures.push({ source: source.name, error });
      console.error(`[fetch] ${source.name} failed: ${error.message}`);
    }
  }
  if (only && runEntries.length === 0 && failures.length === 0) {
    console.warn(`[fetch] nothing matched --only ${only}; the latest manifest is empty`);
  }
  const latestManifestPath = await writeLatestManifest(stagingDirFor(config), runEntries);
  console.log(`[fetch] latest manifest: ${latestManifestPath}`);
  return { entries: runEntries, failures };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const { entries, failures } = await runFetchers({
    configPath: args.config,
    sourceFilter: args.source,
    only: args.only,
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
