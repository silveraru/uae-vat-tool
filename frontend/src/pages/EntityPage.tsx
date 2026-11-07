import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AgentLink, AppUser, Assignment, CapitalAssetView, Connector, ConnectorProfileView, ConnectorRun, Entity, EntityGrant, Establishment, MappingEntry, MappingVersion, Period, Signatory } from "../api/types";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, Field, Money, useAction, useRoles } from "../components/ui";
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
            {e.entityKind !== "GROUP_MEMBER" && (
              <p className="row">
                <Link to={`/entities/${id}/analytics`}>{t("entity.analytics")}</Link>
                <Link to={`/entities/${id}/audit-cases`}>{t("entity.auditCases")}</Link>
              </p>
            )}
          </div>
          {e.entityKind !== "GROUP_MEMBER" && <Periods entityId={id} admin={roles.isAdmin} />}
          {e.entityKind !== "GROUP_MEMBER" && roles.isAdmin && <Settings entity={e} />}
          {e.entityKind !== "GROUP_MEMBER" && <Assignments entityId={id} canManage={roles.canManagePractice} />}
          {roles.isAdmin && <Access entityId={id} />}
          {e.entityKind !== "GROUP_MEMBER" && <CapitalAssets entityId={id} canEdit={roles.isReviewer} />}
          {e.entityKind !== "GROUP_MEMBER" && <Connectors entityId={id} admin={roles.isAdmin} canPull={roles.isPreparer} />}
          {e.entityKind === "GROUP_REPRESENTATIVE" && <Members entityId={id} />}
          <Establishments entityId={id} admin={roles.isAdmin} />
          {e.entityKind !== "GROUP_MEMBER" && <Mappings entityId={id} admin={roles.isAdmin} />}
          <Agents entityId={id} admin={roles.isAdmin} />
        </>
      )}
    </Query>
  );
}

function Settings({ entity }: { entity: Entity }) {
  const { t } = useTranslation();
  const [form, setForm] = useState({ ftaGiban: entity.ftaGiban ?? "", requireEsignature: entity.requireEsignature, taxYearStartMonth: entity.taxYearStartMonth ? String(entity.taxYearStartMonth) : "" });
  const save = useAction(() => api.put(`/entities/${entity.id}/settings`, {
    defaultRecoveryPct: entity.defaultRecoveryPct, partnerThresholdAed: entity.partnerThresholdAed, requireReviewer: entity.requireReviewer, einvoiceGoLive: entity.einvoiceGoLive,
    ftaGiban: form.ftaGiban || null, requireEsignature: form.requireEsignature, taxYearStartMonth: form.taxYearStartMonth ? Number(form.taxYearStartMonth) : null,
  }), [["entity", entity.id]]);
  return (
    <form className="card" onSubmit={(e) => { e.preventDefault(); save.mutate(undefined); }}>
      <h2>{t("entity.settings")}</h2>
      <ErrorAlert error={save.error} />
      {save.isSuccess && <div className="alert ok" role="status">{t("entity.saved")}</div>}
      <div className="form-grid">
        <Field label={t("entity.giban")} hint={t("entity.gibanHint")}><input dir="ltr" value={form.ftaGiban} onChange={(e) => setForm({ ...form, ftaGiban: e.target.value })} /></Field>
        <Field label={t("entity.taxYearMonth")} hint={t("entity.taxYearMonthHint")}><input type="number" min="1" max="12" value={form.taxYearStartMonth} onChange={(e) => setForm({ ...form, taxYearStartMonth: e.target.value })} /></Field>
      </div>
      <label className="check-row">
        <input type="checkbox" checked={form.requireEsignature} onChange={(e) => setForm({ ...form, requireEsignature: e.target.checked })} />
        {t("entity.requireEsignature")}
      </label>
      <button type="submit" disabled={save.isPending}>{t("common.save")}</button>
    </form>
  );
}

