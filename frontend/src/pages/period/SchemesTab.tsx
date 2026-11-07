import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { ApportionResult, AssetDue, BadDebtCandidate, BadDebtClaim, BadDebtRelieved, CapitalAssetView } from "../../api/types";
import { uploadEvidence } from "../../components/DocumentList";
import { Query } from "../../components/Query";
import { ErrorAlert, Field, Money, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

/** The schemes that adjust a return after the event: apportionment, bad debt relief and capital assets. */
export function SchemesTab() {
  const { t } = useTranslation();
  const { view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state);
  if (locked) return <p className="card muted">{t("schemes.locked")}</p>;
  if (!roles.isReviewer) return <p className="card muted">{t("schemes.reviewerOnly")}</p>;
  return (
    <>
      <Apportionment />
      <BadDebt />
      <CapitalAssetsDue />
    </>
  );
}

function Apportionment() {
  const { t } = useTranslation();
  const { periodId } = usePeriod();
  const [excluded, setExcluded] = useState("");
  const [result, setResult] = useState<ApportionResult | null>(null);
  const refresh = [["return", periodId], ["adjustments", periodId], ["exceptions", periodId]];
  const run = useAction(({ path, post }: { path: string; post: boolean }) =>
    api.post<ApportionResult>(`/periods/${periodId}/${path}`, { post, excludedSuppliesAed: excluded || null }), refresh);
  const act = (path: string, post: boolean) => run.mutate({ path, post }, { onSuccess: (r) => setResult(r as ApportionResult) });
  return (
    <section className="card">
      <h2>{t("schemes.apportionment")}</h2>
      <p className="muted">{t("schemes.apportionmentExplain")}</p>
      <ErrorAlert error={run.error} />
      <div className="form-grid">
        <Field label={t("schemes.excluded")} hint={t("schemes.excludedHint")}><input type="number" step="0.01" min="0" value={excluded} onChange={(e) => setExcluded(e.target.value)} /></Field>
      </div>
      <div className="row">
        <button type="button" disabled={run.isPending} onClick={() => act("apportionment", false)}>{t("schemes.preview")}</button>
        <button type="button" className="primary" disabled={run.isPending} onClick={() => act("apportionment", true)}>{t("schemes.post")}</button>
        <button type="button" disabled={run.isPending} onClick={() => act("apportionment/annual", false)}>{t("schemes.previewAnnual")}</button>
        <button type="button" disabled={run.isPending} onClick={() => act("apportionment/annual", true)}>{t("schemes.postAnnual")}</button>
      </div>
      {result && (
        <div className={`alert ${result.furtherAdjustment ? "warn" : "ok"}`} role="status" style={{ marginBlockStart: 12 }}>
          <strong>{t(`schemes.method.${result.method}`)}</strong>{" "}
          <span className="ltr">{result.from} → {result.to}</span>
          <p>{t("schemes.ratio", { ratio: Number(result.ratioPct) })}</p>
          <p>{t("schemes.apportionResult", { residual: result.residualInputAed, target: result.targetRecoverableAed, already: result.alreadyRecoveredAed, delta: result.deltaAed })}</p>
          <p>{result.posted ? t("schemes.posted") : t("schemes.notPosted")}</p>
          {result.furtherAdjustment && <p>{t("schemes.further")}</p>}
        </div>
      )}
    </section>
  );
}

function BadDebt() {
  const { t } = useTranslation();
  const { periodId, entity, view } = usePeriod();
  const claims = useQuery({ queryKey: ["bad-debt", periodId], queryFn: () => api.get<BadDebtClaim[]>(`/periods/${periodId}/bad-debt`) });
  const candidates = useQuery({ queryKey: ["bad-debt-candidates", periodId], queryFn: () => api.get<BadDebtCandidate[]>(`/periods/${periodId}/bad-debt/candidates`) });
  const blank = { documentId: "", writtenOffAed: "", writtenOffOn: "", notifiedOn: "", reason: "" };
  const [form, setForm] = useState(blank);
  const writeOff = useRef<HTMLInputElement>(null);
  const notice = useRef<HTMLInputElement>(null);
  const recoverable = useQuery({ queryKey: ["bad-debt-recoverable", periodId], queryFn: () => api.get<BadDebtRelieved[]>(`/periods/${periodId}/bad-debt/recoverable`) });
  const blankRecovery = { documentId: "", recoveredAed: "", reason: "" };
  const [recovery, setRecovery] = useState(blankRecovery);
  const recoveryFile = useRef<HTMLInputElement>(null);
  const refresh = [["bad-debt", periodId], ["bad-debt-candidates", periodId], ["bad-debt-recoverable", periodId], ["adjustments", periodId], ["return", periodId]];
  const recover = useAction(async () => {
    const f = recoveryFile.current?.files?.[0];
    if (!f) throw new Error(t("schemes.recoveryEvidence"));
    const evidenceId = await uploadEvidence(entity.id, f, view.period.id, "Bad debt recovered");
    return api.post(`/periods/${periodId}/bad-debt/recovery`, { ...recovery, evidenceId });
  }, refresh);
  const claim = useAction(async () => {
    const a = writeOff.current?.files?.[0];
    const b = notice.current?.files?.[0];
    if (!a || !b) throw new Error(t("schemes.badDebtEvidence"));
    const writeOffEvidenceId = await uploadEvidence(entity.id, a, view.period.id, "Bad debt write-off");
    const noticeEvidenceId = await uploadEvidence(entity.id, b, view.period.id, "Bad debt notice to customer");
    return api.post(`/periods/${periodId}/bad-debt`, { ...form, writeOffEvidenceId, noticeEvidenceId });
  }, refresh);
  return (
    <section className="card">
      <h2>{t("schemes.badDebt")}</h2>
      <p className="muted">{t("schemes.badDebtExplain")}</p>
      <ErrorAlert error={claim.error ?? recover.error} />
      <Query query={candidates}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("schemes.noCandidates")}</p> : (
          <form onSubmit={(e) => { e.preventDefault(); claim.mutate(undefined, { onSuccess: () => { setForm(blank); if (writeOff.current) writeOff.current.value = ""; if (notice.current) notice.current.value = ""; } }); }}>
            <div className="form-grid">
              <Field label={t("schemes.invoice")}>
                <select required value={form.documentId} onChange={(e) => setForm({ ...form, documentId: e.target.value })}>
                  <option value="">{t("common.choose")}</option>
                  {rows.map((c) => <option key={c.documentId} value={c.documentId}>{c.docNumber} · {c.docDate} · {c.grossAed}</option>)}
                </select>
              </Field>
              <Field label={t("schemes.writtenOff")}><input type="number" step="0.01" min="0.01" required value={form.writtenOffAed} onChange={(e) => setForm({ ...form, writtenOffAed: e.target.value })} /></Field>
              <Field label={t("schemes.writtenOffOn")}><input type="date" required value={form.writtenOffOn} onChange={(e) => setForm({ ...form, writtenOffOn: e.target.value })} /></Field>
              <Field label={t("schemes.notifiedOn")}><input type="date" required value={form.notifiedOn} onChange={(e) => setForm({ ...form, notifiedOn: e.target.value })} /></Field>
              <Field label={t("schemes.writeOffFile")}><input ref={writeOff} type="file" required /></Field>
              <Field label={t("schemes.noticeFile")}><input ref={notice} type="file" required /></Field>
              <Field label={t("common.reason")}><input required value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field>
            </div>
            <button type="submit" className="primary" disabled={claim.isPending}>{t("schemes.claim")}</button>
          </form>
        )}
      </Query>
      <Query query={claims}>
        {(rows) => rows.length > 0 && (
          <div className="table-wrap" style={{ marginBlockStart: 12 }}>
            <table>
              <thead><tr><th>{t("schemes.invoice")}</th><th>{t("schemes.claimKind")}</th><th className="num">{t("return.amount")}</th><th className="num">{t("return.vat")}</th></tr></thead>
              <tbody>{rows.map((c) => <tr key={c.id}><td className="ltr">{c.docNumber}</td><td>{t(`schemes.claimKindValue.${c.kind}`)}</td><td className="num"><Money value={c.amountAed} /></td><td className="num"><Money value={c.vatAed} /></td></tr>)}</tbody>
            </table>
            <p className="muted"><small>{t("schemes.secondPerson")}</small></p>
          </div>
        )}
      </Query>
      <Query query={recoverable}>
        {(rows) => rows.length > 0 && (
          <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); recover.mutate(undefined, { onSuccess: () => { setRecovery(blankRecovery); if (recoveryFile.current) recoveryFile.current.value = ""; } }); }}>
            <h3>{t("schemes.recovery")}</h3>
            <p className="muted">{t("schemes.recoveryExplain")}</p>
            <div className="form-grid">
              <Field label={t("schemes.relievedInvoice")}>
                <select required value={recovery.documentId} onChange={(e) => setRecovery({ ...recovery, documentId: e.target.value })}>
                  <option value="">{t("common.choose")}</option>
                  {rows.map((c) => <option key={c.documentId} value={c.documentId}>{c.docNumber} · {c.docDate} · {c.outstandingAed}</option>)}
                </select>
              </Field>
              <Field label={t("schemes.recovered")}><input type="number" step="0.01" min="0.01" required value={recovery.recoveredAed} onChange={(e) => setRecovery({ ...recovery, recoveredAed: e.target.value })} /></Field>
              <Field label={t("schemes.recoveryFile")}><input ref={recoveryFile} type="file" required /></Field>
              <Field label={t("common.reason")}><input required value={recovery.reason} onChange={(e) => setRecovery({ ...recovery, reason: e.target.value })} /></Field>
            </div>
            <button type="submit" disabled={recover.isPending}>{t("schemes.recordRecovery")}</button>
          </form>
        )}
      </Query>
    </section>
  );
}

