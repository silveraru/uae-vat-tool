import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AgentLink, Entity, Establishment, MappingEntry, MappingVersion, Period, Signatory } from "../api/types";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, Field, useAction, useRoles } from "../components/ui";
import { formatDate } from "../i18n";

const EMIRATES = ["AUH", "DXB", "SHJ", "AJM", "UAQ", "RAK", "FUJ"];
const TREATMENTS = ["STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "REVERSE_CHARGE", "IMPORT", "DRC", "TOURIST_REFUND"];

export function EntityPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const roles = useRoles(id);
  const entity = useQuery({ queryKey: ["entity", id], queryFn: () => api.get<Entity>(`/entities/${id}`) });
  return (
    <Query query={entity}>
      {(e) => (
        <>
          <div className="card">
            <h1>{e.legalName}</h1>
            <p className="muted">
              {t(`entityKind.${e.entityKind}`)}
              {e.trn && <> · TRN <span className="ltr">{e.trn}</span></>}
              {e.filingFrequency && <> · {t(`entityFrequency.${e.filingFrequency}`)}</>}
            </p>
          </div>
          {e.entityKind !== "GROUP_MEMBER" && <Periods entityId={id} admin={roles.isAdmin} />}
          {e.entityKind === "GROUP_REPRESENTATIVE" && <Members entityId={id} />}
          <Establishments entityId={id} admin={roles.isAdmin} />
          {e.entityKind !== "GROUP_MEMBER" && <Mappings entityId={id} admin={roles.isAdmin} />}
          <Agents entityId={id} admin={roles.isAdmin} />
        </>
      )}
    </Query>
  );
}

function Periods({ entityId, admin }: { entityId: string; admin: boolean }) {
  const { t } = useTranslation();
  const [through, setThrough] = useState("2027-03-31");
  const query = useQuery({ queryKey: ["periods", entityId], queryFn: () => api.get<Period[]>(`/entities/${entityId}/periods`) });
  const ensure = useAction((d: string) => api.post(`/entities/${entityId}/periods/ensure`, d), [["periods", entityId]]);
  return (
    <section className="card">
      <h2>{t("entity.periods")}</h2>
      <ErrorAlert error={ensure.error} />
      <Query query={query}>
        {(rows) => (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("portfolio.period")}</th><th>{t("portfolio.due")}</th><th>{t("common.status")}</th></tr></thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.id}>
                    <td><Link to={`/periods/${p.id}`} className="ltr">{p.periodStart} → {p.periodEnd}</Link></td>
                    <td className="ltr">{formatDate(p.dueDate)}</td>
                    <td><Badge value={p.state} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      {admin && (
        <form className="row" onSubmit={(e) => { e.preventDefault(); ensure.mutate(through); }}>
          <Field label={t("entity.addPeriodsThrough")}><input type="date" value={through} onChange={(e) => setThrough(e.target.value)} /></Field>
          <button type="submit">{t("entity.addPeriods")}</button>
        </form>
      )}
    </section>
  );
}

function Members({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["members", entityId], queryFn: () => api.get<Entity[]>(`/entities/${entityId}/members`) });
  return (
    <section className="card">
      <h2>{t("entity.members")}</h2>
      <Query query={query}>{(rows) => <ul>{rows.map((m) => <li key={m.id}><Link to={`/entities/${m.id}`}>{m.legalName}</Link> <span className="muted">{t(`entityKind.${m.entityKind}`)}</span></li>)}</ul>}</Query>
    </section>
  );
}

