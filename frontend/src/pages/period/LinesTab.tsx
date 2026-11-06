import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { LinesPage } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, Pager, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

const TREATMENTS = ["STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "REVERSE_CHARGE", "IMPORT", "DRC", "TOURIST_REFUND"];
const REASONS = ["WRONG_TAX_CODE", "PLACE_OF_SUPPLY", "DRC_CONFIRMED", "BLOCKED_CONFIRMED", "BLOCKED_REMOVED", "RECOVERY_RATE", "CONFIRMED_AS_IS", "OTHER"];

export function LinesTab() {
  const { t } = useTranslation();
  const { periodId, view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const [filter, setFilter] = useState({ treatment: "", direction: "", search: "", flagged: false });
  const [page, setPage] = useState(0);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [change, setChange] = useState({ treatment: "STANDARD", blockedCategory: "", recoveryPct: "", reasonCode: "WRONG_TAX_CODE", reason: "" });
  const params = new URLSearchParams({ page: String(page), size: "50" });
  if (filter.treatment) params.set("treatment", filter.treatment);
  if (filter.direction) params.set("direction", filter.direction);
  if (filter.search) params.set("search", filter.search);
  const key = ["lines", periodId, params.toString()];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<LinesPage>(`/periods/${periodId}/lines?${params}`) });
  const reclass = useAction((body: unknown) => api.post(`/periods/${periodId}/reclassify`, body), [["lines", periodId], ["return", periodId], ["exceptions", periodId]]);
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state);
  const canEdit = roles.isPreparer && !locked;
  const toggle = (id: string) => setChosen((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  return (
    <>
      <form className="card form-grid" onSubmit={(e) => e.preventDefault()}>
        <Field label={t("lines.treatment")}>
          <select value={filter.treatment} onChange={(e) => { setFilter({ ...filter, treatment: e.target.value }); setPage(0); }}>
            <option value="">{t("common.all")}</option>
            {[...TREATMENTS, "UNCLASSIFIED"].map((x) => <option key={x} value={x}>{t(`treatment.${x}`)}</option>)}
          </select>
        </Field>
        <Field label={t("lines.direction")}>
          <select value={filter.direction} onChange={(e) => { setFilter({ ...filter, direction: e.target.value }); setPage(0); }}>
            <option value="">{t("common.all")}</option>
            <option value="OUT">{t("direction.OUT")}</option>
            <option value="IN">{t("direction.IN")}</option>
          </select>
        </Field>
        <Field label={t("lines.search")}><input value={filter.search} onChange={(e) => { setFilter({ ...filter, search: e.target.value }); setPage(0); }} /></Field>
      </form>
      <ErrorAlert error={reclass.error} />
      <Query query={q}>
        {(d) => (
          <div className="card table-wrap">
            <table>
              <thead>
                <tr>
                  {canEdit && <th><span className="sr-only">{t("lines.select")}</span></th>}
                  <th>{t("lines.taxPoint")}</th><th>{t("lines.direction")}</th><th>{t("lines.description")}</th><th>{t("lines.code")}</th>
                  <th>{t("lines.treatment")}</th><th className="num">{t("return.amount")}</th><th className="num">{t("return.vat")}</th><th className="num">{t("lines.recoverable")}</th><th>{t("lines.flags")}</th>
                </tr>
              </thead>
              <tbody>
                {d.rows.map((l) => (
                  <tr key={l.id}>
                    {canEdit && <td><input type="checkbox" style={{ width: "auto" }} aria-label={t("lines.selectLine", { line: l.description ?? l.id.slice(0, 8) })} checked={chosen.has(l.id)} onChange={() => toggle(l.id)} disabled={l.lineKind !== "NORMAL"} /></td>}
                    <td className="ltr">{l.taxPoint}</td>
                    <td>{t(`direction.${l.direction}`)}</td>
                    <td>{l.description ?? l.counterpartyTrn ?? "–"}</td>
                    <td className="ltr">{l.taxCode ?? "–"}{l.glAccount ? ` / ${l.glAccount}` : ""}</td>
                    <td><Badge value={l.treatment} group="treatment" />{l.blockedCategory && <><br /><small className="muted">{t("lines.blocked")}: {l.blockedCategory}</small></>}{l.memo && <><br /><small className="muted">{t("lines.memo")}</small></>}</td>
                    <td className="num"><Money value={l.netAed} /></td>
                    <td className="num"><Money value={l.vatAed} /></td>
                    <td className="num"><Money value={l.recoverableVatAed} /></td>
                    <td><small className="ltr">{l.reviewFlags.join(", ")}</small></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {d.rows.length === 0 && <p className="muted">{t("common.none")}</p>}
            <Pager page={d.page} size={d.size} total={d.total} onPage={setPage} />
          </div>
        )}
      </Query>
      {canEdit && chosen.size > 0 && (
        <form
          className="card"
          onSubmit={(e) => {
            e.preventDefault();
            reclass.mutate({
              lineIds: [...chosen], treatment: change.treatment, blockedCategory: change.blockedCategory || null,
              recoveryPct: change.recoveryPct || null, reasonCode: change.reasonCode, reason: change.reason,
            }, { onSuccess: () => { setChosen(new Set()); setChange({ ...change, reason: "" }); } });
          }}
        >
          <h2>{t("lines.reclassify", { count: chosen.size })}</h2>
          <div className="form-grid">
            <Field label={t("lines.newTreatment")}>
              <select value={change.treatment} onChange={(e) => setChange({ ...change, treatment: e.target.value })}>{TREATMENTS.map((x) => <option key={x} value={x}>{t(`treatment.${x}`)}</option>)}</select>
            </Field>
            <Field label={t("entity.blocked")}>
              <select value={change.blockedCategory} onChange={(e) => setChange({ ...change, blockedCategory: e.target.value })}>
                <option value="">–</option>
                {["ENTERTAINMENT", "MOTOR_VEHICLE_PRIVATE", "EMPLOYEE_BENEFIT"].map((x) => <option key={x} value={x}>{x}</option>)}
              </select>
            </Field>
            <Field label={t("lines.recoveryPct")} hint={t("lines.recoveryHint")}><input type="number" min="0" max="100" step="0.01" value={change.recoveryPct} onChange={(e) => setChange({ ...change, recoveryPct: e.target.value })} /></Field>
            <Field label={t("lines.reasonCode")}>
              <select value={change.reasonCode} onChange={(e) => setChange({ ...change, reasonCode: e.target.value })}>{REASONS.map((x) => <option key={x} value={x}>{t(`reasonCode.${x}`)}</option>)}</select>
            </Field>
            <Field label={t("common.reason")}><input required value={change.reason} onChange={(e) => setChange({ ...change, reason: e.target.value })} /></Field>
          </div>
          <button type="submit" className="primary" disabled={reclass.isPending}>{t("lines.apply")}</button>
        </form>
      )}
    </>
  );
}
