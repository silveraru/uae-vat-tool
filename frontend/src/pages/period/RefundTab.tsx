import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { RefundPosition, RefundRequest } from "../../api/types";
import { DocumentList, uploadEvidence } from "../../components/DocumentList";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, Stat, useAction, useRoles } from "../../components/ui";
import { formatAed } from "../../i18n";
import { usePeriod } from "./PeriodLayout";

export function RefundTab() {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const key = ["refund", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<RefundPosition>(`/periods/${periodId}/refund`) });
  const [amount, setAmount] = useState("");
  const create = useAction(() => api.post(`/periods/${periodId}/refund`, { amountAed: amount || null }), [key]);
  return (
    <Query query={q}>
      {(p) => !p.refundable ? <p className="card muted">{t("refund.notRefundable")}</p> : (
        <>
          <ErrorAlert error={create.error} />
          <div className="stat-grid">
            <Stat label={t("refund.excess")} value={<Money value={p.excessAed} />} />
            <Stat label={t("refund.limit")} value={<span className="ltr">{p.timeLimitOn}</span>} tone={p.withinLimit ? "" : "bad"} />
          </div>
          {p.transitional && <div className="alert warn" role="status">{t("refund.transitional")}</div>}
          {!p.withinLimit && <div className="alert bad" role="status">{t("refund.limitPassed")}</div>}
          {!p.filed && <p className="card muted">{t("refund.fileFirst")}</p>}
          {p.filed && !p.request && p.withinLimit && roles.canFile && (
            <form className="card" onSubmit={(e) => { e.preventDefault(); create.mutate(undefined); }}>
              <h2>{t("refund.start")}</h2>
              <p className="muted">{t("refund.startExplain", { form: p.form })}</p>
              <div className="form-grid">
                <Field label={t("refund.amount")} hint={t("refund.amountHint")}><input type="number" step="0.01" min="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
              </div>
              <button type="submit" className="primary" disabled={create.isPending}>{t("refund.create")}</button>
            </form>
          )}
          {p.request && <RequestCard request={p.request} position={p} canFile={roles.canFile} />}
        </>
      )}
    </Query>
  );
}

function RequestCard({ request, position, canFile }: { request: RefundRequest; position: RefundPosition; canFile: boolean }) {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const key = ["refund", periodId];
  const pack = useAction(() => api.post(`/refunds/${request.id}/pack`), [key]);
  const ack = useRef<HTMLInputElement>(null);
  const [sub, setSub] = useState({ submissionRef: "", submittedOn: "" });
  const submit = useAction(async () => {
    const f = ack.current?.files?.[0];
    if (!f) throw new Error(t("filing.ackNeeded"));
    return api.post(`/refunds/${request.id}/submission`, { ...sub, ackEvidenceId: await uploadEvidence(entity.id, f, periodId, "Refund acknowledgement") });
  }, [key]);
  const [decision, setDecision] = useState({ approved: "true", decidedOn: "", approvedAmountAed: "", note: "" });
  const decide = useAction(() => api.post(`/refunds/${request.id}/decision`, { approved: decision.approved === "true", decidedOn: decision.decidedOn, approvedAmountAed: decision.approvedAmountAed || null, note: decision.note || null }), [key]);
  const [receipt, setReceipt] = useState({ receivedOn: "", receivedAmountAed: "" });
  const receive = useAction(() => api.post(`/refunds/${request.id}/receipt`, receipt), [key]);
  return (
    <section className="card">
      <div className="row">
        <h2>{t("refund.request")}</h2>
        <Badge value={request.status} />
      </div>
      <p><Money value={request.amountAed} /></p>
      <ErrorAlert error={pack.error ?? submit.error ?? decide.error ?? receive.error} />
      <DocumentList documents={position.documents} />
      {canFile && <button type="button" disabled={pack.isPending} onClick={() => pack.mutate(undefined)}>{t("refund.makePack")}</button>}
      {canFile && request.status === "DRAFT" && (
        <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); submit.mutate(undefined); }}>
          <h3>{t("refund.recordSubmission")}</h3>
          <div className="form-grid">
            <Field label={t("filing.ref")}><input required dir="ltr" value={sub.submissionRef} onChange={(e) => setSub({ ...sub, submissionRef: e.target.value })} /></Field>
            <Field label={t("filing.date")}><input type="date" required value={sub.submittedOn} onChange={(e) => setSub({ ...sub, submittedOn: e.target.value })} /></Field>
            <Field label={t("filing.ack")}><input ref={ack} type="file" required /></Field>
          </div>
          <button type="submit" className="primary" disabled={submit.isPending}>{t("filing.record")}</button>
        </form>
      )}
      {request.submissionRef && <p>{t("filing.filed", { ref: request.submissionRef, on: request.submittedOn })}</p>}
      {canFile && request.status === "SUBMITTED" && (
        <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); decide.mutate(undefined); }}>
          <h3>{t("refund.recordDecision")}</h3>
          <div className="form-grid">
            <Field label={t("refund.decision")}>
              <select value={decision.approved} onChange={(e) => setDecision({ ...decision, approved: e.target.value })}>
                <option value="true">{t("refund.approved")}</option>
                <option value="false">{t("refund.rejected")}</option>
              </select>
            </Field>
            <Field label={t("filing.date")}><input type="date" required value={decision.decidedOn} onChange={(e) => setDecision({ ...decision, decidedOn: e.target.value })} /></Field>
            <Field label={t("refund.approvedAmount")}><input type="number" step="0.01" value={decision.approvedAmountAed} onChange={(e) => setDecision({ ...decision, approvedAmountAed: e.target.value })} /></Field>
            <Field label={t("common.note")}><input value={decision.note} onChange={(e) => setDecision({ ...decision, note: e.target.value })} /></Field>
          </div>
          <button type="submit" className="primary" disabled={decide.isPending}>{t("common.save")}</button>
        </form>
      )}
      {request.decisionNote && <p className="muted">{request.decisionNote}</p>}
      {canFile && request.status === "APPROVED" && (
        <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); receive.mutate(undefined); }}>
          <h3>{t("refund.recordReceipt")}</h3>
          <div className="form-grid">
            <Field label={t("filing.date")}><input type="date" required value={receipt.receivedOn} onChange={(e) => setReceipt({ ...receipt, receivedOn: e.target.value })} /></Field>
            <Field label={t("refund.receivedAmount")}><input type="number" step="0.01" required value={receipt.receivedAmountAed} onChange={(e) => setReceipt({ ...receipt, receivedAmountAed: e.target.value })} /></Field>
          </div>
          <button type="submit" className="primary" disabled={receive.isPending}>{t("common.save")}</button>
        </form>
      )}
      {request.status === "RECEIVED" && <p>{t("refund.received", { amount: formatAed(request.receivedAmountAed), on: request.receivedOn })}</p>}
    </section>
  );
}
