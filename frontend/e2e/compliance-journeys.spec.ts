import { readFileSync } from "node:fs";
import { expect, test, type Browser, type Page } from "@playwright/test";
import { as, box, csvFile, loadFile, pdfFile, section } from "./helpers";

const AR = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_name,counterparty_trn,customer_type\n";
const AP = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_name,counterparty_trn,has_tax_invoice,goods_or_services,agreed_payment_date\n";
const CUSTOMER = "100000000000901";
const SUPPLIER = "100000000000801";

/** A database started before pack 1.1 has only the older pack, so an admin installs the new one first. */
async function ensureCurrentRulePack(browser: Browser) {
  const admin = await as(browser, "admin", "/rule-packs");
  await admin.waitForLoadState("networkidle");
  const install = admin.getByRole("button", { name: "Install it" });
  if (await install.count()) {
    await install.click();
    await expect(install).toHaveCount(0);
  }
  await admin.context().close();
}

async function onboard(browser: Browser, name: string, trn: string) {
  const admin = await as(browser, "admin", "/entities/new");
  await admin.getByLabel("Legal name", { exact: true }).fill(name);
  await admin.getByLabel(/Tax Registration Number/).fill(trn);
  await admin.getByLabel(/First tax period starts/).fill("2027-01-01");
  // These journeys are not about e-invoice matching, so the client is not yet on e-invoicing.
  await admin.getByLabel(/E-invoicing go-live/).fill("");
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
  const first = periods.getByRole("link", { name: /2027-01-01/ });
  await expect(first).toBeVisible();
  const href = (await first.getAttribute("href"))!;
  const entityHref = admin.url().replace(/^https?:\/\/[^/]+/, "");
  await admin.context().close();
  return { href, entityHref };
}

/** Takes one period from data to filed, as the three roles would. */
async function fileReturn(browser: Browser, href: string, stamp: string, data: { ar?: string; ap?: string; gl: string }) {
  const preparer = await as(browser, "preparer", `${href}/data`);
  if (data.ar) await loadFile(preparer, "Sales (AR)", csvFile(`ar-${stamp}.csv`, data.ar));
  if (data.ap) await loadFile(preparer, "Purchases (AP)", csvFile(`ap-${stamp}.csv`, data.ap));
  await loadFile(preparer, "GL control balances", csvFile(`gl-${stamp}.csv`, data.gl));
  await preparer.goto(`${href}/review`);
  await preparer.getByRole("button", { name: "Submit for review" }).click();
  await expect(preparer.getByText("In review").first()).toBeVisible();
  const reviewer = await as(browser, "reviewer", `${href}/review`);
  await reviewer.getByRole("button", { name: "Sign off review" }).click();
  await expect(reviewer.getByText(/Signed by Rami Reviewer/)).toBeVisible();
  const approver = await as(browser, "approver", `${href}/review`);
  await approver.getByRole("button", { name: "Approve and lock" }).click();
  await expect(approver.getByRole("button", { name: "Withdraw approval" })).toBeVisible();
  await reviewer.goto(`${href}/filing`);
  await reviewer.getByLabel("Submission reference").fill(`EMT-${stamp}`);
  await section(reviewer, "EmaraTax submission").getByLabel("Date").fill("2027-02-20");
  await reviewer.getByLabel("Acknowledgement file").setInputFiles(pdfFile("ack.pdf"));
  await reviewer.getByRole("button", { name: "Record filing" }).click();
  await expect(reviewer.getByText(`Filed on 2027-02-20, reference EMT-${stamp}.`)).toBeVisible();
  return { preparer, reviewer, approver };
}

function saved(page: Page, name: string | RegExp) {
  return Promise.all([page.waitForEvent("download"), page.getByRole("button", { name }).click()]).then(([d]) => d.path());
}

