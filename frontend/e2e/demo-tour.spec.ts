import { expect, test, type Page } from "@playwright/test";
import { as } from "./helpers";

/**
 * Checks the demo data rather than the code: every section of every demo client, and every firm-wide
 * page, has something to show. It reads the same API the screens read, as a signed-in user, and skips
 * itself when the demo clients are not loaded (scripts/reset-demo.sh builds them).
 */

const DEMO = [
  "Al Noor Home Furnishings Trading LLC",
  "Falcon Logistics Holding LLC",
  "Desert Rose Real Estate LLC",
  "Zarina Jewellery & Bullion Trading LLC",
  "Jebel Ali Marine Supplies FZCO",
];

type Json = any; // eslint-disable-line @typescript-eslint/no-explicit-any

async function api(page: Page, path: string): Promise<Json> {
  return page.evaluate(async (p) => {
    const key = Object.keys(sessionStorage).find((k) => k.startsWith("oidc.user:"));
    const token = key ? JSON.parse(sessionStorage.getItem(key) ?? "{}").access_token : "";
    const res = await fetch(`/api${p}`, { headers: { Authorization: `Bearer ${token}` } });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`${p}: HTTP ${res.status}`);
    return res.status === 204 ? null : res.json();
  }, path);
}

const size = (v: Json) => (v == null ? 0 : Array.isArray(v) ? v.length : 1);

async function demoClients(page: Page): Promise<{ entityId: string; legalName: string; entityKind: string }[]> {
  const rows: Json[] = (await api(page, "/portfolio")) ?? [];
  return rows.filter((r) => DEMO.includes(r.legalName));
}

test("every section of every demo client has something to show", async ({ browser }) => {
  test.setTimeout(600_000);
  const page = await as(browser, "reviewer");
  const clients = await demoClients(page);
  test.skip(clients.length < DEMO.length, "The demo clients are not loaded");
  const missing: string[] = [];
  const report: string[] = [];
  for (const c of clients) {
    const id = c.entityId;
    const found: Record<string, number> = {};
    const add = (section: string, n: number) => { found[section] = (found[section] ?? 0) + n; };
    for (const [section, path] of Object.entries({
      periods: `/entities/${id}/periods`, assignments: `/entities/${id}/assignments`, capitalAssets: `/entities/${id}/capital-assets`,
      connectors: `/entities/${id}/connectors`, establishments: `/entities/${id}/establishments`, mappings: `/entities/${id}/mappings`,
      agents: `/entities/${id}/agents`, signatories: `/entities/${id}/signatories`, analytics: `/entities/${id}/analytics/trends`,
      auditCases: `/entities/${id}/audit-cases`, dataLoads: `/entities/${id}/ingest/batches`, bankStatements: `/entities/${id}/bank-statements`,
    })) {
      add(section, size(await api(page, path)));
    }
    add("clientRules", ((await api(page, `/entities/${id}/client-rules`))?.versions ?? []).length);
    add("firmRules", (await api(page, `/entities/${id}/rule-pack`))?.definition?.classification?.rules?.length ?? 0);
    for (const connector of (await api(page, `/entities/${id}/connectors`)) ?? []) {
      add("connectorRuns", size(await api(page, `/connectors/${connector.id}/runs`)));
    }
    if (c.entityKind === "GROUP_REPRESENTATIVE") {
      const members: Json[] = (await api(page, `/entities/${id}/members`)) ?? [];
      add("members", members.length);
      for (const m of members) {
        add("memberEstablishments", size(await api(page, `/entities/${m.id}/establishments`)) > 0 ? 1 : 0);
        add("memberSignatories", size(await api(page, `/entities/${m.id}/signatories`)) > 0 ? 1 : 0);
      }
    }
    for (const auditCase of (await api(page, `/entities/${id}/audit-cases`)) ?? []) {
      const view = await api(page, `/audit-cases/${auditCase.id}`);
      add("auditRequests", view.requests.length);
      add("auditDocuments", view.documents.length);
    }
    const periods: Json[] = (await api(page, `/entities/${id}/periods`)) ?? [];
    for (const p of periods) {
      const pid = p.id;
      const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(p.state);
      const view = await api(page, `/periods/${pid}/return`);
      add("return", view.version ? 1 : 0);
      add("returnVersions", size(await api(page, `/periods/${pid}/versions`)));
      add("lines", (await api(page, `/periods/${pid}/lines?page=0&size=1`)).total);
      add("exceptions", size(await api(page, `/periods/${pid}/exceptions`)));
      add("timingItems", size(await api(page, `/periods/${pid}/timing-items`)));
      add("certificate", (await api(page, `/periods/${pid}/certificate`)).rules.some((r: Json) => r.open + r.resolved + r.waived > 0) ? 1 : 0);
      add("suggestions", size(await api(page, `/periods/${pid}/suggestions?status=PROPOSED`)));
      add("adjustments", size(await api(page, `/periods/${pid}/adjustments`)));
      add("schemes", size(await api(page, `/periods/${pid}/apportionment`)) + size(await api(page, `/periods/${pid}/bad-debt`))
        + (locked ? 0 : size(await api(page, `/periods/${pid}/bad-debt/candidates`)) + size(await api(page, `/periods/${pid}/capital-assets/due`))));
      add("exports", size(await api(page, `/periods/${pid}/exports`)) + size(await api(page, `/periods/${pid}/margin-schedule`)));
      const workflow = await api(page, `/periods/${pid}/workflow`);
      add("review", workflow.signoffs.length);
      add("comments", size(await api(page, `/periods/${pid}/comments?objectType=PERIOD&objectId=${pid}`)));
      if (locked) {
        add("filing", (await api(page, `/periods/${pid}/pack`)) ? 1 : 0);
      }
      const refund = await api(page, `/periods/${pid}/refund`);
      add("refund", refund.request ? 1 : 0);
      add("evidence", size(await api(page, `/entities/${id}/evidence?periodId=${pid}`)));
      add("corrections", size(await api(page, `/periods/${pid}/corrections`)));
    }
    report.push(`${c.legalName}: ${Object.entries(found).map(([k, v]) => `${k} ${v}`).join(", ")}`);
    for (const [section, n] of Object.entries(found)) {
      if (n === 0) missing.push(`${c.legalName}: ${section}`);
    }
  }
  console.log(report.join("\n"));
  expect(missing).toEqual([]);
});

