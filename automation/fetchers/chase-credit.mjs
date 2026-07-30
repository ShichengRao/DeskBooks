import path from "node:path";
import {
  assertAllowedUrl,
  assertNonEmptyFile,
  clickByRole,
  gotoAllowed,
  safeFilename,
  savePageDiagnostics,
} from "../src/fetcher-api.mjs";
import { readGenericPassword } from "../src/keychain.mjs";

const LOGIN_PATTERNS = [
  /username/i,
  /password/i,
  /sign in/i,
  /log in/i,
  /verify your identity/i,
  /security code/i,
  /one-time code/i,
];

const ACCOUNT_ACTIVITY_NAMES = [
  /account activity/i,
  /see activity/i,
  /activity/i,
  /transactions/i,
];

const DOWNLOAD_NAMES = [
  /download account activity/i,
  /download activity/i,
  /download transactions/i,
  /^download$/i,
  /export/i,
];

const CSV_NAMES = [
  /^csv$/i,
  /comma/i,
  /spreadsheet/i,
  /chase csv/i,
];

const SUBMIT_DOWNLOAD_NAMES = [
  /^download$/i,
  /download now/i,
  /export/i,
];

export async function fetch({ page, source, downloadsDir }) {
  if (!page) {
    throw new Error("chase-credit fetcher requires browser access");
  }

  const startUrl = source.startUrl || "https://secure.chase.com/";
  const timeoutMs = source.downloadTimeoutMs ?? 10 * 60 * 1000;
  const mode = source.mode || "manual";
  validateCredentialConfig(source, mode);

  await gotoAllowed(page, startUrl, source);
  await page.waitForLoadState("domcontentloaded");
  await waitForChaseShell(page, source);

  if (mode === "manual") {
    return fetchManual(page, source, downloadsDir, timeoutMs);
  }
  if (mode !== "auto" && mode !== "auto-login") {
    throw new Error(`${source.name}: unknown Chase fetch mode ${mode}`);
  }

  try {
    if (mode === "auto-login") {
      await loginIfNeeded(page, source);
    }
    return await fetchAuto(page, source, downloadsDir, timeoutMs);
  } catch (error) {
    const diagnostics = await savePageDiagnostics(page, downloadsDir, "chase-auto-failed");
    console.error(`[chase] saved diagnostics: ${diagnostics.screenshotPath}`);
    console.error(`[chase] saved diagnostics: ${diagnostics.htmlPath}`);
    throw error;
  }
}

async function loginIfNeeded(page, source) {
  const loginFrame = await visibleLoginFrame(page);
  if (!loginFrame) {
    return;
  }

  const frameSrc = await loginFrame.getAttribute("src");
  if (!frameSrc) {
    throw new Error("Chase login frame has no src");
  }
  assertAllowedUrl(new URL(frameSrc, page.url()).href, source);

  const username = source.username || process.env.DESKBOOKS_CHASE_USERNAME;
  const password = await readGenericPassword({
    service: source.credentialService || "DeskBooks.Chase",
    account: username,
  });
  const frame = page.frameLocator(loginFrameSelector()).first();
  const loginFormTimeoutMs = source.loginFormTimeoutMs ?? 30000;

  await fillOne(usernameFieldLocators(frame), username, "Chase username", loginFormTimeoutMs);
  await fillOne(passwordFieldLocators(frame), password, "Chase password", loginFormTimeoutMs);
  await clickOne(submitLoginLocators(frame), "Chase sign-in button", loginFormTimeoutMs);

  await Promise.race([
    page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {}),
    page.waitForTimeout(5000),
  ]);
}

function validateCredentialConfig(source, mode) {
  if (mode !== "auto-login") {
    return;
  }
  const username = source.username || process.env.DESKBOOKS_CHASE_USERNAME;
  if (!username || username === "TODO_SET_CHASE_USERNAME") {
    throw new Error(
      "Chase auto-login needs a real username in automation/config.local.json or DESKBOOKS_CHASE_USERNAME.",
    );
  }
  if (!source.credentialService) {
    throw new Error("Chase auto-login needs credentialService set in automation/config.local.json.");
  }
}

async function waitForChaseShell(page, source) {
  const accountText = source.accountText ? page.getByText(source.accountText, { exact: false }) : null;
  await Promise.race([
    page.locator(loginFrameSelector()).first().waitFor({ state: "attached", timeout: 15000 }).catch(() => {}),
    accountText?.first().waitFor({ state: "attached", timeout: 15000 }).catch(() => {}),
    page.waitForLoadState("networkidle", { timeout: 15000 }).catch(() => {}),
    page.waitForTimeout(5000),
  ]);
}

