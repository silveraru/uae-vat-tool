import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Adjustment, Evidence, ReversalResult } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, useAction, useMe, useRoles } from "../../components/ui";
import { formatAed } from "../../i18n";
import { usePeriod } from "./PeriodLayout";

export function AdjustmentsTab() {
  const { t } = useTranslation();
  const { periodId, entity, view } = usePeriod();
  const roles = useRoles(entity.id);
  const me = useMe();
  const key = ["adjustments", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Adjustment[]>(`/periods/${periodId}/adjustments`) });
  const refresh = [key, ["return", periodId], ["exceptions", periodId], ["workflow", periodId]];
  const [result, setResult] = useState<ReversalResult | null>(null);
  const reversal = useAction(() => api.post<ReversalResult>(`/periods/${periodId}/adjustments/supplier-reversal`), refresh);
  const decide = useAction(({ id, approve }: { id: string; approve: boolean }) => api.post(`/adjustments/${id}/decision`, { approve }), refresh);
  const [form, setForm] = useState({ type: "MANUAL_JOURNAL", boxCode: "9", netAed: "", vatAed: "", description: "", reason: "" });
  const fileRef = useRef<HTMLInputElement>(null);
  const propose = useAction(async () => {
    const file = fileRef.current?.files?.[0];
    if (!file) throw new Error(t("adjustments.evidenceNeeded"));
    const evidence = await api.upload<Evidence>(`/entities/${entity.id}/evidence`, file, { periodId, description: form.description });
    return api.post(`/periods/${periodId}/adjustments`, { ...form, netAed: form.netAed || null, evidenceId: evidence.id });
  }, refresh);
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state);
  const adjustable = view.form.filter((b) => !b.computed && (form.type === "DEEMED_SUPPLY" ? /^1[a-g]$/.test(b.code) : b.columns.includes("adjustment")));

  return (
    <>
      <ErrorAlert error={reversal.error ?? decide.error ?? propose.error} />
      {!locked && roles.canAdjust && (
        <section className="card">
          <h2>{t("adjustments.sixMonth")}</h2>
          <p className="muted">{t("adjustments.sixMonthExplain")}</p>
          {result && <div className="alert ok" role="status">{t("adjustments.sixMonthResult", { reversed: result.reversed, reinstated: result.reinstated, total: formatAed(result.totalVatChange) })}</div>}
          <button type="button" className="primary" disabled={reversal.isPending} onClick={() => reversal.mutate(undefined, { onSuccess: (r) => setResult(r as ReversalResult) })}>{t("adjustments.runSixMonth")}</button>
        </section>
      )}
      <section className="card table-wrap">
        <h2>{t("adjustments.list")}</h2>
        <Query query={q}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
            <table>
              <thead><tr><th>{t("adjustments.type")}</th><th>{t("return.box")}</th><th>{t("exceptions.description")}</th><th className="num">{t("return.vat")}</th><th>{t("common.status")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.id}>
                    <td>{t(`adjustmentType.${a.type}`)}</td>
                    <td className="ltr">{a.boxCode}</td>
                    <td>{a.description}{a.reason && <><br /><small className="muted">{a.reason}</small></>}</td>
                    <td className="num"><Money value={a.vatAed} /></td>
                    <td><Badge value={a.status} /></td>
                    <td>
                      {a.status === "PROPOSED" && roles.canDecide && a.createdBy !== me.data?.id && !locked && (
                        <>
                          <button type="button" onClick={() => decide.mutate({ id: a.id, approve: true })}>{t("adjustments.approve")}</button>{" "}
                          <button type="button" onClick={() => decide.mutate({ id: a.id, approve: false })}>{t("adjustments.reject")}</button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Query>
      </section>
      {!locked && roles.canAdjust && (
        <form
          className="card"
          onSubmit={(e) => {
            e.preventDefault();
            propose.mutate(undefined, { onSuccess: () => { setForm({ ...form, netAed: "", vatAed: "", description: "", reason: "" }); if (fileRef.current) fileRef.current.value = ""; } });
          }}
        >
          <h2>{t("adjustments.propose")}</h2>
          <p className="muted">{t("adjustments.proposeExplain")}</p>
          <div className="form-grid">
            <Field label={t("adjustments.type")}>
              <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value, boxCode: e.target.value === "DEEMED_SUPPLY" ? "1b" : "9" })}>
                <option value="MANUAL_JOURNAL">{t("adjustmentType.MANUAL_JOURNAL")}</option>
                <option value="DEEMED_SUPPLY">{t("adjustmentType.DEEMED_SUPPLY")}</option>
              </select>
            </Field>
            <Field label={t("return.box")}>
              <select value={form.boxCode} onChange={(e) => setForm({ ...form, boxCode: e.target.value })}>{adjustable.map((b) => <option key={b.code} value={b.code}>{b.code}</option>)}</select>
            </Field>
            {form.type === "DEEMED_SUPPLY" && <Field label={t("return.amount")}><input type="number" step="0.01" value={form.netAed} onChange={(e) => setForm({ ...form, netAed: e.target.value })} /></Field>}
            <Field label={t("return.vat")}><input type="number" step="0.01" required value={form.vatAed} onChange={(e) => setForm({ ...form, vatAed: e.target.value })} /></Field>
            <Field label={t("exceptions.description")}><input required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
            <Field label={t("common.reason")}><input required value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field>
            <Field label={t("adjustments.evidence")}><input ref={fileRef} type="file" required /></Field>
          </div>
          <button type="submit" className="primary" disabled={propose.isPending}>{t("adjustments.submit")}</button>
        </form>
      )}
    </>
  );
}