function CapitalAssetsDue() {
  const { t } = useTranslation();
  const { periodId, entity, view } = usePeriod();
  const q = useQuery({ queryKey: ["assets-due", periodId], queryFn: () => api.get<AssetDue[]>(`/periods/${periodId}/capital-assets/due`) });
  const assets = useQuery({ queryKey: ["capital-assets", entity.id], queryFn: () => api.get<CapitalAssetView[]>(`/entities/${entity.id}/capital-assets`) });
  const blankDisposal = { assetId: "", disposedOn: "", taxable: false };
  const [disposal, setDisposal] = useState(blankDisposal);
  const dispose = useAction(
    () => api.post(`/periods/${periodId}/capital-assets/${disposal.assetId}/dispose`, { disposedOn: disposal.disposedOn, taxable: disposal.taxable }),
    [["assets-due", periodId], ["capital-assets", entity.id], ["return", periodId], ["adjustments", periodId]],
  );
  const held = (assets.data ?? []).filter((v) => !v.asset.disposedOn);
  const [use, setUse] = useState<Record<string, string>>({});
  const adjust = useAction(({ id, pct }: { id: string; pct: string }) => api.post(`/periods/${periodId}/capital-assets/${id}/adjust`, { taxableUsePct: pct }), [["assets-due", periodId], ["return", periodId], ["adjustments", periodId]]);
  return (
    <section className="card">
      <h2>{t("schemes.assets")}</h2>
      <p className="muted">{t("schemes.assetsExplain")} <Link to={`/entities/${entity.id}`}>{t("schemes.assetRegister")}</Link></p>
      <ErrorAlert error={adjust.error ?? dispose.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("schemes.noAssetsDue")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("schemes.asset")}</th><th className="num">{t("schemes.interval")}</th><th>{t("schemes.intervalEnd")}</th><th className="num">{t("schemes.initialPct")}</th><th>{t("schemes.usePct")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((d) => (
                  <tr key={d.asset.id}>
                    <td>{d.asset.reference} <small className="muted">{d.asset.description}</small></td>
                    <td className="num">{d.intervalNo}</td>
                    <td className="ltr">{d.intervalEnd}</td>
                    <td className="num">{Number(d.asset.initialRecoveryPct)}%</td>
                    <td><input type="number" min="0" max="100" step="0.01" aria-label={t("schemes.usePct")} value={use[d.asset.id] ?? ""} onChange={(e) => setUse({ ...use, [d.asset.id]: e.target.value })} /></td>
                    <td><button type="button" disabled={!use[d.asset.id] || adjust.isPending} onClick={() => adjust.mutate({ id: d.asset.id, pct: use[d.asset.id] })}>{t("schemes.recordUse")}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      {held.length > 0 && (
        <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); dispose.mutate(undefined, { onSuccess: () => setDisposal(blankDisposal) }); }}>
          <h3>{t("schemes.disposal")}</h3>
          <p className="muted">{t("schemes.disposalExplain")}</p>
          <div className="form-grid">
            <Field label={t("schemes.asset")}>
              <select required value={disposal.assetId} onChange={(e) => setDisposal({ ...disposal, assetId: e.target.value })}>
                <option value="">{t("common.choose")}</option>
                {held.map((v) => <option key={v.asset.id} value={v.asset.id}>{v.asset.reference} · {v.asset.description}</option>)}
              </select>
            </Field>
            <Field label={t("schemes.disposedOn")}><input type="date" required min={view.period.periodStart} max={view.period.periodEnd} value={disposal.disposedOn} onChange={(e) => setDisposal({ ...disposal, disposedOn: e.target.value })} /></Field>
          </div>
          <label className="check-row"><input type="checkbox" checked={disposal.taxable} onChange={(e) => setDisposal({ ...disposal, taxable: e.target.checked })} /><span>{t("schemes.disposalTaxable")}</span></label>
          <button type="submit" disabled={dispose.isPending}>{t("schemes.recordDisposal")}</button>
        </form>
      )}
    </section>
  );
}
