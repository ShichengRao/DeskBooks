#!/usr/bin/env node
/**
 * One-time Plaid enrollment via Hosted Link, entirely from the CLI:
 * creates a Hosted Link session, prints the URL for you to complete in
 * your browser, polls until the Item is linked, exchanges the public
 * token, and writes the access token to a private file.
 *
 *   node bin/plaid-link-setup.mjs \
 *     --client-id ~/.config/deskbooks/plaid/client-id \
 *     --secret    ~/.config/deskbooks/plaid/secret \
 *     --env sandbox \
 *     --out ~/.config/deskbooks/plaid/access-token-mybank
 *
 * Sandbox tip: pick any institution and log in with user_good / pass_good.
 */
import { readFile, writeFile } from "node:fs/promises";
import { httpsPostJson } from "../src/connector-http.mjs";
import { expandHome } from "../src/fetcher-api.mjs";
import { PLAID_HOSTS, plaidHost } from "../fetchers/plaid.mjs";

const POLL_INTERVAL_MS = 5_000;
const POLL_DEADLINE_MS = 30 * 60 * 1000; // hosted link URLs live ~30 minutes

function parseArgs(argv) {
  const args = { env: "sandbox" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--client-id") args.clientId = argv[++i];
    else if (arg === "--secret") args.secret = argv[++i];
    else if (arg === "--env") args.env = argv[++i];
    else if (arg === "--out") args.out = argv[++i];
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!args.clientId || !args.secret || !args.out) {
    throw new Error("--client-id, --secret, and --out are required");
  }
  return args;
}

async function readSecret(rawPath) {
  const value = (await readFile(expandHome(rawPath), "utf8")).trim();
  if (!value) {
    throw new Error(`credential file is empty: ${rawPath}`);
  }
  return value;
}

function findPublicToken(linkTokenGetResponse) {
  for (const session of linkTokenGetResponse.link_sessions ?? []) {
    for (const result of session.results?.item_add_results ?? []) {
      if (result.public_token) {
        return result.public_token;
      }
    }
  }
  return null;
}

const args = parseArgs(process.argv.slice(2));
const base = plaidHost(args.env);
const http = { allowedHosts: PLAID_HOSTS };
const auth = { client_id: await readSecret(args.clientId), secret: await readSecret(args.secret) };

const created = await httpsPostJson(
  `${base}/link/token/create`,
  {
    ...auth,
    client_name: "DeskBooks (local)",
    language: "en",
    country_codes: ["US"],
    user: { client_user_id: "deskbooks-local" },
    products: ["transactions"],
    hosted_link: {},
  },
  http,
);

console.log("\nOpen this URL in your browser and link your bank:\n");
console.log(`  ${created.hosted_link_url}\n`);
console.log("The link expires in about 30 minutes. Waiting for completion…");

const deadline = Date.now() + POLL_DEADLINE_MS;
let publicToken = null;
while (!publicToken) {
  if (Date.now() > deadline) {
    throw new Error("timed out waiting for Link completion; run the script again");
  }
  await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  const status = await httpsPostJson(
    `${base}/link/token/get`,
    { ...auth, link_token: created.link_token },
    http,
  );
  publicToken = findPublicToken(status);
}

const exchanged = await httpsPostJson(
  `${base}/item/public_token/exchange`,
  { ...auth, public_token: publicToken },
  http,
);

const outPath = expandHome(args.out);
await writeFile(outPath, `${exchanged.access_token}\n`, { encoding: "utf8", mode: 0o600 });
console.log(`\nLinked. Access token written to ${outPath} (item ${exchanged.item_id}).`);
console.log("Next: node bin/list-plaid-accounts.mjs to map account ids in config.local.json.");
