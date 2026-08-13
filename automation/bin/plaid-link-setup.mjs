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
 * --products defaults to "transactions". Add "investments" for an Item
 * holding brokerages, IRAs, 401(k)s or donor-advised funds; without that
 * consent those accounts report no transactions at all.
 *
 * Passing --access-token instead of --out runs Link in update mode
 * against an Item you already have, to add consent for products it was
 * not linked with. Update mode keeps the Item and its provider account
 * ids, so the mappings in config.local.json stay valid — re-linking from
 * scratch issues fresh account ids and silently orphans every mapping.
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
  const args = { env: "sandbox", products: ["transactions"] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--client-id") args.clientId = argv[++i];
    else if (arg === "--secret") args.secret = argv[++i];
    else if (arg === "--env") args.env = argv[++i];
    else if (arg === "--out") args.out = argv[++i];
    else if (arg === "--access-token") args.accessToken = argv[++i];
    else if (arg === "--products") {
      args.products = argv[++i]
        .split(",")
        .map((p) => p.trim())
        .filter(Boolean);
    } else throw new Error(`unknown argument: ${arg}`);
  }
  if (!args.clientId || !args.secret) {
    throw new Error("--client-id and --secret are required");
  }
  if (!args.out === !args.accessToken) {
    throw new Error(
      "pass exactly one of --out (link a new Item) or --access-token (update an existing Item's consent)",
    );
  }
  if (!args.products.length) {
    throw new Error("--products must name at least one product");
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

const updating = Boolean(args.accessToken);
const existingToken = updating ? await readSecret(args.accessToken) : null;

const created = await httpsPostJson(
  `${base}/link/token/create`,
  {
    ...auth,
    client_name: "DeskBooks (local)",
    language: "en",
    country_codes: ["US"],
    user: { client_user_id: "deskbooks-local" },
    // Update mode takes the Item instead of a product list; the products
    // being added ride along as additional consent.
    ...(updating
      ? { access_token: existingToken, additional_consented_products: args.products }
      : { products: args.products }),
    hosted_link: {},
  },
  http,
);

console.log(
  updating
    ? `\nOpen this URL to grant ${args.products.join(", ")} for the existing Item:\n`
    : "\nOpen this URL in your browser and link your bank:\n",
);
console.log(`  ${created.hosted_link_url}\n`);
console.log("The link expires in about 30 minutes. Waiting for completion…");

const deadline = Date.now() + POLL_DEADLINE_MS;

async function consentedProducts() {
  const item = await httpsPostJson(`${base}/item/get`, { ...auth, access_token: existingToken }, http);
  return new Set(item.item?.consented_products ?? item.item?.products ?? []);
}

if (updating) {
  // Update mode issues no public token — an added-consent session leaves
  // item_add_results empty — so completion is read off the Item itself.
  for (;;) {
    if (Date.now() > deadline) {
      throw new Error("timed out waiting for Link completion; run the script again");
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
    const consented = await consentedProducts();
    if (args.products.every((product) => consented.has(product))) {
      break;
    }
  }
  console.log(`\nConsent updated: the Item now covers ${args.products.join(", ")}.`);
  console.log('Next: set "investments": true on the source in config.local.json, then re-run the fetch.');
} else {
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
}