function Establishments({ entityId, admin }: { entityId: string; admin: boolean }) {
  const { t } = useTranslation();
  const blank = { code: "", name: "", emirate: "DXB", designatedZone: false, zoneName: "" };
  const [form, setForm] = useState(blank);
  const query = useQuery({ queryKey: ["establishments", entityId], queryFn: () => api.get<Establishment[]>(`/entities/${entityId}/establishments`) });
  const add = useAction((body: unknown) => api.post(`/entities/${entityId}/establishments`, body), [["establishments", entityId]]);
  function submit(e: FormEvent) {
    e.preventDefault();
    add.mutate({ ...form, zoneName: form.zoneName || null }, { onSuccess: () => setForm(blank) });
  }
  return (
    <section className="card">
      <h2>{t("entity.establishments")}</h2>
      <ErrorAlert error={add.error} />
      <Query query={query}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("entity.noEstablishments")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("entity.code")}</th><th>{t("entity.name")}</th><th>{t("entity.emirate")}</th><th>{t("entity.designatedZone")}</th></tr></thead>
              <tbody>
                {rows.map((x) => <tr key={x.id}><td className="ltr">{x.code}</td><td>{x.name}</td><td>{t(`emirate.${x.emirate}`)}</td><td>{x.designatedZone ? (x.zoneName ?? t("common.yes")) : t("common.no")}</td></tr>)}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      {admin && (
        <form onSubmit={submit} style={{ marginBlockStart: 12 }}>
          <div className="form-grid">
            <Field label={t("entity.code")}><input required dir="ltr" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} /></Field>
            <Field label={t("entity.name")}><input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
            <Field label={t("entity.emirate")}>
              <select value={form.emirate} onChange={(e) => setForm({ ...form, emirate: e.target.value })}>
                {EMIRATES.map((c) => <option key={c} value={c}>{t(`emirate.${c}`)}</option>)}
              </select>
            </Field>
            <Field label={t("entity.zoneName")}><input value={form.zoneName} onChange={(e) => setForm({ ...form, zoneName: e.target.value })} /></Field>
          </div>
          <label className="row" style={{ gap: 8, marginBlockEnd: 12 }}>
            <input type="checkbox" style={{ width: "auto" }} checked={form.designatedZone} onChange={(e) => setForm({ ...form, designatedZone: e.target.checked })} />
            {t("entity.designatedZone")}
          </label>
          <button type="submit">{t("entity.addEstablishment")}</button>
        </form>
      )}
    </section>
  );
}

