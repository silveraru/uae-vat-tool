import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AuditCaseView, AuditRequest, FafResult } from "../api/types";
import { DocumentList, uploadEvidence } from "../components/DocumentList";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, Field, useAction, useRoles } from "../components/ui";
import { fafWarning } from "./AuditCasesPage";

export function AuditCasePage() {
  const { id = "" } = useParams();
  const q = useQuery({ queryKey: ["audit-case", id], queryFn: () => api.get<AuditCaseView>(`/audit-cases/${id}`) });
  return (
    <Query query={q}>
      {(v) => <Body id={id} view={v} />}
    </Query>
  );
}

function Body({ id, view }: { id: string; view: AuditCaseView }) {
  const { t } = useTranslation();
  const key = ["audit-case", id];
  const c = view.auditCase;
  const roles = useRoles(c.entityId);
  const [request, setRequest] = useState({ description: "", dueOn: "" });
  const add = useAction(() => api.post(`/audit-cases/${id}/requests`, request), [key]);
  const [warnings, setWarnings] = useState<string[]>([]);
  const faf = useAction(() => api.post<FafResult>(`/audit-cases/${id}/faf`), [key]);
  const bundle = useAction(() => api.post(`/audit-cases/${id}/bundle`), [key]);
  const [closing, setClosing] = useState({ outcome: "", releaseHolds: true });
  const close = useAction(() => api.post(`/audit-cases/${id}/close`, closing), [key]);
  const isOpen = c.status === "OPEN";
  return (
    <>
      <div className="row">
        <h1 className="ltr">{c.ftaReference}</h1>
        <Badge value={c.status} />
      </div>
      <p className="muted">
        <Link to={`/entities/${c.entityId}/audit-cases`}>{t("auditCases.title")}</Link>
        {" · "}<span className="ltr">{c.periodFrom} → {c.periodTo}</span>
        {" · "}{t("auditCases.heldPeriods", { count: view.heldPeriods })}
      </p>
      {c.description && <p>{c.description}</p>}
      {c.outcome && <div className="alert ok" role="status">{c.outcome}</div>}
      <ErrorAlert error={add.error ?? faf.error ?? bundle.error ?? close.error} />
      <section className="card">
        <h2>{t("auditCases.requests")}</h2>
        {view.requests.length === 0 ? <p className="muted">{t("auditCases.noRequests")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("exceptions.description")}</th><th>{t("auditCases.dueOn")}</th><th>{t("common.status")}</th><th>{t("auditCases.response")}</th></tr></thead>
              <tbody>{view.requests.map((r) => <RequestRow key={r.id} request={r} entityId={c.entityId} canAct={roles.canAudit && isOpen} />)}</tbody>
            </table>
          </div>
        )}
        {roles.canAudit && isOpen && (
          <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => setRequest({ description: "", dueOn: "" }) }); }}>
            <div className="form-grid">
              <Field label={t("auditCases.whatAsked")}><input required value={request.description} onChange={(e) => setRequest({ ...request, description: e.target.value })} /></Field>
              <Field label={t("auditCases.dueOn")}><input type="date" required value={request.dueOn} onChange={(e) => setRequest({ ...request, dueOn: e.target.value })} /></Field>
            </div>
            <button type="submit" disabled={add.isPending}>{t("auditCases.addRequest")}</button>
          </form>
        )}
      </section>
      <section className="card">
        <h2>{t("auditCases.documents")}</h2>
        <p className="muted">{t("auditCases.bundleExplain")}</p>
        <DocumentList documents={view.documents} />
        {warnings.map((w) => <div key={w} className="alert warn" role="status">{fafWarning(t, w)}</div>)}
        {roles.canAudit && (
          <div className="row">
            <button type="button" disabled={faf.isPending} onClick={() => faf.mutate(undefined, { onSuccess: (r) => setWarnings((r as FafResult).warnings) })}>{t("auditCases.makeFaf")}</button>
            <button type="button" className="primary" disabled={bundle.isPending} onClick={() => bundle.mutate(undefined)}>{t("auditCases.makeBundle")}</button>
          </div>
        )}
      </section>
      {isOpen && roles.canCloseAudit && (
        <form className="card" onSubmit={(e) => { e.preventDefault(); close.mutate(undefined); }}>
          <h2>{t("auditCases.close")}</h2>
          <div className="form-grid">
            <Field label={t("auditCases.outcome")}><input required value={closing.outcome} onChange={(e) => setClosing({ ...closing, outcome: e.target.value })} /></Field>
          </div>
          <label className="check-row">
            <input type="checkbox" checked={closing.releaseHolds} onChange={(e) => setClosing({ ...closing, releaseHolds: e.target.checked })} />
            {t("auditCases.releaseHolds")}
          </label>
          <button type="submit" disabled={close.isPending}>{t("auditCases.closeButton")}</button>
        </form>
      )}
    </>
  );
}

function RequestRow({ request, entityId, canAct }: { request: AuditRequest; entityId: string; canAct: boolean }) {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const [note, setNote] = useState("");
  const file = useRef<HTMLInputElement>(null);
  const respond = useAction(async () => {
    const f = file.current?.files?.[0];
    const evidenceIds = f ? [await uploadEvidence(entityId, f, null, `Response to FTA: ${request.description}`)] : [];
    return api.post(`/audit-requests/${request.id}/response`, { note, evidenceIds });
  }, [["audit-case", id]]);
  return (
    <tr>
      <td>{request.description}</td>
      <td className="ltr">{request.dueOn}</td>
      <td><Badge value={request.status} /></td>
      <td>
        <ErrorAlert error={respond.error} />
        {request.status === "RESPONDED" ? request.responseNote : canAct && (
          <form onSubmit={(e) => { e.preventDefault(); respond.mutate(undefined); }}>
            <Field label={t("auditCases.whatGiven")}><input required value={note} onChange={(e) => setNote(e.target.value)} /></Field>
            <Field label={t("evidence.file")}><input ref={file} type="file" /></Field>
            <button type="submit" disabled={respond.isPending}>{t("auditCases.answer")}</button>
          </form>
        )}
      </td>
    </tr>
  );
}