function Assignments({ entityId, canManage }: { entityId: string; canManage: boolean }) {
  const { t } = useTranslation();
  const key = ["assignments", entityId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Assignment[]>(`/entities/${entityId}/assignments`) });
  const users = useQuery({ queryKey: ["users"], queryFn: () => api.get<AppUser[]>("/users"), enabled: canManage, retry: false });
  const [form, setForm] = useState({ userId: "", duty: "PREPARER" });
  const add = useAction(() => api.post(`/entities/${entityId}/assignments`, form), [key]);
  const remove = useAction((id: string) => api.delete(`/entities/${entityId}/assignments/${id}`), [key]);
  return (
    <section className="card">
      <h2>{t("entity.assignments")}</h2>
      <ErrorAlert error={add.error ?? remove.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("entity.noAssignments")}</p> : (
          <ul>
            {rows.map((a) => (
              <li key={a.id}>
                {a.displayName} <span className="muted">{t(`entity.duty.${a.duty}`)}</span>
                {!a.hasAccess && <> <span className="badge warn">{t("entity.noAccess")}</span></>}
                {canManage && <> <button type="button" className="linklike" onClick={() => remove.mutate(a.id)}>{t("entity.remove")}</button></>}
              </li>
            ))}
          </ul>
        )}
      </Query>
      {canManage && users.data && (
        <form className="row" onSubmit={(e) => { e.preventDefault(); add.mutate(undefined); }}>
          <Field label={t("entity.person")}>
            <select required value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })}>
              <option value="">{t("common.choose")}</option>
              {users.data.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </select>
          </Field>
          <Field label={t("entity.dutyLabel")}>
            <select value={form.duty} onChange={(e) => setForm({ ...form, duty: e.target.value })}>
              {["PREPARER", "REVIEWER", "PARTNER"].map((d) => <option key={d} value={d}>{t(`entity.duty.${d}`)}</option>)}
            </select>
          </Field>
          <button type="submit" disabled={add.isPending}>{t("entity.assign")}</button>
        </form>
      )}
    </section>
  );
}

/** Who can open this client and with what role. An assignment only shows responsibility: access comes from here. */
function Access({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const key = ["grants", entityId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<EntityGrant[]>(`/entities/${entityId}/grants`) });
  const users = useQuery({ queryKey: ["users"], queryFn: () => api.get<AppUser[]>("/users"), retry: false });
  const blank = { userId: "", role: "PREPARER", validTo: "" };
  const [form, setForm] = useState(blank);
  const refresh = [key, ["assignments", entityId]];
  const add = useAction(() => api.post(`/entities/${entityId}/grants`, { userId: form.userId, role: form.role, validTo: form.validTo ? `${form.validTo}T23:59:59+04:00` : null }), refresh);
  const remove = useAction((grantId: string) => api.delete(`/entities/${entityId}/grants/${grantId}`), refresh);
  const person = (id: string) => users.data?.find((u) => u.id === id)?.displayName ?? id;
  return (
    <section className="card">
      <h2>{t("entity.access")}</h2>
      <p className="muted">{t("entity.accessExplain")}</p>
      <ErrorAlert error={add.error ?? remove.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <ul>
            {rows.map((g) => (
              <li key={g.id}>
                {person(g.userId)} <span className="muted">{t(`role.${g.role}`)}</span>
                {g.validTo && <> <small className="muted">{t("entity.accessUntil")} <span className="ltr">{g.validTo.slice(0, 10)}</span></small></>}
                {g.entityId === null
                  ? <> <small className="muted">{t("entity.accessAllClients")}</small></>
                  : <> <button type="button" className="linklike" aria-label={t("entity.accessRemoveOne", { name: person(g.userId), role: t(`role.${g.role}`) })} onClick={() => remove.mutate(g.id)}>{t("entity.remove")}</button></>}
              </li>
            ))}
          </ul>
        )}
      </Query>
      <form className="row" onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => setForm(blank) }); }}>
        <Field label={t("entity.person")}>
          <select required value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })}>
            <option value="">{t("common.choose")}</option>
            {users.data?.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
          </select>
        </Field>
        <Field label={t("entity.accessRole")}>
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
            {["PREPARER", "TAX_REVIEWER", "APPROVER", "ENGAGEMENT_PARTNER", "FIRM_ADMIN", "AUDITOR"].map((r) => <option key={r} value={r}>{t(`role.${r}`)}</option>)}
          </select>
        </Field>
        <Field label={t("entity.accessUntil")} hint={form.role === "AUDITOR" ? t("entity.accessAuditorHint") : undefined}><input type="date" required={form.role === "AUDITOR"} value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} /></Field>
        <button type="submit" disabled={add.isPending}>{t("entity.accessGrant")}</button>
      </form>
    </section>
  );
}

