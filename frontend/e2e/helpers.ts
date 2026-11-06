import { expect, type Page } from "@playwright/test";

export const PASSWORD = "demo-pass-1";

/** Signs in through the real Keycloak login page. */
export async function signIn(page: Page, username: string, path = "/") {
  await page.goto(path);
  await page.waitForURL(/realms\/uaevat/);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(PASSWORD);
  await page.locator("#kc-login").click();
  await page.waitForURL((url) => url.host === "localhost:5183" && !url.pathname.startsWith("/auth/callback"));
}

export async function signOut(page: Page) {
  await page.getByRole("button", { name: /sign out|تسجيل الخروج/i }).click();
  await page.waitForLoadState("networkidle");
  await expect(page).toHaveURL(/./);
}