function Mappings({ entityId, admin }: { entityId: string; admin: boolean }) {
  const { t } = useTranslation();
  const history = useQuery({ queryKey: ["mappings", entityId], queryFn: () => api.get<MappingVersion[]>(`/entities/${entityId}/mappings`) });
  const latestId = history.data?.[0]?.id;
  const latest = useQuery({
    queryKey: ["mapping", latestId],
    queryFn: () => api.get<{ entries: MappingEntry[] }>(`/entities/${entityId}/mappings/${latestId}`),
    enabled: !!latestId,
  });
  const [effectiveFrom, setEffectiveFrom] = useState("2027-01-01");
  const [note, setNote] = useState("");
  const [rows, setRows] = useState<MappingEntry[]>([{ matchType: "TAX_CODE", sourceCode: "", treatment: "STANDARD", blockedCategory: null, drcCategory: null }]);
  const publish = useAction((body: unknown) => api.post(`/entities/${entityId}/mappings`, body), [["mappings", entityId]]);
  const setRow = (i: number, patch: Partial<MappingEntry>) => setRows((r) => r.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  return (
    <section className="card">
      <h2>{t("entity.mappings")}</h2>
      <ErrorAlert error={publish.error} />
      <Query query={history}>
        {(versions) => versions.length === 0 ? <p className="alert warn">{t("entity.noMapping")}</p> : (
          <p className="muted">{t("entity.mappingVersion", { no: versions[0].versionNo, date: versions[0].effectiveFrom })}</p>
        )}
      </Query>
      {latest.data && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t("entity.matchType")}</th><th>{t("entity.sourceCode")}</th><th>{t("lines.treatment")}</th><th>{t("entity.blocked")}</th><th>{t("entity.drc")}</th></tr></thead>
            <tbody>
              {latest.data.entries.map((m) => (
                <tr key={`${m.matchType}-${m.sourceCode}`}>
                  <td>{t(`matchType.${m.matchType}`)}</td><td className="ltr">{m.sourceCode}</td>
                  <td>{m.treatment ? t(`treatment.${m.treatment}`) : "–"}</td><td>{m.blockedCategory ?? "–"}</td><td>{m.drcCategory ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {admin && (
        <form
          style={{ marginBlockStart: 12 }}
          onSubmit={(e) => {
            e.preventDefault();
            publish.mutate({ effectiveFrom, note: note || null, entries: rows.filter((r) => r.sourceCode.trim()) });
          }}
        >
          <h3>{t("entity.publishMapping")}</h3>
          <div className="form-grid">
            <Field label={t("entity.effectiveFrom")}><input type="date" required value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} /></Field>
            <Field label={t("common.note")}><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          </div>
          {rows.map((r, i) => (
            <div className="form-grid" key={i}>
              <Field label={t("entity.matchType")}>
                <select value={r.matchType} onChange={(e) => setRow(i, { matchType: e.target.value })}>
                  {["TAX_CODE", "GL_ACCOUNT", "VENDOR_TRN"].map((m) => <option key={m} value={m}>{t(`matchType.${m}`)}</option>)}
                </select>
              </Field>
              <Field label={t("entity.sourceCode")}><input dir="ltr" value={r.sourceCode} onChange={(e) => setRow(i, { sourceCode: e.target.value })} /></Field>
              <Field label={t("lines.treatment")}>
                <select value={r.treatment ?? ""} onChange={(e) => setRow(i, { treatment: e.target.value || null })}>
                  <option value="">–</option>
                  {TREATMENTS.map((x) => <option key={x} value={x}>{t(`treatment.${x}`)}</option>)}
                </select>
              </Field>
              <Field label={t("entity.blocked")}>
                <select value={r.blockedCategory ?? ""} onChange={(e) => setRow(i, { blockedCategory: e.target.value || null })}>
                  <option value="">–</option>
                  {["ENTERTAINMENT", "MOTOR_VEHICLE_PRIVATE", "EMPLOYEE_BENEFIT"].map((x) => <option key={x} value={x}>{x}</option>)}
                </select>
              </Field>
            </div>
          ))}
          <div className="row">
            <button type="button" onClick={() => setRows((r) => [...r, { matchType: "TAX_CODE", sourceCode: "", treatment: "STANDARD", blockedCategory: null, drcCategory: null }])}>{t("entity.addRow")}</button>
            <button type="submit" className="primary" disabled={publish.isPending}>{t("entity.publish")}</button>
          </div>
        </form>
      )}
    </section>
  );
}

function Agents({ entityId, admin }: { entityId: string; admin: boolean }) {
  const { t } = useTranslation();
  const agents = useQuery({ queryKey: ["agents", entityId], queryFn: () => api.get<AgentLink[]>(`/entities/${entityId}/agents`) });
  const signatories = useQuery({ queryKey: ["signatories", entityId], queryFn: () => api.get<Signatory[]>(`/entities/${entityId}/signatories`) });
  const [agent, setAgent] = useState({ agencyName: "", tan: "", agentName: "", taan: "" });
  const [sig, setSig] = useState({ name: "", email: "", position: "" });
  const linkAgent = useAction((body: unknown) => api.post(`/entities/${entityId}/agents`, body), [["agents", entityId]]);
  const addSig = useAction((body: unknown) => api.post(`/entities/${entityId}/signatories`, body), [["signatories", entityId]]);
  return (
    <section className="card">
      <h2>{t("entity.agents")}</h2>
      <ErrorAlert error={linkAgent.error ?? addSig.error} />
      <Query query={agents}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("entity.noAgent")}</p> : <ul>{rows.map((a) => <li key={a.taan}>{a.agentName} (TAAN <span className="ltr">{a.taan}</span>), {a.agencyName} (TAN <span className="ltr">{a.tan}</span>)</li>)}</ul>}
      </Query>
      {admin && (
        <form className="form-grid" onSubmit={(e) => { e.preventDefault(); linkAgent.mutate(agent); }}>
          <Field label={t("entity.agencyName")}><input required value={agent.agencyName} onChange={(e) => setAgent({ ...agent, agencyName: e.target.value })} /></Field>
          <Field label="TAN"><input required dir="ltr" value={agent.tan} onChange={(e) => setAgent({ ...agent, tan: e.target.value })} /></Field>
          <Field label={t("entity.agentName")}><input required value={agent.agentName} onChange={(e) => setAgent({ ...agent, agentName: e.target.value })} /></Field>
          <Field label="TAAN"><input required dir="ltr" value={agent.taan} onChange={(e) => setAgent({ ...agent, taan: e.target.value })} /></Field>
          <div><button type="submit">{t("entity.linkAgent")}</button></div>
        </form>
      )}
      <h3 style={{ marginBlockStart: 16 }}>{t("entity.signatories")}</h3>
      <Query query={signatories}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : <ul>{rows.map((s) => <li key={s.id}>{s.name} <span className="muted">{s.position} · {s.email}</span></li>)}</ul>}
      </Query>
      {admin && (
        <form className="form-grid" onSubmit={(e) => { e.preventDefault(); addSig.mutate({ ...sig, position: sig.position || null }); }}>
          <Field label={t("entity.name")}><input required value={sig.name} onChange={(e) => setSig({ ...sig, name: e.target.value })} /></Field>
          <Field label={t("entity.email")}><input type="email" required dir="ltr" value={sig.email} onChange={(e) => setSig({ ...sig, email: e.target.value })} /></Field>
          <Field label={t("entity.position")}><input value={sig.position} onChange={(e) => setSig({ ...sig, position: e.target.value })} /></Field>
          <div><button type="submit">{t("entity.addSignatory")}</button></div>
        </form>
      )}
    </section>
  );
}
