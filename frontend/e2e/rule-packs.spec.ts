import { expect, test } from "@playwright/test";
import { as } from "./helpers";

/**
 * The firm's rule pack and the client rules on top of it, through the screens. Nothing here is saved:
 * a saved copy would become the pack in force for every client, so the editor is only checked.
 * Skips itself when the demo is not loaded (scripts/reset-demo.sh builds it).
 */

test("an admin reads the pack in force and checks an edited copy before saving it", async ({ browser }) => {
  const page = await as(browser, "admin", "/rule-packs");
  await expect(page.getByRole("heading", { level: 1, name: "Rule packs" })).toBeVisible();
  const inForce = page.getByRole("row").filter({ hasText: "In force today" });
  test.skip((await inForce.count()) === 0, "No rule pack in force");
  const version = (await inForce.getByRole("link").first().textContent()) ?? "";
  await inForce.getByRole("link", { name: version }).click();

  await expect(page.getByRole("heading", { level: 1, name: version })).toBeVisible();
  for (const heading of ["Filing and rates", "Reconciliation tolerances", "Classification rules", "Review patterns", "Penalties", "FTA Audit File"]) {
    await expect(page.getByRole("heading", { level: 2, name: heading })).toBeVisible();
  }
  await expect(page.getByRole("cell", { name: "POS-DOMESTIC", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: /^Form VAT201/ })).toBeVisible();

  await page.getByRole("link", { name: "Edit a copy" }).click();
  const source = page.getByLabel(/Pack source/);
  await expect(source).toHaveValue(/glToleranceAed: 100/);
  await source.fill((await source.inputValue()).replace("glToleranceAed: 100", "glToleranceAed: 60"));
  await page.getByRole("button", { name: "Check the copy" }).click();

  await expect(page.getByRole("status").filter({ hasText: "reads correctly" })).toBeVisible();
  const change = page.getByRole("row").filter({ hasText: "reconciliation.glToleranceAed" });
  await expect(change).toContainText("100");
  await expect(change).toContainText("60");
  await expect(page.getByRole("button", { name: "Save as a new draft version" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("everyone on a client can read the rules that apply to it, and only an admin can change them", async ({ browser }) => {
  const admin = await as(browser, "admin", "/rule-packs");
  const clients = admin.locator("section").filter({ has: admin.getByRole("heading", { name: "Clients with rules of their own" }) }).getByRole("link");
  test.skip((await clients.count()) === 0, "No client has rules of its own");
  const names = await clients.allTextContents();

  const reviewer = await as(browser, "reviewer");
  for (const name of names) {
    await admin.goto("/rule-packs");
    await admin.getByRole("link", { name, exact: true }).click();
    const card = admin.locator("#client-rules");
    await expect(card.getByRole("heading", { name: "Rules for this client" })).toBeVisible();
    await expect(card).toContainText("Firm rule pack in force");
    await expect(card.getByRole("button", { name: "Change the client rules" })).toBeVisible();
    await expect(admin.getByRole("alert")).toHaveCount(0);

    await reviewer.goto(admin.url().replace(/#.*$/, ""));
    const seen = reviewer.locator("#client-rules");
    await expect(seen.getByRole("heading", { name: "Rules for this client" })).toBeVisible();
    await expect(seen).toContainText(/Client rules version \d+|No client rules today/);
    await expect(seen.getByRole("button", { name: "Change the client rules" })).toHaveCount(0);
    await seen.getByText(/Show the firm's rules/).click();
    await expect(seen.getByRole("heading", { level: 2, name: "Classification rules" })).toBeVisible();
  }
});

test("the client rule editor offers every kind of client rule", async ({ browser }) => {
  const admin = await as(browser, "admin", "/rule-packs");
  const first = admin.locator("section").filter({ has: admin.getByRole("heading", { name: "Clients with rules of their own" }) }).getByRole("link").first();
  test.skip((await first.count()) === 0, "No client has rules of its own");
  await first.click();
  const card = admin.locator("#client-rules");
  await card.getByRole("button", { name: "Change the client rules" }).click();
  for (const legend of ["Classification rules", "Possible domestic reverse charge", "Blocked input VAT", "Firm rules switched off for this client",
    "Reconciliation tolerances", "Suggestion keywords"]) {
    await expect(card.locator("legend", { hasText: legend })).toBeVisible();
  }
  await expect(card.getByRole("button", { name: "Publish as a draft" })).toBeDisabled();
  await card.getByRole("button", { name: "Cancel" }).click();
  await expect(card.getByRole("button", { name: "Change the client rules" })).toBeVisible();
});
