import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { as } from "./helpers";

/**
 * Automated WCAG 2.1 AA checks on the main screens in both languages. Axe finds only part of what a
 * manual audit does, so this is a floor. NFR-12 still needs a manual review with assistive technology.
 */
async function audit(page: Page, name: string) {
  await page.waitForLoadState("networkidle");
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  const problems = results.violations.map((v) => `${name}: ${v.id} (${v.impact}) ${v.nodes.length} element(s), e.g. ${v.nodes[0]?.html?.slice(0, 140)}`);
  expect(problems, problems.join("\n")).toEqual([]);
}

async function firstPeriod(page: Page): Promise<string | null> {
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const link = page.getByRole("table").getByRole("link", { name: /→/ }).first();
  return (await link.count()) > 0 ? link.getAttribute("href") : null;
}

for (const locale of ["en", "ar"] as const) {
  test(`main screens have no automatically detectable accessibility problems in ${locale}`, async ({ browser }) => {
    test.setTimeout(240_000);
    const page = await as(browser, "admin", "/");
    if (locale === "ar") {
      await page.getByRole("button", { name: "العربية" }).click();
      await expect(page.locator("html")).toHaveAttribute("dir", "rtl");
    }
    await audit(page, "portfolio");
    await page.goto("/deadlines");
    await audit(page, "deadlines");
    await page.goto("/entities/new");
    await audit(page, "new client");
    await page.goto("/rule-packs");
    await audit(page, "rule packs");
    await page.goto("/audit");
    await audit(page, "audit log");

    const period = await firstPeriod(page);
    test.skip(period === null, "Needs a period from the full-close test");
    await page.goto("/practice");
    await audit(page, "practice");
    for (const tab of ["", "/data", "/exceptions", "/lines", "/suggestions", "/adjustments", "/schemes", "/exports", "/review", "/filing", "/refund", "/evidence", "/corrections"]) {
      await page.goto(`${period}${tab}`);
      await audit(page, `period${tab || "/return"}`);
    }
    await page.goto(period!);
    const entity = await page.getByRole("heading", { level: 1 }).getByRole("link").getAttribute("href");
    for (const path of ["", "/analytics", "/audit-cases"]) {
      await page.goto(`${entity}${path}`);
      await audit(page, `client${path || " page"}`);
    }
  });
}

test("the keyboard reaches a box's detail and the page has one main landmark", async ({ browser }) => {
  const page = await as(browser, "reviewer", "/");
  const period = await firstPeriod(page);
  test.skip(period === null, "Needs a period from the full-close test");
  await page.goto(period!);
  const button = page.getByRole("button", { name: "1b", exact: true });
  await button.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("region", { name: /Details of box 1b/ })).toBeVisible();
  await expect(page.locator("main")).toHaveCount(1);
});
