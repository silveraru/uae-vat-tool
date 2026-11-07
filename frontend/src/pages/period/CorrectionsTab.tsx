import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { CorrectionCase, CorrectionPreview, DisclosureView, Evidence } from "../../api/types";
import { DocumentList, uploadEvidence } from "../../components/DocumentList";
import { PenaltyPanel } from "../../components/PenaltyPanel";
import { Query } from "../../components/Query";
import { ApiError } from "../../api/client";
import { Badge, ErrorAlert, Field, Money, useAction, useMe, useRoles } from "../../components/ui";
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
      <Query query={q}>
        {(rows) => <>{rows.filter((c) => c.route === "FORM_211").map((c) => <DisclosureCard key={c.id} correction={c} />)}</>}
      </Query>
    </>
  );
}

/** One voluntary disclosure, from draft to settled. A second person approves, and an agent submits on EmaraTax. */
function DisclosureCard({ correction }: { correction: CorrectionCase }) {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const me = useMe();
  const key = ["disclosure", correction.id];
  const q = useQuery({
    queryKey: key,
    queryFn: async () => {
      try {
        return await api.get<DisclosureView>(`/corrections/${correction.id}/disclosure`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
  const [draft, setDraft] = useState({ discoveredOn: "", natureOfError: "" });
  const create = useAction(() => api.post(`/corrections/${correction.id}/disclosure`, draft), [key]);
  const act = useAction(({ path, body }: { path: string; body?: unknown }) => api.post(`/disclosures/${q.data?.disclosure.id}/${path}`, body), [key]);
  const ack = useRef<HTMLInputElement>(null);
  const [sub, setSub] = useState({ submissionRef: "", submittedOn: "" });
  const submit = useAction(async () => {
    const f = ack.current?.files?.[0];
    if (!f) throw new Error(t("filing.ackNeeded"));
    return api.post(`/disclosures/${q.data?.disclosure.id}/submission`, { ...sub, ackEvidenceId: await uploadEvidence(entity.id, f, periodId, "Form 211 acknowledgement") });
  }, [key]);
  const [settle, setSettle] = useState({ settledOn: "", amountAed: "", gibanReference: "" });
  return (
    <section className="card">
      <h2>{t("disclosure.title")}</h2>
      <ErrorAlert error={create.error ?? act.error ?? submit.error} />
      <Query query={q}>
        {(v) => !v ? (
          roles.isReviewer ? (
            <form onSubmit={(e) => { e.preventDefault(); create.mutate(undefined); }}>
              <p className="muted">{t("disclosure.explain")}</p>
              <div className="form-grid">
                <Field label={t("disclosure.discoveredOn")}><input type="date" required value={draft.discoveredOn} onChange={(e) => setDraft({ ...draft, discoveredOn: e.target.value })} /></Field>
                <Field label={t("disclosure.nature")}><input required value={draft.natureOfError} onChange={(e) => setDraft({ ...draft, natureOfError: e.target.value })} /></Field>
              </div>
              <button type="submit" className="primary" disabled={create.isPending}>{t("disclosure.draft")}</button>
            </form>
          ) : <p className="muted">{t("disclosure.notDrafted")}</p>
        ) : (
          <>
            <div className="row">
              <Badge value={v.disclosure.status} />
              <span>{t("disclosure.difference", { amount: formatAed(v.taxDifferenceAed) })}</span>
              <span className="muted">{t("disclosure.dueBy")} <span className="ltr">{v.disclosure.dueBy}</span></span>
              {v.overdue && <span className="badge bad">{t("disclosure.overdue")}</span>}
            </div>
            <p>{v.disclosure.natureOfError}</p>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t("return.box")}</th><th>{t("exceptions.description")}</th><th className="num">{t("disclosure.vatFiled")}</th><th className="num">{t("disclosure.vatCorrected")}</th></tr></thead>
                <tbody>
                  {v.boxes.filter((b) => Number(b.vatFrom) !== Number(b.vatTo)).map((b) => (
                    <tr key={b.box}><td className="ltr">{b.box}</td><td>{b.label}</td><td className="num"><Money value={b.vatFrom} /></td><td className="num"><Money value={b.vatTo} /></td></tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h3 style={{ marginBlockStart: 12 }}>{t("penalty.title")}</h3>
            <PenaltyPanel estimate={v.penalty} />
            <h3 style={{ marginBlockStart: 12 }}>{t("disclosure.pack")}</h3>
            <DocumentList documents={v.documents} />
            <div className="row">
              {roles.isReviewer && <button type="button" disabled={act.isPending} onClick={() => act.mutate({ path: "pack" })}>{t("disclosure.makePack")}</button>}
              {v.disclosure.status === "DRAFT" && (roles.canDecide || roles.isPartner) && v.disclosure.createdBy !== me.data?.id && (
                <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "approve" })}>{t("disclosure.approve")}</button>
              )}
            </div>
            {v.disclosure.status === "APPROVED" && roles.canFile && (
              <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); submit.mutate(undefined); }}>
                <h3>{t("disclosure.recordSubmission")}</h3>
                <div className="form-grid">
                  <Field label={t("filing.ref")}><input required dir="ltr" value={sub.submissionRef} onChange={(e) => setSub({ ...sub, submissionRef: e.target.value })} /></Field>
                  <Field label={t("filing.date")}><input type="date" required value={sub.submittedOn} onChange={(e) => setSub({ ...sub, submittedOn: e.target.value })} /></Field>
                  <Field label={t("filing.ack")}><input ref={ack} type="file" required /></Field>
                </div>
                <button type="submit" className="primary" disabled={submit.isPending}>{t("filing.record")}</button>
              </form>
            )}
            {v.disclosure.status === "SUBMITTED" && roles.canFile && (
              <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); act.mutate({ path: "settlement", body: { ...settle, gibanReference: settle.gibanReference || null } }); }}>
                <h3>{t("disclosure.recordSettlement")}</h3>
                <div className="form-grid">
                  <Field label={t("filing.date")}><input type="date" required value={settle.settledOn} onChange={(e) => setSettle({ ...settle, settledOn: e.target.value })} /></Field>
                  <Field label={t("filing.amountPaid")}><input type="number" step="0.01" required value={settle.amountAed} onChange={(e) => setSettle({ ...settle, amountAed: e.target.value })} /></Field>
                  <Field label={t("filing.giban")}><input dir="ltr" value={settle.gibanReference} onChange={(e) => setSettle({ ...settle, gibanReference: e.target.value })} /></Field>
                </div>
                <button type="submit" className="primary" disabled={act.isPending}>{t("common.save")}</button>
              </form>
            )}
            {v.disclosure.submissionRef && <p>{t("filing.filed", { ref: v.disclosure.submissionRef, on: v.disclosure.submittedOn })}</p>}
          </>
        )}
      </Query>
    </section>
  );
}
