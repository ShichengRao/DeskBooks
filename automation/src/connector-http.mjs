/**
 * The only module in automation/ allowed to open network connections
 * (enforced by tests/network-guard.test.mjs). Every request must name an
 * explicit host allowlist — there is no permissive default — and only
 * HTTPS GET/POST of JSON are supported, which is all a read-only bank
 * connector needs.
 */
import https from "node:https";

const MAX_BODY_BYTES = 20 * 1024 * 1024;

function assertAllowedRequestUrl(url, allowedHosts) {
  if (!Array.isArray(allowedHosts) || allowedHosts.length === 0) {
    throw new Error("connector-http: allowedHosts is required and must be non-empty");
  }
  if (url.protocol !== "https:") {
    throw new Error(`connector-http: refusing non-HTTPS URL: ${url}`);
  }
  const host = url.hostname.toLowerCase();
  const ok = allowedHosts.map((h) => String(h).toLowerCase()).includes(host);
  if (!ok) {
    throw new Error(`connector-http: refusing URL outside allowedHosts: ${host}`);
  }
}

function requestJson(
  method,
  urlLike,
  { allowedHosts, headers = {}, body = null, cert, key, timeoutMs = 30_000 } = {},
) {
  const url = new URL(urlLike);
  assertAllowedRequestUrl(url, allowedHosts);

  const payload = body == null ? null : Buffer.from(JSON.stringify(body), "utf8");
  const requestHeaders = { accept: "application/json", ...headers };
  if (payload) {
    requestHeaders["content-type"] = "application/json";
    requestHeaders["content-length"] = payload.length;
  }

  return new Promise((resolve, reject) => {
    const request = https.request(
      url,
      { method, headers: requestHeaders, cert, key, timeout: timeoutMs },
      (response) => {
        const chunks = [];
        let size = 0;
        response.on("data", (chunk) => {
          size += chunk.length;
          if (size > MAX_BODY_BYTES) {
            request.destroy(new Error("connector-http: response too large"));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(
              new Error(
                `connector-http: ${url.hostname}${url.pathname} returned ${response.statusCode}: ${text.slice(0, 300)}`,
              ),
            );
            return;
          }
          try {
            resolve(JSON.parse(text));
          } catch (error) {
            reject(new Error(`connector-http: invalid JSON from ${url.hostname}: ${error.message}`));
          }
        });
      },
    );
    request.on("timeout", () => request.destroy(new Error(`connector-http: timeout after ${timeoutMs}ms`)));
    request.on("error", reject);
    if (payload) {
      request.write(payload);
    }
    request.end();
  });
}

export async function httpsGetJson(urlLike, options = {}) {
  return requestJson("GET", urlLike, options);
}

export async function httpsPostJson(urlLike, body, options = {}) {
  return requestJson("POST", urlLike, { ...options, body });
}

export function basicAuthHeader(username, password = "") {
  const token = Buffer.from(`${username}:${password}`, "utf8").toString("base64");
  return `Basic ${token}`;
}
