#!/usr/bin/env node
/**
 * One-time discovery helper: lists the accounts behind a Teller enrollment
 * so you can fill in the accounts mapping in config.local.json.
 *
 *   node bin/list-teller-accounts.mjs \
 *     --cert ~/.config/deskbooks/teller/certificate.pem \
 *     --key  ~/.config/deskbooks/teller/private_key.pem \
 *     [--token-service DeskBooks.Teller] [--token-account teller]
 */
import { readFile } from "node:fs/promises";
import { basicAuthHeader, httpsGetJson } from "../src/connector-http.mjs";
import { expandHome } from "../src/fetcher-api.mjs";
import { readGenericPassword } from "../src/keychain.mjs";
import { TELLER_HOSTS } from "../fetchers/teller.mjs";

function parseArgs(argv) {
  const args = { tokenService: "DeskBooks.Teller", tokenAccount: "teller" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--cert") args.cert = argv[++i];
    else if (arg === "--key") args.key = argv[++i];
    else if (arg === "--token-service") args.tokenService = argv[++i];
    else if (arg === "--token-account") args.tokenAccount = argv[++i];
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!args.cert || !args.key) {
    throw new Error("--cert and --key are required");
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const token = await readGenericPassword({ service: args.tokenService, account: args.tokenAccount });
const accounts = await httpsGetJson("https://api.teller.io/accounts", {
  allowedHosts: TELLER_HOSTS,
  headers: { authorization: basicAuthHeader(token) },
  cert: await readFile(expandHome(args.cert)),
  key: await readFile(expandHome(args.key)),
});

for (const account of accounts) {
  const label = [
    account.id,
    account.institution?.name ?? "?",
    account.name ?? "?",
    account.last_four ? `…${account.last_four}` : "",
    `${account.type ?? "?"}/${account.subtype ?? "?"}`,
  ].join("  ");
  console.log(label);
}
console.log(`\n${accounts.length} account(s). Map each id to a DeskBooks account id in config.local.json.`);