test("every firm-wide page of the demo has something to show", async ({ browser }) => {
  const page = await as(browser, "admin");
  test.skip((await demoClients(page)).length < DEMO.length, "The demo clients are not loaded");
  const workload = await api(page, "/practice/workload");
  const webhooks: Json[] = await api(page, "/practice/webhooks");
  let deliveries = 0;
  for (const w of webhooks) deliveries += size(await api(page, `/practice/webhooks/${w.id}/deliveries`));
  const found: Record<string, number> = {
    portfolio: size(await api(page, "/portfolio")),
    deadlines: size(await api(page, "/deadlines?days=120")),
    alerts: size(await api(page, "/notifications")),
    workload: workload.people.length,
    registrationWatch: size(await api(page, "/registration-watch")),
    people: size(await api(page, "/users")),
    supplierRegister: size(await api(page, "/counterparties")),
    webhooks: webhooks.length,
    webhookDeliveries: deliveries,
    retention: size(await api(page, "/retention?limit=50")),
    rulePacks: size(await api(page, "/rule-packs")),
    clientRules: size(await api(page, "/client-rules")),
    auditLog: size(await api(page, "/audit/events?limit=50")),
  };
  console.log(Object.entries(found).map(([k, v]) => `${k} ${v}`).join(", "));
  expect(Object.entries(found).filter(([, n]) => n === 0).map(([k]) => k)).toEqual([]);
});

test("the demo's main screens open without errors", async ({ browser }) => {
  test.setTimeout(300_000);
  const page = await as(browser, "reviewer");
  const clients = await demoClients(page);
  test.skip(clients.length < DEMO.length, "The demo clients are not loaded");
  for (const c of clients) {
    await page.goto(`/entities/${c.entityId}`);
    await expect(page.getByRole("heading", { level: 1, name: c.legalName })).toBeVisible();
    await expect(page.getByRole("alert")).toHaveCount(0);
    const periods: Json[] = await api(page, `/entities/${c.entityId}/periods`);
    const open = periods.filter((p) => ["OPEN", "IN_REVIEW", "APPROVED", "FILED"].includes(p.state))[0] ?? periods[periods.length - 1];
    for (const tab of ["", "data", "exceptions", "lines", "suggestions", "adjustments", "schemes", "exports", "review", "filing", "refund", "evidence", "corrections"]) {
      await page.goto(`/periods/${open.id}${tab ? `/${tab}` : ""}`);
      await expect(page.locator("nav.tabs")).toBeVisible();
      await page.waitForLoadState("networkidle");
      await expect(page.getByRole("alert"), `${c.legalName} ${tab || "return"}`).toHaveCount(0);
    }
  }
});
