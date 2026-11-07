import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AuditCase, FafResult } from "../api/types";
import { DocumentList } from "../components/DocumentList";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, Field, useAction, useRoles } from "../components/ui";

export function fafWarning(t: (key: string, options?: Record<string, unknown>) => string, warning: string): string {
  const [code, a, b] = warning.split(":");
  return t(`auditCases.warning.${code}`, { a, b });
}

export function AuditCasesPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const roles = useRoles(id);
  const key = ["audit-cases", id];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<AuditCase[]>(`/entities/${id}/audit-cases`) });
  const blank = { ftaReference: "", notifiedOn: "", periodFrom: "", periodTo: "", description: "" };
  const [form, setForm] = useState(blank);
  const open = useAction(() => api.post(`/entities/${id}/audit-cases`, form), [key]);
  const [range, setRange] = useState({ from: "", to: "" });
  const [faf, setFaf] = useState<FafResult | null>(null);
  const make = useAction(() => api.post<FafResult>(`/entities/${id}/faf?from=${range.from}&to=${range.to}`));
  return (
    <>
      <h1>{t("auditCases.title")}</h1>
      <p><Link to={`/entities/${id}`}>{t("auditCases.backToClient")}</Link></p>
      <ErrorAlert error={open.error ?? make.error} />
      {roles.canAudit && (
        <form className="card" onSubmit={(e) => { e.preventDefault(); make.mutate(undefined, { onSuccess: (r) => setFaf(r as FafResult) }); }}>
          <h2>{t("auditCases.faf")}</h2>
          <p className="muted">{t("auditCases.fafExplain")}</p>
          <div className="form-grid">
            <Field label={t("connectors.from")}><input type="date" required value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /></Field>
            <Field label={t("connectors.to")}><input type="date" required value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></Field>
          </div>
          <button type="submit" className="primary" disabled={make.isPending}>{t("auditCases.makeFaf")}</button>
          {faf && (
            <div style={{ marginBlockStart: 12 }}>
              <p>{t("auditCases.fafCounts", { purchases: faf.purchaseRows, supplies: faf.supplyRows, ledger: faf.ledgerRows })}</p>
              {faf.warnings.map((w) => <div key={w} className="alert warn" role="status">{fafWarning(t, w)}</div>)}
              <DocumentList documents={[faf.document]} />
            </div>
          )}
        </form>
      )}
      <section className="card">
        <h2>{t("auditCases.cases")}</h2>
        <Query query={q}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("auditCases.none")}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t("auditCases.reference")}</th><th>{t("auditCases.notifiedOn")}</th><th>{t("auditCases.periods")}</th><th>{t("common.status")}</th></tr></thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id}>
                      <td><Link to={`/audit-cases/${c.id}`} className="ltr">{c.ftaReference}</Link></td>
                      <td className="ltr">{c.notifiedOn}</td>
                      <td className="ltr">{c.periodFrom} → {c.periodTo}</td>
                      <td><Badge value={c.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Query>
        {roles.canAudit && (
          <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); open.mutate(undefined, { onSuccess: () => setForm(blank) }); }}>
            <h3>{t("auditCases.open")}</h3>
            <p className="muted">{t("auditCases.openExplain")}</p>
            <div className="form-grid">
              <Field label={t("auditCases.reference")}><input required dir="ltr" value={form.ftaReference} onChange={(e) => setForm({ ...form, ftaReference: e.target.value })} /></Field>
              <Field label={t("auditCases.notifiedOn")}><input type="date" required value={form.notifiedOn} onChange={(e) => setForm({ ...form, notifiedOn: e.target.value })} /></Field>
              <Field label={t("connectors.from")}><input type="date" required value={form.periodFrom} onChange={(e) => setForm({ ...form, periodFrom: e.target.value })} /></Field>
              <Field label={t("connectors.to")}><input type="date" required value={form.periodTo} onChange={(e) => setForm({ ...form, periodTo: e.target.value })} /></Field>
              <Field label={t("exceptions.description")}><input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
            </div>
            <button type="submit" className="primary" disabled={open.isPending}>{t("auditCases.openButton")}</button>
          </form>
        )}
      </section>
    </>
  );
}
