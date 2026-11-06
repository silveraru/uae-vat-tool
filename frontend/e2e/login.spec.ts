import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

test("a user signs in through the identity provider and sees their firm and role", async ({ page }) => {
  await signIn(page, "preparer");
  await expect(page.getByRole("heading", { name: /Welcome, Dana Preparer/ })).toBeVisible();
  await expect(page.getByText("Demo Tax Advisers LLC")).toBeVisible();
  await expect(page.getByText("Preparer", { exact: true })).toBeVisible();
});

test("the interface switches to Arabic and right-to-left", async ({ page }) => {
  await signIn(page, "reviewer");
  await page.getByRole("button", { name: "العربية" }).click();
  await expect(page.locator("html")).toHaveAttribute("dir", "rtl");
  await expect(page.locator("html")).toHaveAttribute("lang", "ar");
  await expect(page.getByText("المراجع الضريبي")).toBeVisible();
  await page.getByRole("button", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
});
