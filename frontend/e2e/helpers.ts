import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, type Browser, type Locator, type Page } from "@playwright/test";

export const PASSWORD = "demo-pass-1";
export const DEMO_TENANT = "11111111-1111-4111-8111-111111111111";

/** Signs in through the real Keycloak login page. */
export async function signIn(page: Page, username: string, path = "/") {
  await page.goto(path);
  await page.waitForURL(/realms\/uaevat/);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(PASSWORD);
  await page.locator("#kc-login").click();
  await page.waitForURL((url) => url.host === "localhost:5183" && !url.pathname.startsWith("/auth/callback"));
}

/** Each role gets its own browser context, as if on its own computer. */
export async function as(browser: Browser, username: string, path = "/"): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, username, path);
  return page;
}

export function csvFile(name: string, content: string) {
  return { name, mimeType: "text/csv", buffer: Buffer.from(content) };
}

export function pdfFile(name: string) {
  return { name, mimeType: "application/pdf", buffer: Buffer.from(`%PDF-1.4 evidence ${name} ${Date.now()}`) };
}

export function section(page: Page, heading: string | RegExp): Locator {
  return page.locator("section, form").filter({ has: page.getByRole("heading", { name: heading }) }).first();
}

export async function loadFile(page: Page, kindLabel: string, file: { name: string; mimeType: string; buffer: Buffer }) {
  await page.getByLabel("Data type").selectOption({ label: kindLabel });
  await page.getByLabel(/^File/).setInputFiles(file);
  await page.getByRole("button", { name: "Load file" }).click();
  await expect(page.getByRole("status").filter({ hasText: file.name })).toBeVisible();
}

export function box(page: Page, code: string): Locator {
  return page.getByRole("row").filter({ has: page.getByRole("button", { name: code, exact: true }) });
}

/** Publishes e-invoice events to Kafka through the simulated ASP platform. */
export function publishEinvoices(trn: string, issuedCsv: string, faults = "") {
  const dir = mkdtempSync(join(tmpdir(), "vat-e2e-"));
  const file = join(dir, "issued.csv");
  writeFileSync(file, issuedCsv);
  const args = ["-jar", "../asp-simulator/target/asp-simulator-0.1.0-SNAPSHOT-exec.jar", `--tenant=${DEMO_TENANT}`, `--trn=${trn}`, `--issued=${file}`];
  if (faults) args.push(`--faults=${faults}`);
  return execFileSync("java", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
}

/** Keeps running reconciliation until the worker has caught up with Kafka. */
export async function reconcileUntil(page: Page, check: () => Promise<boolean>, attempts = 20) {
  for (let i = 0; i < attempts; i++) {
    await page.getByRole("button", { name: "Run reconciliation" }).click();
    await page.waitForLoadState("networkidle");
    if (await check()) return;
    await page.waitForTimeout(1000);
  }
  throw new Error("Reconciliation did not reach the expected state");
}
