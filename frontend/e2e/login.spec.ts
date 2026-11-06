import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

test("a user signs in through the identity provider and sees who they are", async ({ page }) => {
  await signIn(page, "preparer");
  await expect(page.getByTestId("who")).toContainText("Dana Preparer");
  await expect(page.getByTestId("who")).toContainText("Preparer");
  await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible();
});

test("the interface switches to Arabic and right-to-left and back", async ({ page }) => {
  await signIn(page, "reviewer");
  await page.getByRole("button", { name: "العربية" }).click();
  await expect(page.locator("html")).toHaveAttribute("dir", "rtl");
  await expect(page.locator("html")).toHaveAttribute("lang", "ar");
  await expect(page.getByTestId("who")).toContainText("المراجع الضريبي");
  await page.getByRole("button", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
});

test("a preparer does not see the admin screens", async ({ page }) => {
  await signIn(page, "preparer");
  await expect(page.getByRole("link", { name: "Rule packs" })).toHaveCount(0);
  await page.goto("/audit");
  await expect(page.getByRole("alert").first()).toContainText("Missing tenant-wide permission");
});