function CapitalAssets({ entityId, canEdit }: { entityId: string; canEdit: boolean }) {
  const { t } = useTranslation();
  const key = ["capital-assets", entityId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<CapitalAssetView[]>(`/entities/${entityId}/capital-assets`) });
  const blank = { reference: "", description: "", category: "REAL_ESTATE", costAed: "", inputVatAed: "", initialRecoveryPct: "100", firstUsedOn: "" };
  const [form, setForm] = useState(blank);
  const add = useAction(() => api.post(`/entities/${entityId}/capital-assets`, form), [key]);
  return (
    <section className="card">
      <h2>{t("entity.capitalAssets")}</h2>
      <p className="muted">{t("entity.capitalAssetsExplain")}</p>
      <ErrorAlert error={add.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("entity.code")}</th><th>{t("exceptions.description")}</th><th className="num">{t("entity.cost")}</th><th className="num">{t("entity.inputVat")}</th><th className="num">{t("entity.years")}</th><th>{t("entity.nextInterval")}</th></tr></thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.asset.id}>
                    <td className="ltr">{v.asset.reference}</td><td>{v.asset.description}</td><td className="num"><Money value={v.asset.costAed} /></td><td className="num"><Money value={v.asset.inputVatAed} /></td>
                    <td className="num">{v.intervals.length} / {v.asset.adjustmentYears}</td>
                    <td className="ltr">{v.asset.disposedOn ? t("entity.disposed") : (v.nextIntervalEnd ?? t("entity.finished"))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      {canEdit && (
        <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => setForm(blank) }); }}>
          <div className="form-grid">
            <Field label={t("entity.code")}><input required dir="ltr" value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></Field>
            <Field label={t("exceptions.description")}><input required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
            <Field label={t("entity.assetCategory")}>
              <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
                <option value="REAL_ESTATE">{t("evidence.realEstate")}</option>
                <option value="OTHER">{t("entity.otherAsset")}</option>
              </select>
            </Field>
            <Field label={t("entity.cost")}><input type="number" step="0.01" required value={form.costAed} onChange={(e) => setForm({ ...form, costAed: e.target.value })} /></Field>
            <Field label={t("entity.inputVat")}><input type="number" step="0.01" required value={form.inputVatAed} onChange={(e) => setForm({ ...form, inputVatAed: e.target.value })} /></Field>
            <Field label={t("entity.initialPct")}><input type="number" step="0.01" min="0" max="100" required value={form.initialRecoveryPct} onChange={(e) => setForm({ ...form, initialRecoveryPct: e.target.value })} /></Field>
            <Field label={t("entity.firstUsed")}><input type="date" required value={form.firstUsedOn} onChange={(e) => setForm({ ...form, firstUsedOn: e.target.value })} /></Field>
          </div>
          <button type="submit" disabled={add.isPending}>{t("entity.addAsset")}</button>
        </form>
      )}
    </section>
  );
}

