import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { WorkflowStatus } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, ReasonAction, useAction, useRoles } from "../../components/ui";
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
                {state === "IN_REVIEW" && roles.isApprover && <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate({ path: "approve" })}>{t("review.approve")}</button>}
                {state === "IN_REVIEW" && roles.canWaive && <ReasonAction label={t("review.sendBack")} onSubmit={(reason) => act.mutate({ path: "send-back", body: { reason } })} />}
                {state === "APPROVED" && roles.isApprover && <ReasonAction label={t("review.withdraw")} danger onSubmit={(reason) => act.mutate({ path: "withdraw-approval", body: { reason } })} />}
              </div>
            </section>
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
