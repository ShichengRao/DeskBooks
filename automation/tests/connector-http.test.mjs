import assert from "node:assert/strict";
import test from "node:test";

import { basicAuthHeader, httpsGetJson } from "../src/connector-http.mjs";

test("refuses requests without an allowlist", async () => {
  await assert.rejects(httpsGetJson("https://api.example.com/x"), /allowedHosts is required/);
  await assert.rejects(
    httpsGetJson("https://api.example.com/x", { allowedHosts: [] }),
    /allowedHosts is required/,
  );
});

test("refuses non-HTTPS URLs and hosts outside the allowlist", async () => {
  await assert.rejects(
    httpsGetJson("http://production.plaid.com/accounts/get", { allowedHosts: ["production.plaid.com"] }),
    /refusing non-HTTPS URL/,
  );
  await assert.rejects(
    httpsGetJson("https://evil.example.com/accounts", { allowedHosts: ["production.plaid.com"] }),
    /outside allowedHosts: evil.example.com/,
  );
});

test("basicAuthHeader encodes token-style credentials", () => {
  assert.equal(basicAuthHeader("token_abc"), `Basic ${Buffer.from("token_abc:").toString("base64")}`);
});