function Connectors({ entityId, admin, canPull }: { entityId: string; admin: boolean; canPull: boolean }) {
  const { t } = useTranslation();
  const key = ["connectors", entityId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Connector[]>(`/entities/${entityId}/connectors`) });
  const profiles = useQuery({ queryKey: ["connector-profiles"], queryFn: () => api.get<ConnectorProfileView[]>("/connector-profiles") });
  const blank = { system: "", baseUrl: "", authKind: "BEARER", secret: "", enabled: false };
  const [form, setForm] = useState(blank);
  const add = useAction(() => api.post(`/entities/${entityId}/connectors`, form), [key]);
  const [pull, setPull] = useState({ from: "", to: "" });
  const sync = useAction(({ id, kind }: { id: string; kind: string }) => api.post(`/connectors/${id}/sync`, { kind, ...pull }), [key, ["batches", entityId]]);
  const restProfiles = profiles.data?.filter((p) => p.transport === "REST") ?? [];
  return (
    <section className="card">
      <h2>{t("connectors.title")}</h2>
      <div className="alert warn" role="status">{t("connectors.unverifiedAll")}</div>
      <ErrorAlert error={add.error ?? sync.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("connectors.none")}</p> : (
          <>
            {canPull && (
              <div className="form-grid">
                <Field label={t("connectors.from")}><input type="date" value={pull.from} onChange={(e) => setPull({ ...pull, from: e.target.value })} /></Field>
                <Field label={t("connectors.to")}><input type="date" value={pull.to} onChange={(e) => setPull({ ...pull, to: e.target.value })} /></Field>
              </div>
            )}
            {rows.map((c) => (
              <div key={c.id} className="row" style={{ marginBlockEnd: 8 }}>
                <strong>{c.system}</strong> <span className="ltr muted">{c.baseUrl}</span>
                <Badge value={c.enabled ? "ENABLED" : "PAUSED"} group="connectors.state" />
                {canPull && c.kinds.map((k) => <button key={k} type="button" disabled={sync.isPending || !pull.from || !pull.to} onClick={() => sync.mutate({ id: c.id, kind: k })}>{t("connectors.pull", { kind: t(`kind.${k}`) })}</button>)}
                <ConnectorRuns connectorId={c.id} />
              </div>
            ))}
          </>
        )}
      </Query>
      {admin && (
        <form onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => setForm(blank) }); }}>
          <h3>{t("connectors.add")}</h3>
          <div className="form-grid">
            <Field label={t("connectors.system")}>
              <select required value={form.system} onChange={(e) => setForm({ ...form, system: e.target.value })}>
                <option value="">{t("common.choose")}</option>
                {restProfiles.map((p) => <option key={p.system} value={p.system}>{p.label}</option>)}
              </select>
            </Field>
            <Field label={t("connectors.baseUrl")}><input required type="url" dir="ltr" value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} /></Field>
            <Field label={t("connectors.auth")}>
              <select value={form.authKind} onChange={(e) => setForm({ ...form, authKind: e.target.value })}>
                {["BEARER", "BASIC", "API_KEY", "NONE"].map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </Field>
            <Field label={t("connectors.secret")}><input type="password" autoComplete="off" dir="ltr" value={form.secret} onChange={(e) => setForm({ ...form, secret: e.target.value })} /></Field>
          </div>
          <label className="check-row">
            <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
            {t("connectors.daily")}
          </label>
          <button type="submit" disabled={add.isPending}>{t("connectors.connect")}</button>
        </form>
      )}
    </section>
  );
}

function ConnectorRuns({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ["connector-runs", connectorId], queryFn: () => api.get<ConnectorRun[]>(`/connectors/${connectorId}/runs`) });
  const last = q.data?.[0];
  if (!last) return null;
  return <small className="muted">{t("connectors.lastRun", { status: last.status, rows: last.rowsFetched, when: last.finishedAt.slice(0, 16).replace("T", " ") })}</small>;
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