test("an error found after filing runs through Form 211 and its penalty exposure", async ({ browser }) => {
  test.setTimeout(240_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const { href } = await onboard(browser, `Disclosure Client ${stamp}`, `1007${stamp}`);
  const { preparer, reviewer, approver } = await fileReturn(browser, href, stamp, {
    ar: AR + `TAX_INVOICE,INV-1,2027-01-05,10000.00,500.00,S5,DXB-1,Customer,${CUSTOMER},B2B\n`,
    gl: "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,500.00\n",
  });

  await expect(section(reviewer, "Penalty exposure")).toContainText("129 of 2025");

  await preparer.goto(`${href}/data`);
  await loadFile(preparer, "Sales (AR)", csvFile(`big-${stamp}.csv`, AR + `TAX_INVOICE,INV-BIG,2027-01-21,400000.00,20000.00,S5,DXB-1,Customer,${CUSTOMER},B2B\n`));

  await reviewer.goto(`${href}/corrections`);
  await reviewer.getByLabel("Reason").fill("A large invoice was left out");
  await reviewer.getByRole("button", { name: "Open correction" }).click();

  const card = section(reviewer, "Voluntary disclosure");
  await card.getByLabel("Date the error was discovered").fill("2026-10-01");
  await card.getByLabel("Nature of the error").fill("Invoice INV-BIG was left out of the January return");
  await card.getByRole("button", { name: "Draft the disclosure" }).click();
  await expect(card).toContainText("Tax difference: AED 20,000.00");
  await expect(card).toContainText("Voluntary disclosure, monthly on the tax difference");
  await expect(card.getByRole("button", { name: "Approve" })).toHaveCount(0);

  await card.getByRole("button", { name: "Generate the pack" }).click();
  await expect(card.getByRole("button", { name: "form-211-en.pdf" })).toBeVisible();
  const pdf = await saved(reviewer, "form-211-en.pdf");
  expect(readFileSync(pdf).subarray(0, 4).toString()).toBe("%PDF");

  await approver.goto(`${href}/corrections`);
  await section(approver, "Voluntary disclosure").getByRole("button", { name: "Approve" }).click();
  await expect(section(approver, "Voluntary disclosure").getByText("Approved", { exact: true })).toBeVisible();

  await reviewer.reload();
  const again = section(reviewer, "Voluntary disclosure");
  await again.getByLabel("Submission reference").fill(`VD-${stamp}`);
  await again.getByLabel("Date").fill("2026-10-05");
  await again.getByLabel("Acknowledgement file").setInputFiles(pdfFile("vd-ack.pdf"));
  await again.getByRole("button", { name: "Record filing" }).click();
  await expect(again.getByText(`reference VD-${stamp}`)).toBeVisible();
});

test("a refundable return runs through the pack to the money arriving", async ({ browser }) => {
  test.setTimeout(180_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const { href } = await onboard(browser, `Refund Client ${stamp}`, `1006${stamp}`);
  const { reviewer } = await fileReturn(browser, href, stamp, {
    ap: AP + `TAX_INVOICE,BILL-1,2027-01-08,4000.00,200.00,S5,DXB-1,Good Supplier,${SUPPLIER},true,SERVICES,2027-02-08\n`,
    gl: "account_code,account_type,period_end,balance_aed\n1201,INPUT,2027-01-31,200.00\n",
  });

  await reviewer.goto(`${href}/refund`);
  await expect(reviewer.getByText("Excess recoverable tax")).toBeVisible();
  await reviewer.getByRole("button", { name: "Start the claim" }).click();
  const request = section(reviewer, "Refund request");
  await request.getByRole("button", { name: "Generate the pack" }).click();
  await expect(request.getByRole("button", { name: "refund-request-en.pdf" })).toBeVisible();

  await request.getByLabel("Submission reference").fill(`R-${stamp}`);
  await request.getByLabel("Date").fill("2027-03-01");
  await request.getByLabel("Acknowledgement file").setInputFiles(pdfFile("refund-ack.pdf"));
  await request.getByRole("button", { name: "Record filing" }).click();
  await expect(request.getByText("Submitted", { exact: true })).toBeVisible();

  await request.getByLabel("Date").fill("2027-03-20");
  await request.getByRole("button", { name: "Save" }).click();
  await expect(request.getByText("Approved", { exact: true })).toBeVisible();

  await request.getByLabel("Date").fill("2027-03-25");
  await request.getByLabel("Amount received").fill("200.00");
  await request.getByRole("button", { name: "Save" }).click();
  await expect(request.getByText("AED 200.00 received on 2027-03-25.")).toBeVisible();
});

test("an FTA audit runs from notice to a bundle, and the file follows the published layout", async ({ browser }) => {
  test.setTimeout(240_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const name = `Audit Client ${stamp}`;
  const { href, entityHref } = await onboard(browser, name, `1005${stamp}`);
  const { preparer, reviewer } = await fileReturn(browser, href, stamp, {
    ar: AR + `TAX_INVOICE,INV-1,2027-01-05,10000.00,500.00,S5,DXB-1,"Customer, LLC",${CUSTOMER},B2B\n`,
    gl: "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,500.00\n",
  });
  await preparer.goto(`${href}/data`);
  await loadFile(preparer, "GL journal lines", csvFile(`journal-${stamp}.csv`, "transaction_date,account_id,transaction_id,debit,credit\n2027-01-05,1100,TX-1,10500.00,0.00\n2027-01-05,4000,TX-1b,0.00,10500.00\n"));

  await reviewer.goto(`${entityHref}/audit-cases`);
  const open = section(reviewer, "Audit cases");
  await open.getByLabel("FTA reference").fill(`FTA-${stamp}`);
  await open.getByLabel("Notice date").fill("2027-06-01");
  await open.getByLabel("From").fill("2027-01-01");
  await open.getByLabel("To", { exact: true }).fill("2027-01-31");
  await open.getByRole("button", { name: "Open the case" }).click();
  await open.getByRole("link", { name: `FTA-${stamp}` }).click();
  await expect(reviewer.getByText("1 period(s) on legal hold")).toBeVisible();

  const requests = section(reviewer, "What the FTA has asked for");
  await requests.getByLabel("What they asked for").fill("Sales listing for January");
  await requests.getByLabel("Due").fill("2027-06-20");
  await requests.getByRole("button", { name: "Add the request" }).click();
  await requests.getByLabel("What was given to the FTA").fill("Sent the audit file");
  await requests.getByRole("button", { name: "Record the answer" }).click();
  await expect(requests.getByText("Answered")).toBeVisible();

  const docs = section(reviewer, "What goes to the auditor");
  await docs.getByRole("button", { name: "Generate the audit file" }).click();
  const fafButton = docs.getByRole("button", { name: /^FAF_/ });
  await expect(fafButton).toBeVisible();
  const faf = readFileSync(await saved(reviewer, /^FAF_/)).toString();
  expect(faf).toContain("CompInfoStart");
  expect(faf).toContain("Customer; LLC");
  expect(faf).toContain("10500.00,10500.00,2,AED");

  await docs.getByRole("button", { name: "Make the bundle" }).click();
  await expect(docs.getByRole("button", { name: /^audit-bundle-/ })).toBeVisible();
  const zip = readFileSync(await saved(reviewer, /^audit-bundle-/));
  expect(zip.subarray(0, 2).toString()).toBe("PK");

  const partner = await as(browser, "partner", reviewer.url().replace(/^https?:\/\/[^/]+/, ""));
  const close = section(partner, "Close the case");
  await close.getByLabel("Outcome").fill("No findings");
  await close.getByRole("button", { name: "Close", exact: true }).click();
  await expect(partner.getByText("No findings")).toBeVisible();
});

test("a bank statement settles a filed return, and analytics and practice views show the work", async ({ browser }) => {
  test.setTimeout(240_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const giban = `AE99FTA${stamp}`;
  const name = `Bank Client ${stamp}`;
  const { href, entityHref } = await onboard(browser, name, `1004${stamp}`);

  const admin = await as(browser, "admin", entityHref);
  const settings = section(admin, "Filing settings");
  await settings.getByLabel("FTA payment GIBAN").fill(giban);
  await settings.getByRole("button", { name: "Save" }).click();
  await expect(settings.getByText("Saved.")).toBeVisible();

  const { preparer, reviewer } = await fileReturn(browser, href, stamp, {
    ar: AR + `TAX_INVOICE,INV-1,2027-01-05,10000.00,500.00,S5,DXB-1,Customer,${CUSTOMER},B2B\n`,
    gl: "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,500.00\n",
  });

  await preparer.goto(`${href}/data`);
  const statement = `:20:S1\n:25:AE07\n:60F:C270101AED100000,00\n:61:2702250225D500,00NTRFVAT\n:86:FTA ${giban} VAT JAN\n:62F:C270331AED99500,00\n-\n`;
  const bankForm = preparer.locator("form").filter({ has: preparer.getByRole("heading", { name: "Bank statement" }) });
  await bankForm.getByLabel("Statement file").setInputFiles({ name: `stmt-${stamp}.sta`, mimeType: "text/plain", buffer: Buffer.from(statement) });
  await bankForm.getByRole("button", { name: "Load statement" }).click();
  await expect(bankForm.getByRole("status")).toContainText("1 return(s) settled");
  await bankForm.getByRole("button", { name: `Show the lines of stmt-${stamp}.sta` }).click();
  await expect(bankForm.getByRole("row").filter({ hasText: giban })).toContainText("Money out");

  await reviewer.goto(`${href}/filing`);
  await expect(reviewer.getByText("Paid", { exact: true }).first()).toBeVisible();

  // The trends chart and its table both show the period.
  await reviewer.goto(`${entityHref}/analytics`);
  await expect(reviewer.getByRole("heading", { name: "Output and recoverable tax" })).toBeVisible();
  await expect(reviewer.locator("svg").first()).toBeVisible();
  await expect(section(reviewer, "The figures")).toContainText("2027-01");

  // The firm sees who is on what, and the client shows as unassigned until someone is.
  await admin.goto("/practice");
  await expect(section(admin, "Workload").getByRole("link", { name })).toBeVisible();
  await admin.goto(entityHref);
  const assignments = section(admin, "Who looks after this client");
  await assignments.getByLabel("Person").selectOption({ index: 1 });
  await assignments.getByRole("button", { name: "Assign" }).click();
  await expect(assignments.getByRole("button", { name: "Remove" })).toBeVisible();
  await admin.goto("/practice");
  await expect(section(admin, "Workload").getByRole("link", { name })).toHaveCount(0);

  const watch = section(admin, "VAT registration threshold");
  await watch.getByLabel("Add a client to watch").fill(`Prospect ${stamp}`);
  await watch.getByRole("button", { name: "Add" }).click();
  await watch.getByRole("combobox", { name: "Client" }).selectOption({ label: `Prospect ${stamp}` });
  await watch.getByLabel("Month", { exact: true }).fill("2027-01-01");
  await watch.getByLabel("Taxable supplies").fill("400000.00");
  await watch.getByRole("button", { name: "Save" }).click();
  await expect(watch.getByRole("row").filter({ hasText: `Prospect ${stamp}` })).toContainText("Must register");
});

test("an unclassified line gets a suggestion, and nothing changes until a person accepts it", async ({ browser }) => {
  test.setTimeout(120_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const { href } = await onboard(browser, `Suggest Client ${stamp}`, `1003${stamp}`);
  const preparer = await as(browser, "preparer", `${href}/data`);
  await loadFile(preparer, "Sales (AR)", csvFile(`ar-${stamp}.csv`,
    "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,description,customer_type\nTAX_INVOICE,K-1,2027-01-10,5000.00,0.00,UNMAPPED,DXB-1,Sale of a residential flat,B2C\n"));

  await preparer.goto(`${href}/suggestions`);
  await preparer.getByRole("button", { name: "Look for suggestions" }).click();
  const row = preparer.getByRole("row").filter({ hasText: "K-1" });
  await expect(row).toContainText("Exempt");
  await expect(row).toContainText("60%");

  await preparer.goto(href);
  await preparer.getByRole("button", { name: "Recompute now" }).click();
  await expect(box(preparer, "5")).toContainText("0.00");

  await preparer.goto(`${href}/suggestions`);
  await preparer.getByRole("row").filter({ hasText: "K-1" }).getByRole("checkbox").check();
  await preparer.getByRole("button", { name: /Accept the 1 chosen/ }).click();
  await expect(preparer.getByText("No suggestions waiting.")).toBeVisible();

  await preparer.goto(href);
  await preparer.getByRole("button", { name: "Recompute now" }).click();
  await expect(box(preparer, "5")).toContainText("5,000.00");
});

test("an invoice from another provider stays not reported until someone enters its status", async ({ browser }) => {
  test.setTimeout(120_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const trn = `1005${stamp}`;
  const { href } = await onboard(browser, `Other Provider ${stamp}`, trn);

  const preparer = await as(browser, "preparer", `${href}/data`);
  const number = `OP-${stamp}`;
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
      xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
      xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
      <cbc:ID>${number}</cbc:ID><cbc:UUID>${crypto.randomUUID()}</cbc:UUID><cbc:IssueDate>2027-01-10</cbc:IssueDate>
      <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cbc:DocumentCurrencyCode>AED</cbc:DocumentCurrencyCode>
      <cac:AccountingSupplierParty><cac:Party><cbc:EndpointID schemeID="0235">${trn.slice(0, 10)}</cbc:EndpointID>
        <cac:PartyTaxScheme><cbc:CompanyID>${trn}</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme></cac:Party></cac:AccountingSupplierParty>
      <cac:AccountingCustomerParty><cac:Party><cac:PartyTaxScheme><cbc:CompanyID>${CUSTOMER}</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme></cac:Party></cac:AccountingCustomerParty>
      <cac:TaxTotal><cbc:TaxAmount currencyID="AED">50.00</cbc:TaxAmount></cac:TaxTotal>
      <cac:LegalMonetaryTotal><cbc:TaxExclusiveAmount currencyID="AED">1000.00</cbc:TaxExclusiveAmount></cac:LegalMonetaryTotal>
    </Invoice>`;
  const form = preparer.locator("form").filter({ has: preparer.getByRole("heading", { name: "E-invoices from another provider" }) });
  await form.getByLabel("XML or ZIP file").setInputFiles({ name: `einvoice-${stamp}.xml`, mimeType: "application/xml", buffer: Buffer.from(xml) });
  await form.getByRole("button", { name: "Load e-invoices" }).click();

  const row = preparer.getByRole("row").filter({ hasText: number });
  await expect(row).toContainText("Not yet reported");
  await row.getByRole("button", { name: `Mark ${number} as reported` }).click();
  await expect(row).toContainText("Reported to FTA");
  await expect(row.getByRole("button", { name: `Mark ${number} as reported` })).toHaveCount(0);
});

test("a relieved debt is paid back when money arrives, and a capital asset is disposed of", async ({ browser }) => {
  test.setTimeout(180_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const { href, entityHref } = await onboard(browser, `Schemes Client ${stamp}`, `1006${stamp}`);
  const admin = await as(browser, "admin", entityHref);
  const periods = section(admin, "Tax periods");
  await periods.getByLabel("Add periods through").fill("2027-12-31");
  await periods.getByRole("button", { name: "Add periods" }).click();
  const august = periods.getByRole("link", { name: /2027-08-01/ });
  await expect(august).toBeVisible();
  const augustHref = (await august.getAttribute("href"))!;
  const septemberHref = (await periods.getByRole("link", { name: /2027-09-01/ }).getAttribute("href"))!;

  const { reviewer, approver } = await fileReturn(browser, href, stamp, {
    ar: AR + `TAX_INVOICE,BD-1,2027-01-05,10000.00,500.00,S5,DXB-1,Customer,${CUSTOMER},B2B\n`,
    gl: "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,500.00\n",
  });

  await reviewer.goto(`${augustHref}/schemes`);
  const relief = section(reviewer, "Bad debt relief");
  await relief.getByLabel("Sales invoice").selectOption({ index: 1 });
  await relief.getByLabel("Amount written off, with VAT").fill("10500.00");
  await relief.getByLabel("Written off on").fill("2027-07-31");
  await relief.getByLabel("Customer told on").fill("2027-08-01");
  await relief.getByLabel("Evidence of the write-off").setInputFiles(pdfFile("writeoff.pdf"));
  await relief.getByLabel("Notice to the customer").setInputFiles(pdfFile("notice.pdf"));
  await relief.getByLabel("Reason").fill("Customer in liquidation");
  await relief.getByRole("button", { name: "Propose the relief" }).click();
  await expect(relief.getByRole("cell", { name: "Relief", exact: true })).toBeVisible();

  await approver.goto(`${augustHref}/adjustments`);
  await approver.getByRole("button", { name: "Approve", exact: true }).first().click();
  await expect(approver.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);

  await reviewer.goto(`${septemberHref}/schemes`);
  const recovery = reviewer.locator("form").filter({ has: reviewer.getByRole("heading", { name: "Money received on a relieved debt" }) });
  await recovery.getByLabel("Relieved invoice").selectOption({ index: 1 });
  await recovery.getByLabel(/Amount received/).fill("2100.00");
  await recovery.getByLabel("Proof of receipt").setInputFiles(pdfFile("receipt.pdf"));
  await recovery.getByLabel("Reason").fill("Dividend from the liquidator");
  await recovery.getByRole("button", { name: "Record the money received" }).click();
  await expect(section(reviewer, "Bad debt relief").getByRole("cell", { name: "Debt recovered" })).toBeVisible();

  const reference = `TWR-${stamp}`;
  await reviewer.goto(entityHref);
  const register = section(reviewer, "Capital assets");
  await register.getByLabel("Code").fill(reference);
  await register.getByLabel("Description").fill("Office tower");
  await register.getByLabel("Cost", { exact: true }).fill("6000000.00");
  await register.getByLabel("Input VAT", { exact: true }).fill("300000.00");
  await register.getByLabel(/Recovered at first/).fill("50");
  await register.getByLabel("First used on").fill("2027-01-15");
  await register.getByRole("button", { name: "Add the asset" }).click();
  await expect(register.getByRole("row").filter({ hasText: reference })).toBeVisible();

  await reviewer.goto(`${septemberHref}/schemes`);
  const disposal = reviewer.locator("form").filter({ has: reviewer.getByRole("heading", { name: "Sold or no longer used" }) });
  await disposal.getByRole("combobox", { name: "Asset" }).selectOption({ index: 1 });
  await disposal.getByLabel(/Date, within this period/).fill("2027-09-15");
  await disposal.getByLabel("The disposal is taxable").check();
  await disposal.getByRole("button", { name: "Record the disposal" }).click();
  await expect(disposal).toHaveCount(0);
  await reviewer.goto(entityHref);
  await expect(section(reviewer, "Capital assets").getByRole("row").filter({ hasText: reference })).toContainText("Disposed of");
});

test("the firm gives access, keeps its supplier register and marks long-retention periods", async ({ browser }) => {
  test.setTimeout(120_000);
  await ensureCurrentRulePack(browser);
  const stamp = String(Date.now()).slice(-11);
  const name = `Access Client ${stamp}`;
  const { entityHref } = await onboard(browser, name, `1007${stamp}`);
  const admin = await as(browser, "admin", entityHref);

  const access = section(admin, "Access");
  await access.getByLabel("Person").selectOption({ index: 1 });
  await access.getByLabel("Role").selectOption({ label: "Tax reviewer" });
  await access.getByRole("button", { name: "Give access" }).click();
  const granted = access.getByRole("button", { name: /^Remove Tax reviewer access/ });
  await expect(granted).toBeVisible();
  await granted.click();
  await expect(granted).toHaveCount(0);

  await admin.goto("/practice");
  const register = section(admin, "Supplier TRN register");
  const supplier = `Supplier ${stamp}`;
  await register.getByLabel("TRN").fill(`1008${stamp}`);
  await register.getByLabel("Name", { exact: true }).fill(supplier);
  await register.getByRole("button", { name: "Save" }).click();
  const entry = register.getByRole("row").filter({ hasText: supplier });
  await expect(entry).toContainText("Active");
  await entry.getByRole("button", { name: `Edit ${supplier}` }).click();
  await register.getByLabel("Status in the register").selectOption({ label: "Inactive" });
  await register.getByRole("button", { name: "Save" }).click();
  await expect(entry).toContainText("Inactive");

  const feed = section(admin, "Exchange rate feed");
  await feed.getByRole("button", { name: "Pull rates now" }).click();
  await expect(feed.getByRole("alert")).toContainText("No FX feed is configured");

  const retention = section(admin, "Record retention");
  await retention.getByLabel("Client").fill(name);
  await retention.getByRole("button", { name: "Find" }).click();
  const period = retention.getByRole("row").filter({ hasText: name }).first();
  await period.getByRole("button", { name: "Mark as real estate" }).click();
  await period.getByLabel("Reason").fill("Holds records of a property sale");
  await period.getByRole("button", { name: "Mark as real estate" }).click();
  await expect(period).toContainText("Real estate");
  await expect(period.getByRole("button", { name: "Mark as standard" })).toBeVisible();
});
