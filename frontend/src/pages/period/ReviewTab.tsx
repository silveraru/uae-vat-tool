import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Anomaly, WorkflowStatus } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, ReasonAction, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";
import { Comments } from "./ReturnTab";

export function ReviewTab() {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const key = ["workflow", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<WorkflowStatus>(`/periods/${periodId}/workflow`), refetchInterval: 5000 });
  const refresh = [key, ["return", periodId], ["versions", periodId], ["exceptions", periodId], ["portfolio"]];
  const act = useAction(({ path, body }: { path: string; body?: unknown }) => api.post(`/periods/${periodId}/${path}`, body), refresh);
  const [signature, setSignature] = useState({ typedName: "", consent: false });
  const anomalies = useQuery({ queryKey: ["anomalies", periodId], queryFn: () => api.get<Anomaly[]>(`/periods/${periodId}/analytics/anomalies`) });
  return (
    <Query query={q}>
      {(w) => {
        const live = w.signoffs.filter((s) => !s.invalidatedAt);
        const has = (step: string) => live.some((s) => s.step === step);
        const state = w.period.state;
        return (
          <>
            <ErrorAlert error={act.error} />
            <section className="card">
              <h2>{t("review.steps")}</h2>
              <ol className="steps">
                {w.requiredSteps.map((step) => {
                  const done = live.find((s) => s.step === step);
                  return (
                    <li key={step} className={done ? "done" : ""}>
                      <strong>{t(`step.${step}`)}</strong>{" "}
                      {done ? <span>{t("review.signedBy", { name: done.userName, when: done.signedAt.slice(0, 16).replace("T", " ") })}</span> : <span className="muted">{t("review.waiting")}</span>}
                    </li>
                  );
                })}
              </ol>
              {w.partnerRequired && <p className="muted">{t("review.partnerNeeded")}</p>}
              {w.blockers.length > 0 && (
                <div className="alert bad" role="status">
                  <strong>{t("review.blocked")}</strong>
                  <ul>{w.blockers.map((b) => <li key={b}>{b}</li>)}</ul>
                </div>
              )}
              <div className="row">
                {state === "OPEN" && roles.isPreparer && <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "submit" })}>{t("review.submit")}</button>}
                {state === "IN_REVIEW" && roles.isReviewer && !has("REVIEWED") && <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "review" })}>{t("review.review")}</button>}
                {state === "IN_REVIEW" && roles.isPartner && w.partnerRequired && !has("PARTNER") && <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "partner-signoff" })}>{t("review.partner")}</button>}
                {state === "IN_REVIEW" && roles.isApprover && !w.esignatureRequired && <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "approve" })}>{t("review.approve")}</button>}
                {state === "IN_REVIEW" && roles.canWaive && <ReasonAction label={t("review.sendBack")} onSubmit={(reason) => act.mutate({ path: "send-back", body: { reason } })} />}
                {state === "APPROVED" && roles.isApprover && <ReasonAction label={t("review.withdraw")} danger onSubmit={(reason) => act.mutate({ path: "withdraw-approval", body: { reason } })} />}
              </div>
              {state === "IN_REVIEW" && roles.isApprover && w.esignatureRequired && (
                <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); act.mutate({ path: "approve", body: { signature } }); }}>
                  <h3>{t("esign.title")}</h3>
                  <p className="muted">{t("esign.explain")}</p>
                  <div className="form-grid">
                    <Field label={t("esign.name")}><input required autoComplete="name" value={signature.typedName} onChange={(e) => setSignature({ ...signature, typedName: e.target.value })} /></Field>
                  </div>
                  <label className="check-row">
                    <input type="checkbox" required checked={signature.consent} onChange={(e) => setSignature({ ...signature, consent: e.target.checked })} />
                    {t("esign.consent")}
                  </label>
                  <button type="submit" className="primary" disabled={act.isPending}>{t("esign.sign")}</button>
                </form>
              )}
              {w.signatures.length > 0 && (
                <p className="muted" style={{ marginBlockStart: 12 }}>{t("esign.signed", { name: w.signatures[0].typedName, when: w.signatures[0].signedAt.slice(0, 16).replace("T", " ") })}</p>
              )}
            </section>
            {anomalies.data && anomalies.data.length > 0 && (
              <section className="card">
                <h2>{t("analytics.anomalies")}</h2>
                <p className="muted">{t("analytics.anomaliesExplain")}</p>
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>{t("analytics.flag")}</th><th>{t("return.box")}</th><th className="num">{t("analytics.value")}</th><th className="num">{t("analytics.baseline")}</th><th className="num">{t("analytics.change")}</th></tr></thead>
                    <tbody>
                      {anomalies.data.map((a) => (
                        <tr key={`${a.code}-${a.box}-${a.counterparty}`}>
                          <td>{t(`analytics.code.${a.code}`)}{a.counterparty && <> <span className="muted ltr">{a.counterparty}</span></>}</td>
                          <td className="ltr">{a.box ?? "–"}</td>
                          <td className="num"><Money value={a.value} /></td>
                          <td className="num"><Money value={a.baseline} /></td>
                          <td className="num">{a.changePct === null ? "–" : `${Number(a.changePct)}%`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
            <section className="card table-wrap">
              <h2>{t("review.history")}</h2>
              {w.signoffs.length === 0 ? <p className="muted">{t("common.none")}</p> : (
                <table>
                  <thead><tr><th>{t("review.step")}</th><th>{t("review.who")}</th><th>{t("return.when")}</th><th>{t("common.status")}</th></tr></thead>
                  <tbody>
                    {w.signoffs.map((s) => (
                      <tr key={s.id}>
                        <td>{t(`step.${s.step}`)}</td>
                        <td>{s.userName}</td>
                        <td className="ltr">{s.signedAt.slice(0, 16).replace("T", " ")}</td>
                        <td>{s.invalidatedAt ? <><Badge value="CANCELLED" group="signoff" /><br /><small className="muted">{s.invalidatedReason}</small></> : <Badge value="LIVE" group="signoff" />}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
            <section className="card"><Comments objectType="PERIOD" objectId={periodId} /></section>
          </>
        );
      }}
    </Query>
  );
}
