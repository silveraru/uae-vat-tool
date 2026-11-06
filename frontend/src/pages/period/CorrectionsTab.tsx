import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { CorrectionCase, CorrectionPreview, Evidence } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, useAction, useRoles } from "../../components/ui";
import { formatAed } from "../../i18n";
import { usePeriod } from "./PeriodLayout";

export function CorrectionsTab() {
  const { t } = useTranslation();
  const { periodId, view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const key = ["corrections", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<CorrectionCase[]>(`/periods/${periodId}/corrections`) });
  const [reason, setReason] = useState("");
  const [box, setBox] = useState("");
  const [vatChange, setVatChange] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<CorrectionPreview | null>(null);
  const manual = box && vatChange ? [{ box, vatChange }] : [];
  const look = useAction(() => api.post<CorrectionPreview>(`/periods/${periodId}/corrections/preview`, { manualChanges: manual }));
  const open = useAction(async () => {
    const f = fileRef.current?.files?.[0];
    const evidenceId = f ? (await api.upload<Evidence>(`/entities/${entity.id}/evidence`, f, { periodId, description: reason })).id : null;
    return api.post(`/periods/${periodId}/corrections`, { reason, evidenceId, manualChanges: manual });
  }, [key, ["return", periodId], ["versions", periodId]]);
  const filed = ["FILED", "PAID", "AMENDED"].includes(view.period.state);

  if (!filed) return <p className="card muted">{t("corrections.notFiled")}</p>;
  return (
    <>
      <ErrorAlert error={look.error ?? open.error} />
      {roles.isReviewer && (
        <form className="card" onSubmit={(e) => { e.preventDefault(); open.mutate(undefined, { onSuccess: () => { setPreview(null); setReason(""); setBox(""); setVatChange(""); } }); }}>
          <h2>{t("corrections.title")}</h2>
          <p className="muted">{t("corrections.explain")}</p>
          <div className="form-grid">
            <Field label={t("return.box")} hint={t("corrections.boxHint")}>
              <select value={box} onChange={(e) => setBox(e.target.value)}>
                <option value="">–</option>
                {view.form.filter((b) => !b.computed).map((b) => <option key={b.code} value={b.code}>{b.code}</option>)}
              </select>
            </Field>
            <Field label={t("corrections.vatChange")}><input type="number" step="0.01" value={vatChange} onChange={(e) => setVatChange(e.target.value)} /></Field>
            <Field label={t("adjustments.evidence")}><input ref={fileRef} type="file" /></Field>
            <Field label={t("common.reason")}><input required value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          </div>
          <div className="row">
            <button type="button" disabled={look.isPending} onClick={() => look.mutate(undefined, { onSuccess: (r) => setPreview(r as CorrectionPreview) })}>{t("corrections.preview")}</button>
            <button type="submit" className="primary" disabled={open.isPending}>{t("corrections.open")}</button>
          </div>
          {preview && (
            <div className={`alert ${preview.route === "FORM_211" ? "bad" : "warn"}`} role="status" style={{ marginBlockStart: 12 }}>
              <strong>{t(`route.${preview.route}`)}</strong>
              <p>{t("corrections.difference", { difference: formatAed(preview.taxDifference), threshold: formatAed(preview.threshold) })}</p>
              <p>{t("corrections.payableChange", { from: formatAed(preview.originalPayable), to: formatAed(preview.correctedPayable) })}</p>
            </div>
          )}
        </form>
      )}
      <section className="card table-wrap">
        <h2>{t("corrections.cases")}</h2>
        <Query query={q}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
            <table>
              <thead><tr><th>{t("return.when")}</th><th>{t("corrections.route")}</th><th className="num">{t("corrections.taxDifference")}</th><th>{t("common.reason")}</th><th>{t("corrections.target")}</th></tr></thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.id}>
                    <td className="ltr">{c.createdAt.slice(0, 16).replace("T", " ")}</td>
                    <td><Badge value={c.route} group="route" /></td>
                    <td className="num"><Money value={c.taxDifferenceAed} /></td>
                    <td>{c.reason}</td>
                    <td>{c.targetPeriodId ? <Link to={`/periods/${c.targetPeriodId}`}>{t("corrections.openTarget")}</Link> : t("corrections.form211Needed")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Query>
      </section>
    </>
  );
}
