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
  if (process.platform === "darwin") {
    return path.join(process.env.HOME, "Library", "Application Support", "DeskBooks", "import-staging");
  }
  return path.join(process.env.HOME, ".local", "share", "deskbooks", "import-staging");
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

async function runSource(config, source, browserContext) {
  if (!source.name || !source.module) {
    throw new Error("each source needs name and module");
  }
  if (!Number.isInteger(source.accountId)) {
    throw new Error(`${source.name}: accountId must be an integer`);
  }
  if (!source.importerName) {
    throw new Error(`${source.name}: importerName is required`);
  }

  const modulePath = resolveFrom(config.__dir, source.module);
  const fetcher = await import(pathToFileURL(modulePath).href);
  if (typeof fetcher.fetch !== "function") {
    throw new Error(`${source.name}: fetcher module must export async function fetch(context)`);
  }

  const stagingDir = resolveFrom(config.__dir, config.stagingDir || defaultStagingDir());
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
    const files = Array.isArray(result?.files) ? result.files : [];
    if (files.length === 0) {
      throw new Error(`${source.name}: fetcher returned no files`);
    }

    const entries = [];
    for (const file of files) {
      const filePath = path.resolve(expandHome(file));
      await assertNonEmptyFile(filePath);
      const sha256 = await fileSha256(filePath);
      const entry = {
        source: source.name,
        account_id: source.accountId,
        importer_name: source.importerName,
        path: filePath,
        sha256,
        downloaded_at: new Date().toISOString(),
      };
      entries.push(entry);
      await appendManifest(stagingDir, entry);
      console.log(`[fetch] ${source.name}: staged ${filePath}`);
    }
    return entries;
  } finally {
    if (page) {
      await page.close().catch(() => {});
    }
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const config = await loadConfig(args.config);
  const enabledSources = (config.sources ?? []).filter((source) => {
    if (source.enabled === false) {
      return false;
    }
    return !args.source || source.name === args.source;
  });
  if (enabledSources.length === 0) {
    throw new Error("no enabled fetch sources matched");
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

  try {
    const runEntries = [];
    let stagingDir = null;
    for (const source of enabledSources) {
      const entries = await runSource(config, source, browserContext);
      runEntries.push(...entries);
      stagingDir = resolveFrom(config.__dir, config.stagingDir || defaultStagingDir());
    }
    if (stagingDir) {
      const latestManifestPath = await writeLatestManifest(stagingDir, runEntries);
      console.log(`[fetch] latest manifest: ${latestManifestPath}`);
    }
  } finally {
    if (browserContext) {
      await browserContext.close();
    }
  }
}

main().catch((error) => {
  console.error(`[fetch] failed: ${error.message}`);
  process.exit(1);
});