async function fetchManual(page, source, downloadsDir, timeoutMs) {
  console.log("[chase] Log in if needed, open the credit card activity/download flow, and download CSV.");
  console.log("[chase] This fetcher will not click Chase controls; it only captures the downloaded file.");

  const download = await page.waitForEvent("download", { timeout: timeoutMs });
  assertAllowedUrl(page.url(), source);

  const suggested = download.suggestedFilename() || `${source.name}.csv`;
  const datedName = `${new Date().toISOString().slice(0, 10)}-${safeFilename(suggested)}`;
  const destination = path.join(downloadsDir, datedName);
  await download.saveAs(destination);
  await assertNonEmptyFile(destination);

  return { files: [destination] };
}

async function fetchAuto(page, source, downloadsDir, timeoutMs) {
  await assertAuthenticated(page);

  if (source.accountText) {
    await clickAccountContainingText(page, source.accountText);
    await waitForChaseNavigation(page, source.afterAccountClickWaitMs ?? 12000);
  }

  await assertAuthenticated(page);
  await clickFirstRoleMatch(page, ["link", "button"], ACCOUNT_ACTIVITY_NAMES, source, "account activity");
  await waitForChaseNavigation(page, source.afterActivityClickWaitMs ?? 8000);

  const [initialDownload] = await Promise.all([
    page.waitForEvent("download", { timeout: source.initialDownloadTimeoutMs ?? 10000 }).catch(() => null),
    clickFirstRoleMatch(page, ["link", "button"], DOWNLOAD_NAMES, source, "download activity"),
  ]);
  if (initialDownload) {
    if (source.dateRangeDays) {
      await initialDownload.delete().catch(() => {});
      const destination = await downloadOtherActivity(page, source, downloadsDir, timeoutMs);
      return { files: [destination] };
    } else {
    const destination = await saveDownload(initialDownload, source, downloadsDir);
    return { files: [destination] };
    }
  }

  await chooseCsvIfPresent(page);

  const [download] = await Promise.all([
    page.waitForEvent("download", { timeout: timeoutMs }),
    clickFirstRoleMatch(
      page,
      ["button", "link"],
      SUBMIT_DOWNLOAD_NAMES,
      source,
      "confirm download",
      source.downloadFormTimeoutMs ?? 60000,
    ),
  ]);
  assertAllowedUrl(page.url(), source);

  const destination = await saveDownload(download, source, downloadsDir);
  return { files: [destination] };
}

async function downloadOtherActivity(page, source, downloadsDir, timeoutMs) {
  await clickFirstRoleMatch(
    page,
    ["button", "link"],
    [/download other activity/i],
    source,
    "download other activity",
    source.downloadFormTimeoutMs ?? 60000,
  );
  await waitForChaseNavigation(page, source.afterDownloadOtherClickWaitMs ?? 12000);
  await selectActivityPreset(page, source.activityPreset || "last year", source.downloadFormTimeoutMs ?? 60000);
  if ((source.activityPreset || "").toLowerCase() === "choose a date range") {
    await fillRollingDateRange(page, source);
  }

  const [download] = await Promise.all([
    page.waitForEvent("download", { timeout: timeoutMs }),
    clickDownloadButton(page, source),
  ]);
  assertAllowedUrl(page.url(), source);
  return saveDownload(download, source, downloadsDir);
}

async function selectActivityPreset(page, preset, timeoutMs) {
  const normalized = String(preset).trim().toLowerCase();
  const testId = `showing-activity-select-${normalized}`;
  await page.getByTestId("activity-type-drop-down-selector").click();
  const option = page.getByTestId(testId);
  await option.waitFor({ state: "attached", timeout: timeoutMs });
  await option.click();
}

async function fillRollingDateRange(page, source) {
  const days = Number(source.dateRangeDays || 365);
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - days);
  const from = formatDate(start);
  const to = formatDate(end);

  await fillMdsDatepicker(page, page.locator('mds-datepicker[label="From"]').first(), from, source);
  await fillMdsDatepicker(page, page.locator('mds-datepicker[label="To"]').first(), to, source);
}

function formatDate(date) {
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  const yyyy = String(date.getFullYear());
  return `${mm}/${dd}/${yyyy}`;
}

async function fillMdsDatepicker(page, locator, value, source) {
  await locator.waitFor({ state: "visible", timeout: source.downloadFormTimeoutMs ?? 60000 });
  await locator.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type(value, { delay: 10 });
  await page.keyboard.press("Tab");
}

async function clickDownloadButton(page, source) {
  const button = page.getByTestId("downloadButton");
  if ((await button.count().catch(() => 0)) === 1) {
    await button.click();
    assertAllowedUrl(page.url(), source);
    return;
  }
  await clickFirstRoleMatch(page, ["button", "link"], SUBMIT_DOWNLOAD_NAMES, source, "download");
}

