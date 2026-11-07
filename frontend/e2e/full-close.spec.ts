import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { as, box, csvFile, loadFile, pdfFile, publishEinvoices, reconcileUntil, section } from "./helpers";

const HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_name,counterparty_trn,customer_type,goods_or_services,agreed_payment_date,has_tax_invoice\n";
const CUSTOMER = "100000000000901";
const SUPPLIER = "100000000000801";

/**
 * The main journey from spec section 2, in a real browser against the real services: onboard, load,
 * reconcile, see approval blocked, fix, approve, produce the pack, file, pay, then correct an error.
 */
test("a full period close from onboarding to correction", async ({ browser }) => {
  test.setTimeout(420_000);
  const stamp = String(Date.now()).slice(-11);
  const trn = `1009${stamp}`;
  const name = `E2E Trading ${stamp}`;

  // --- Firm admin onboards the client ---
  const admin = await as(browser, "admin", "/entities/new");
  await admin.getByLabel("Legal name", { exact: true }).fill(name);
  await admin.getByLabel(/Tax Registration Number/).fill(trn);
  await admin.getByLabel(/First tax period starts/).fill("2027-01-01");
  await admin.getByLabel(/E-invoicing go-live/).fill("2027-01-01");
  await admin.getByRole("button", { name: "Add client" }).click();
  await expect(admin.getByRole("heading", { name })).toBeVisible();

  const establishments = section(admin, "Establishments");
  await establishments.getByLabel("Code").fill("DXB-1");
  await establishments.getByLabel("Name", { exact: true }).fill("Dubai office");
  await establishments.getByLabel("Emirate").selectOption({ label: "Dubai" });
  await establishments.getByRole("button", { name: "Add establishment" }).click();
  await expect(establishments.getByRole("cell", { name: "Dubai office" })).toBeVisible();

  const mapping = section(admin, "Tax code mapping");
  await mapping.getByLabel("Effective from").fill("2018-01-01");
  await mapping.getByLabel("Code in the ERP").fill("S5");
  await mapping.getByRole("button", { name: "Publish" }).click();
  await expect(mapping.getByText("Version 1")).toBeVisible();

  const periods = section(admin, "Tax periods");
  await periods.getByRole("button", { name: "Add periods" }).click();
  const firstPeriod = periods.getByRole("link", { name: /2027-01-01/ });
  await expect(firstPeriod).toBeVisible();
  const href = await firstPeriod.getAttribute("href");
  const nextHref = await periods.getByRole("link", { name: /2027-02-01/ }).getAttribute("href");
  await expect(periods.getByRole("row").filter({ hasText: "2027-01-01" })).toContainText("2027-02-28");

  // --- Preparer loads data. One row is bad and must not disappear silently ---
  const preparer = await as(browser, "preparer", `${href}/data`);
  const sales =
    HEAD +
    `TAX_INVOICE,INV-1,2027-01-05,10000.00,500.00,S5,DXB-1,Customer One,${CUSTOMER},B2B,GOODS,,\n` +
    `TAX_INVOICE,INV-2,2027-01-12,20000.00,1000.00,S5,DXB-1,Customer Two,${CUSTOMER},B2B,SERVICES,,\n`;
  await loadFile(preparer, "Sales (AR)", csvFile(`ar-${stamp}.csv`, sales + `TAX_INVOICE,INV-BAD,2027-01-13,abc,1.00,S5,DXB-1,Customer,${CUSTOMER},B2B,GOODS,,\n`));
  await expect(preparer.getByRole("status").filter({ hasText: "2 loaded" })).toContainText("1 rejected");
  await loadFile(preparer, "Purchases (AP)", csvFile(`ap-${stamp}.csv`, HEAD + `TAX_INVOICE,BILL-1,2027-01-08,4000.00,200.00,S5,DXB-1,Good Supplier,${SUPPLIER},,SERVICES,2027-02-08,true\n`));
  await loadFile(preparer, "GL control balances", csvFile(`gl-${stamp}.csv`, "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,1500.00\n1201,INPUT,2027-01-31,200.00\n"));

  // --- The return reflects the loaded data, with drill-down to the source row ---
  await preparer.getByRole("link", { name: "Return", exact: true }).click();
  await preparer.getByRole("button", { name: "Recompute now" }).click();
  await expect(box(preparer, "1b")).toContainText("30,000.00");
  await expect(box(preparer, "1b")).toContainText("1,500.00");
  await expect(box(preparer, "14")).toContainText("1,300.00");
  await box(preparer, "1b").getByRole("button", { name: "1b" }).click();
  await expect(preparer.getByText("Output section, row 1b")).toBeVisible();
  const source = preparer.getByRole("button", { name: new RegExp(`ar-${stamp}.csv #2`) });
  await source.click();
  await expect(preparer.getByText(`From ar-${stamp}.csv, row 2.`)).toBeVisible();

  // --- Reconciliation finds what is wrong ---
  await preparer.getByRole("link", { name: "Exceptions" }).click();
  await preparer.getByRole("button", { name: "Run reconciliation" }).click();
  await expect(preparer.getByRole("status").filter({ hasText: "stop approval" })).toBeVisible();
  await expect(preparer.getByRole("cell", { name: /R01/ }).first()).toBeVisible();
  await expect(preparer.getByRole("cell", { name: /D02/ })).toBeVisible();

  // --- Approval is refused while blocking exceptions are open ---
  await preparer.getByRole("link", { name: "Review and approve" }).click();
  await preparer.getByRole("button", { name: "Submit for review" }).click();
  await expect(preparer.getByText("In review").first()).toBeVisible();
  const reviewer = await as(browser, "reviewer", `${href}/review`);
  await reviewer.getByRole("button", { name: "Sign off review" }).click();
  await expect(reviewer.getByText(/Signed by Rami Reviewer/)).toBeVisible();
  const approver = await as(browser, "approver", `${href}/review`);
  await expect(approver.getByText("This return cannot be approved yet")).toBeVisible();
  await approver.getByRole("button", { name: "Approve and lock" }).click();
  await expect(approver.getByRole("alert")).toContainText("blocking exception");
  await expect(approver.getByText("In review").first()).toBeVisible();

  // --- The ASP reports the e-invoices, and the preparer reloads the bad row ---
  publishEinvoices(trn, sales);
  await preparer.getByRole("link", { name: "Data", exact: true }).click();
  await loadFile(preparer, "Sales (AR)", csvFile(`ar-fix-${stamp}.csv`, HEAD + `TAX_INVOICE,INV-3,2027-01-13,0.00,0.00,S5,DXB-1,Customer,${CUSTOMER},B2B,GOODS,,\n`));
  publishEinvoices(trn, HEAD + `TAX_INVOICE,INV-3,2027-01-13,0.00,0.00,S5,DXB-1,Customer,${CUSTOMER},B2B,GOODS,,\n`);

  // The bad row itself is waived with a reason, as a reviewer would for a row that was a typo.
  await reviewer.goto(`${href}/exceptions`);
  await reconcileUntil(reviewer, async () => (await reviewer.getByRole("cell", { name: /R01/ }).count()) === 0);
  const d02 = reviewer.getByRole("row").filter({ hasText: "D02" });
  await d02.getByRole("button", { name: "Waive" }).click();
  await d02.getByLabel("Reason").fill("Typo in the export. The row was a duplicate of INV-3.");
  await d02.getByRole("button", { name: "Waive" }).click();
  await expect(reviewer.getByText("No blocking exceptions.")).toBeVisible();

  // New data arrived, so the earlier sign-offs were cancelled and the return is open again.
  await reviewer.goto(`${href}/review`);
  await expect(reviewer.getByText("Cancelled").first()).toBeVisible();
  await expect(reviewer.getByText("The data changed after sign-off").first()).toBeVisible();

  // --- Round two: submit, review, approve ---
  await preparer.goto(`${href}/review`);
  await preparer.getByRole("button", { name: "Submit for review" }).click();
  await expect(preparer.getByText("In review").first()).toBeVisible();
  await expect(preparer.getByRole("button", { name: "Approve and lock" })).toHaveCount(0);
  await reviewer.goto(`${href}/review`);
  await reviewer.getByRole("button", { name: "Sign off review" }).click();
  await expect(reviewer.getByText(/Signed by Rami Reviewer/)).toBeVisible();
  await approver.goto(`${href}/review`);
  await approver.getByRole("button", { name: "Approve and lock" }).click();
  await expect(approver.getByText("Approved", { exact: true }).first()).toBeVisible();
  await expect(approver.getByRole("button", { name: "Withdraw approval" })).toBeVisible();

  // --- A locked return offers no way to change it ---
  await preparer.goto(`${href}/lines`);
  await expect(preparer.getByRole("checkbox")).toHaveCount(0);

  // --- Reviewer produces the pack: watermarked, because the rule pack is still draft ---
  await reviewer.goto(`${href}/filing`);
  await reviewer.getByRole("button", { name: "Generate pack" }).click();
  await expect(reviewer.getByRole("status").filter({ hasText: "NOT FOR FILING" })).toBeVisible();
  const [download] = await Promise.all([reviewer.waitForEvent("download"), reviewer.getByRole("button", { name: "PDF", exact: true }).click()]);
  const pdfPath = await download.path();
  expect(readFileSync(pdfPath).subarray(0, 4).toString()).toBe("%PDF");
  await download.saveAs(`test-results/return-pack-${stamp}.pdf`);
  const [arabic] = await Promise.all([reviewer.waitForEvent("download"), reviewer.getByRole("button", { name: "PDF in Arabic" }).click()]);
  expect(readFileSync(await arabic.path()).subarray(0, 4).toString()).toBe("%PDF");
  await arabic.saveAs(`test-results/return-pack-${stamp}-ar.pdf`);

  // --- Records the EmaraTax submission, then the payment, matched to the bank ---
  await reviewer.getByLabel("Submission reference").fill(`EMT-${stamp}`);
  await section(reviewer, "EmaraTax submission").getByLabel("Date").fill("2027-02-20");
  await reviewer.getByLabel("Acknowledgement file").setInputFiles(pdfFile("ack.pdf"));
  await reviewer.getByRole("button", { name: "Record filing" }).click();
  await expect(reviewer.getByText(`Filed on 2027-02-20, reference EMT-${stamp}.`)).toBeVisible();

  const payment = section(reviewer, "Payment");
  await payment.getByLabel("GIBAN payment reference").fill("AE070331234567890123456");
  await payment.getByLabel("Amount paid (AED)").fill("1300.00");
  await payment.getByLabel("Date").fill("2027-02-25");
  await payment.getByLabel("Reference on the bank confirmation").fill("AE070331234567890123456");
  await payment.getByLabel("Amount on the bank confirmation").fill("1300.00");
  await payment.getByLabel("Bank confirmation file").setInputFiles(pdfFile("bank.pdf"));
  await payment.getByRole("button", { name: "Record payment" }).click();
  await expect(payment.getByText("Matched")).toBeVisible();
  await expect(reviewer.getByText("Paid", { exact: true }).first()).toBeVisible();

  // --- A prior-period error under AED 10,000 goes to the next return ---
  await preparer.goto(`${href}/data`);
  await loadFile(preparer, "Sales (AR)", csvFile(`late-${stamp}.csv`, HEAD + `TAX_INVOICE,INV-LATE,2027-01-20,1000.00,50.00,S5,DXB-1,Customer,${CUSTOMER},B2B,GOODS,,\n`));
  await reviewer.goto(`${href}/corrections`);
  await reviewer.getByRole("button", { name: "See the outcome" }).click();
  const outcome = reviewer.getByRole("status").filter({ hasText: "Tax difference" });
  await expect(outcome).toContainText("Correct in the next return");
  await expect(outcome).toContainText("Tax difference AED 50.00");
  await reviewer.getByLabel("Reason").fill("Invoice INV-LATE was left out of the January export");
  await reviewer.getByRole("button", { name: "Open correction" }).click();
  await expect(reviewer.getByRole("link", { name: "Open the next return" })).toBeVisible();

  await reviewer.goto(nextHref!);
  await reviewer.getByRole("button", { name: "Recompute now" }).click();
  await expect(box(reviewer, "1b")).toContainText("50.00");
  await expect(box(reviewer, "14")).toContainText("50.00");

  // The filed return still shows what was filed.
  await reviewer.goto(href!);
  await expect(box(reviewer, "14")).toContainText("1,300.00");
  await expect(section(reviewer, "Version history")).toContainText("Corrected");

  // --- An error over AED 10,000 needs a voluntary disclosure ---
  await preparer.goto(`${href}/data`);
  await loadFile(preparer, "Sales (AR)", csvFile(`big-${stamp}.csv`, HEAD + `TAX_INVOICE,INV-BIG,2027-01-21,400000.00,20000.00,S5,DXB-1,Customer,${CUSTOMER},B2B,GOODS,,\n`));
  await reviewer.goto(`${href}/corrections`);
  await reviewer.getByRole("button", { name: "See the outcome" }).click();
  await expect(reviewer.getByRole("status").filter({ hasText: "Tax difference" })).toContainText("Voluntary disclosure on Form 211");

  // --- The portfolio shows the client, and the audit chain still verifies ---
  await admin.goto("/");
  await expect(admin.getByRole("row").filter({ hasText: name })).toBeVisible();
  await admin.goto("/audit");
  await expect(admin.getByRole("status").filter({ hasText: "intact" })).toBeVisible();
});
