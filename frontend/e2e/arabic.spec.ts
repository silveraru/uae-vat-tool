import { expect, test, type Page } from "@playwright/test";
import { as, csvFile } from "./helpers";

const TABS: [string, string][] = [
  ["", "الإقرار"], ["/data", "البيانات"], ["/exceptions", "الاستثناءات"], ["/lines", "البنود"], ["/suggestions", "الاقتراحات"], ["/adjustments", "التسويات"],
  ["/schemes", "الأنظمة الخاصة"], ["/exports", "الصادرات والهامش"], ["/review", "المراجعة والاعتماد"], ["/filing", "التقديم والدفع"], ["/refund", "الاسترداد"],
  ["/evidence", "المستندات"], ["/corrections", "التصحيحات"],
];

/** A translation key shown as text means a screen is missing a translation. */
async function assertNoRawKeys(page: Page, where: string) {
  const text = await page.locator("main").innerText();
  const raw = text.match(/\b(common|portfolio|deadlines|entity|period|return|comments|data|exceptions|lines|review|adjustments|filing|evidence|corrections|rulePacks|audit|state|treatment|role|step|suggestions|schemes|exports|refund|disclosure|penalty|practice|analytics|auditCases|connectors|esign|documents|errors)\.[a-zA-Z_.]+\b/g);
  expect(raw, `${where} shows untranslated keys`).toBeNull();
}

async function assertNoSidewaysScroll(page: Page, where: string) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, `${where} scrolls sideways`).toBeLessThanOrEqual(0);
}

test("a user works through a return entirely in Arabic", async ({ browser }) => {
  test.setTimeout(180_000);
  const stamp = String(Date.now()).slice(-11);
  const name = `Arabic Client ${stamp}`;

  const admin = await as(browser, "admin", "/entities/new");
  await admin.getByRole("button", { name: "العربية" }).click();
  await expect(admin.locator("html")).toHaveAttribute("dir", "rtl");
  await admin.getByLabel("الاسم القانوني", { exact: true }).fill(name);
  await admin.getByLabel("الاسم القانوني بالعربية").fill("شركة الاختبار");
  await admin.getByLabel(/رقم التسجيل الضريبي/).fill(`1008${stamp}`);
  await admin.getByRole("button", { name: "إضافة العميل" }).click();
  await expect(admin.getByRole("heading", { name })).toBeVisible();

  await admin.getByLabel("الرمز", { exact: true }).fill("DXB-1");
  await admin.getByLabel("الاسم", { exact: true }).first().fill("دبي");
  await admin.getByLabel("الإمارة").selectOption({ label: "دبي" });
  await admin.getByRole("button", { name: "إضافة منشأة" }).click();
  await expect(admin.getByRole("cell", { name: "DXB-1" })).toBeVisible();

  await admin.getByLabel("ساري من").fill("2018-01-01");
  await admin.getByLabel("الرمز في نظام تخطيط الموارد").fill("S5");
  await admin.getByRole("button", { name: "نشر", exact: true }).click();
  await expect(admin.getByText("الإصدار 1")).toBeVisible();

  const href = await admin.getByRole("link", { name: /2027-01-01/ }).getAttribute("href");

  const preparer = await as(browser, "preparer", `${href}/data`);
  await preparer.getByRole("button", { name: "العربية" }).click();
  await preparer.getByLabel(/^الملف/).setInputFiles(csvFile(`ar-${stamp}.csv`,
    "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type\n" +
    "TAX_INVOICE,INV-1,2027-01-05,10000.00,500.00,S5,DXB-1,100000000000901,B2B\n"));
  await preparer.getByRole("button", { name: "تحميل الملف" }).click();
  await expect(preparer.getByRole("status").filter({ hasText: `ar-${stamp}.csv` })).toContainText("تم تحميل 1");

  await preparer.getByRole("link", { name: "الإقرار", exact: true }).click();
  await preparer.getByRole("button", { name: "إعادة الاحتساب الآن" }).click();
  const row = preparer.getByRole("row").filter({ has: preparer.getByRole("button", { name: "1b", exact: true }) });
  // Figures stay in Latin digits so they match EmaraTax and the source documents.
  await expect(row).toContainText("10,000.00");
  await expect(row).toContainText("دبي");

  await preparer.getByRole("link", { name: "المراجعة والاعتماد" }).click();
  await preparer.getByRole("button", { name: "إرسال للمراجعة" }).click();
  await expect(preparer.getByText("قيد المراجعة").first()).toBeVisible();

  for (const [path, label] of TABS) {
    await preparer.goto(`${href}${path}`);
    await expect(preparer.getByRole("link", { name: label, exact: true })).toBeVisible();
    await preparer.waitForLoadState("networkidle");
    await assertNoRawKeys(preparer, `tab ${label}`);
    await assertNoSidewaysScroll(preparer, `tab ${label}`);
  }
  await preparer.goto("/");
  await assertNoRawKeys(preparer, "portfolio");
  await preparer.goto("/deadlines");
  await assertNoRawKeys(preparer, "deadlines");

  // The narrow layout works too.
  await preparer.setViewportSize({ width: 390, height: 800 });
  for (const [path, label] of TABS) {
    await preparer.goto(`${href}${path}`);
    await preparer.waitForLoadState("networkidle");
    await assertNoSidewaysScroll(preparer, `phone width, tab ${label}`);
  }
});