async function saveDownload(download, source, downloadsDir) {
  const suggested = download.suggestedFilename() || `${source.name}.csv`;
  const datedName = `${new Date().toISOString().slice(0, 10)}-${safeFilename(suggested)}`;
  const destination = path.join(downloadsDir, datedName);
  await download.saveAs(destination);
  await assertNonEmptyFile(destination);
  return destination;
}

async function waitForChaseNavigation(page, settleMs) {
  await page.waitForLoadState("domcontentloaded", { timeout: 30000 }).catch(() => {});
  await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(settleMs);
}

async function assertAuthenticated(page) {
  if (await visibleLoginFrame(page)) {
    throw new Error(
      "Chase is showing its login frame. Use mode=auto-login with a Keychain credential, or run manual mode.",
    );
  }

  for (const pattern of LOGIN_PATTERNS) {
    const count = await page.getByText(pattern).count().catch(() => 0);
    if (count > 0) {
      throw new Error(
        "Chase is asking for login or verification. Fully automated mode requires an already-authenticated remembered browser session.",
      );
    }
  }
}

function loginFrameSelector() {
  return 'iframe[title*="logon" i], iframe[id*="logon" i], iframe[src*="/auth/" i]';
}

async function visibleLoginFrame(page) {
  const frame = page.locator(loginFrameSelector()).first();
  if ((await frame.count().catch(() => 0)) > 0) {
    return frame;
  }
  return null;
}

function usernameFieldLocators(frame) {
  return [
    frame.getByLabel(/username|user id|user name/i),
    frame.getByRole("textbox", { name: /username|user id|user name/i }),
    frame.locator('input[name*="user" i]'),
    frame.locator('input[id*="user" i]'),
    frame.locator('input[type="text"]'),
    frame.locator('input:not([type])'),
  ];
}

function passwordFieldLocators(frame) {
  return [
    frame.getByLabel(/password/i),
    frame.locator('input[type="password"]'),
    frame.locator('input[name*="password" i]'),
    frame.locator('input[id*="password" i]'),
  ];
}

function submitLoginLocators(frame) {
  return [
    frame.getByRole("button", { name: /sign in|log in|next|continue/i }),
    frame.locator('button[type="submit"]'),
    frame.locator('input[type="submit"]'),
  ];
}

async function fillOne(locators, value, label, timeoutMs) {
  const match = await resolveOne(locators, label, timeoutMs);
  await match.fill(value);
}

async function clickOne(locators, label, timeoutMs) {
  const match = await resolveOne(locators, label, timeoutMs);
  await match.click();
}

async function resolveOne(locators, label, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastCounts = [];
  while (Date.now() < deadline) {
    const counts = [];
    for (const locator of locators) {
      const count = await locator.count().catch(() => 0);
      const matches = [];
      for (let i = 0; i < count; i += 1) {
        const candidate = locator.nth(i);
        if (await candidate.isVisible().catch(() => false)) {
          matches.push(candidate);
        }
      }
      if (matches.length === 1) {
        return matches[0];
      }
      counts.push(matches.length);
    }
    lastCounts = counts;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(
    `expected exactly one visible ${label} within ${timeoutMs}ms; visible counts by selector: ${lastCounts.join(", ")}`,
  );
}

async function clickAccountContainingText(page, text) {
  const locators = [
    page.locator("button").filter({ hasText: text }),
    page.locator("a").filter({ hasText: text }),
    page.locator('[role="button"]').filter({ hasText: text }),
    page.locator('[data-testid*="account" i]').filter({ hasText: text }),
  ];
  const match = await resolveOne(locators, `configured account containing ${JSON.stringify(text)}`, 10000);
  await match.click();
}

async function clickFirstRoleMatch(page, roles, names, source, label, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  let candidates = [];
  while (Date.now() < deadline) {
    candidates = [];
    for (const name of names) {
      for (const role of roles) {
        const locator = page.getByRole(role, { name });
        const count = await locator.count().catch(() => 0);
        if (count === 1) {
          await clickByRole(page, role, name, source);
          return;
        }
        if (count > 1) {
          candidates.push({ role, name, count });
        }
      }
    }
    await page.waitForTimeout(500);
  }

  const found = candidates
    .map((candidate) => `${candidate.role}:${candidate.name}=${candidate.count}`)
    .join(", ");
  throw new Error(`expected one ${label} control by priority order; candidates: ${found || "none"}`);
}

async function chooseCsvIfPresent(page) {
  for (const name of CSV_NAMES) {
    const radio = page.getByRole("radio", { name });
    if ((await radio.count().catch(() => 0)) === 1) {
      await radio.check();
      return;
    }
    const option = page.getByRole("option", { name });
    if ((await option.count().catch(() => 0)) === 1) {
      await option.click();
      return;
    }
    const menuItem = page.getByRole("menuitem", { name });
    if ((await menuItem.count().catch(() => 0)) === 1) {
      await menuItem.click();
      return;
    }
  }
}
