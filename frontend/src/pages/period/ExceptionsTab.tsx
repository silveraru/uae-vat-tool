import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { AppUser, MatchProposal, ReconException, ReconSummary, TimingItem } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, ReasonAction, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";
import { Comments } from "./ReturnTab";

const OPEN = ["OPEN", "ASSIGNED"];

export function ExceptionsTab() {
  const { t } = useTranslation();
  const { periodId, view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const [showClosed, setShowClosed] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const key = ["exceptions", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<ReconException[]>(`/periods/${periodId}/exceptions`), refetchInterval: 5000 });
  const proposals = useQuery({ queryKey: ["proposals", periodId], queryFn: () => api.get<MatchProposal[]>(`/periods/${periodId}/match-proposals`) });
  const refresh = [key, ["return", periodId], ["workflow", periodId], ["proposals", periodId], ["portfolio"]];
  const run = useAction(() => api.post<ReconSummary>(`/periods/${periodId}/reconcile`), refresh);
  const waive = useAction(({ id, reason }: { id: string; reason: string }) => api.post(`/exceptions/${id}/waive`, { reason }), refresh);
  const resolve = useAction(({ id, code }: { id: string; code: string }) => api.post(`/exceptions/${id}/resolve`, { resolutionCode: code }), refresh);
  const decide = useAction(({ id, accept }: { id: string; accept: boolean }) => api.post(`/periods/${periodId}/match-proposals/${id}`, { accept }), refresh);
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state);

  return (
    <>
      <ErrorAlert error={run.error ?? waive.error ?? resolve.error ?? decide.error} />
      <div className="row" style={{ marginBlockEnd: 12 }}>
        {!locked && <button type="button" className="primary" onClick={() => run.mutate(undefined)} disabled={run.isPending}>{run.isPending ? t("exceptions.running") : t("exceptions.run")}</button>}
        <label className="row" style={{ gap: 6 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          {t("exceptions.showClosed")}
        </label>
      </div>
      <Query query={q}>
        {(rows) => {
          const open = rows.filter((e) => OPEN.includes(e.status));
          const blocking = open.filter((e) => e.severity === "BLOCKING").length;
          const visible = showClosed ? rows : open;
          return (
            <>
              <div className={`alert ${blocking > 0 ? "bad" : "ok"}`} role="status">
                {blocking > 0 ? t("exceptions.blockingCount", { count: blocking }) : t("exceptions.noBlocking")}
                {" "}{t("exceptions.warningCount", { count: open.length - blocking })}
              </div>
              {visible.length === 0 ? <p className="card muted">{t("exceptions.none")}</p> : (
                <div className="card table-wrap">
                  <table>
                    <thead>
                      <tr><th>{t("exceptions.check")}</th><th>{t("exceptions.severity")}</th><th>{t("common.status")}</th><th>{t("exceptions.issue")}</th><th className="num">{t("exceptions.amount")}</th><th>{t("common.actions")}</th></tr>
                    </thead>
                    <tbody>
                      {visible.map((e) => (
                        <ExceptionRow
                          key={e.id} e={e} expanded={expanded === e.id} onToggle={() => setExpanded(expanded === e.id ? null : e.id)}
                          canWaive={roles.canWaive && !locked && OPEN.includes(e.status)} canResolve={roles.isPreparer && !locked && OPEN.includes(e.status)}
                          onWaive={(reason) => waive.mutate({ id: e.id, reason })} onResolve={(code) => resolve.mutate({ id: e.id, code })}
                        />
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          );
        }}
      </Query>
      <Query query={proposals}>
        {(rows) => rows.length === 0 ? null : (
          <section className="card table-wrap">
            <h2>{t("exceptions.proposals")}</h2>
            <p className="muted">{t("exceptions.proposalsExplain")}</p>
            <table>
              <thead><tr><th>{t("exceptions.erpDoc")}</th><th>{t("exceptions.einvoice")}</th><th className="num">{t("return.amount")}</th><th className="num">{t("exceptions.score")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((m) => (
                  <tr key={m.id}>
                    <td className="ltr">{m.docNumber} ({m.docDate})</td>
                    <td className="ltr">{m.invoiceNumber} ({m.issueDate})</td>
                    <td className="num"><Money value={m.docNet} /> / <Money value={m.invoiceNet} /></td>
                    <td className="num">{m.score}</td>
                    <td>
                      <button type="button" onClick={() => decide.mutate({ id: m.id, accept: true })}>{t("exceptions.accept")}</button>{" "}
                      <button type="button" onClick={() => decide.mutate({ id: m.id, accept: false })}>{t("exceptions.reject")}</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </Query>
      <TimingItems locked={locked} />
    </>
  );
}

function ExceptionRow({ e, expanded, onToggle, canWaive, canResolve, onWaive, onResolve }: {
  e: ReconException; expanded: boolean; onToggle: () => void; canWaive: boolean; canResolve: boolean; onWaive: (r: string) => void; onResolve: (c: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      <tr>
        <td><button type="button" className="linklike" onClick={onToggle} aria-expanded={expanded}>{e.ruleId}</button><br /><small className="muted">{t(`rule.${e.ruleId}`)}</small></td>
        <td><Badge value={e.severity} group="severity" /></td>
        <td><Badge value={e.status} />{e.carriedFromId && <><br /><small className="muted">{t("exceptions.carried", { since: e.firstOpenedAt.slice(0, 10) })}</small></>}</td>
        <td>{e.message}{e.waiverReason && <><br /><small className="muted">{t("exceptions.waived", { reason: e.waiverReason })}</small></>}</td>
        <td className="num">{e.amountAed !== null ? <Money value={e.amountAed} /> : "–"}</td>
        <td>
          {canWaive && <ReasonAction label={t("exceptions.waive")} onSubmit={onWaive} />}
          {canResolve && <button type="button" onClick={() => onResolve("FIXED_AT_SOURCE")}>{t("exceptions.markFixed")}</button>}
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={6} style={{ background: "var(--surface-2)" }}>
            <Assign exception={e} />
            <pre className="ltr raw">{JSON.stringify(JSON.parse(e.details), null, 1)}</pre>
            <Comments objectType="EXCEPTION" objectId={e.id} />
          </td>
        </tr>
      )}
    </>
  );
}

function Assign({ exception }: { exception: ReconException }) {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const [owner, setOwner] = useState(exception.ownerId ?? "");
  const users = useQuery({ queryKey: ["users"], queryFn: () => api.get<AppUser[]>("/users"), enabled: roles.isAdmin });
  const assign = useAction((ownerId: string) => api.post(`/exceptions/${exception.id}/assign`, { ownerId }), [["exceptions", periodId]]);
  const options = users.data ?? [];
  if (options.length === 0) return null;
  return (
    <form className="row" onSubmit={(e) => { e.preventDefault(); assign.mutate(owner); }}>
      <ErrorAlert error={assign.error} />
      <Field label={t("exceptions.owner")}>
        <select value={owner} onChange={(e) => setOwner(e.target.value)} required>
          <option value="">{t("common.choose")}</option>
          {options.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
        </select>
      </Field>
      <button type="submit">{t("exceptions.assign")}</button>
    </form>
  );
}

function TimingItems({ locked }: { locked: boolean }) {
  const { t } = useTranslation();
  const { periodId } = usePeriod();
  const [form, setForm] = useState({ accountType: "OUTPUT", amountAed: "", description: "" });
  const key = ["timing", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<TimingItem[]>(`/periods/${periodId}/timing-items`) });
  const add = useAction((body: unknown) => api.post(`/periods/${periodId}/timing-items`, body), [key]);
  return (
    <section className="card">
      <h2>{t("exceptions.timing")}</h2>
      <p className="muted">{t("exceptions.timingExplain")}</p>
      <ErrorAlert error={add.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <ul>{rows.map((i) => <li key={i.id}>{i.account_type}: <Money value={i.amount_aed} /> · {i.description}</li>)}</ul>
        )}
      </Query>
      {!locked && (
        <form className="form-grid" onSubmit={(e) => { e.preventDefault(); add.mutate(form, { onSuccess: () => setForm({ ...form, amountAed: "", description: "" }) }); }}>
          <Field label={t("exceptions.account")}>
            <select value={form.accountType} onChange={(e) => setForm({ ...form, accountType: e.target.value })}>
              {["OUTPUT", "INPUT", "REVERSE_CHARGE", "IMPORT"].map((x) => <option key={x} value={x}>{x}</option>)}
            </select>
          </Field>
          <Field label={t("return.amount")}><input type="number" step="0.01" required value={form.amountAed} onChange={(e) => setForm({ ...form, amountAed: e.target.value })} /></Field>
          <Field label={t("exceptions.description")}><input required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
          <div><button type="submit" disabled={add.isPending}>{t("exceptions.addTiming")}</button></div>
        </form>
      )}
    </section>
  );
}
