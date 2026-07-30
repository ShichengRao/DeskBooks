#!/usr/bin/env node
/**
 * Lists the accounts behind a linked Plaid Item so you can fill in the
 * accounts mapping in config.local.json.
 *
 *   node bin/list-plaid-accounts.mjs \
 *     --client-id ~/.config/deskbooks/plaid/client-id \
 *     --secret    ~/.config/deskbooks/plaid/secret \
 *     --env sandbox \
 *     --access-token ~/.config/deskbooks/plaid/access-token-mybank
 */
import { readFile } from "node:fs/promises";
import { httpsPostJson } from "../src/connector-http.mjs";
import { expandHome } from "../src/fetcher-api.mjs";
import { PLAID_HOSTS, plaidHost } from "../fetchers/plaid.mjs";

function parseArgs(argv) {
  const args = { env: "sandbox" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--client-id") args.clientId = argv[++i];
    else if (arg === "--secret") args.secret = argv[++i];
    else if (arg === "--env") args.env = argv[++i];
    else if (arg === "--access-token") args.accessToken = argv[++i];
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!args.clientId || !args.secret || !args.accessToken) {
    throw new Error("--client-id, --secret, and --access-token are required");
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

const args = parseArgs(process.argv.slice(2));
const base = plaidHost(args.env);
const response = await httpsPostJson(
  `${base}/accounts/get`,
  {
    client_id: await readSecret(args.clientId),
    secret: await readSecret(args.secret),
    access_token: await readSecret(args.accessToken),
  },
  { allowedHosts: PLAID_HOSTS },
);

for (const account of response.accounts ?? []) {
  const label = [
    account.account_id,
    account.name ?? "?",
    account.mask ? `…${account.mask}` : "",
    `${account.type ?? "?"}/${account.subtype ?? "?"}`,
  ].join("  ");
  console.log(label);
}
console.log(`\n${(response.accounts ?? []).length} account(s). Map each id to a DeskBooks account id in config.local.json.`);
