import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { Counterparty, PracticeOverview, RetentionRow, WatchPosition, WebhookDelivery, WebhookEndpoint } from "../api/types";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, Field, Money, ReasonAction, useAction, useRoles } from "../components/ui";

export function PracticePage() {
  const { t } = useTranslation();
  const roles = useRoles();
  return (
    <>
      <h1>{t("practice.title")}</h1>
      <Workload />
      <RegistrationWatch />
      {roles.isAdmin && <SupplierRegister />}
      {roles.isAdmin && <FxFeed />}
      {roles.isAdmin && <Webhooks />}
      {roles.isAdmin && <Retention />}
    </>
  );
}

function Workload() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ["workload"], queryFn: () => api.get<PracticeOverview>("/practice/workload") });
  return (
    <section className="card">
      <h2>{t("practice.workload")}</h2>
      <Query query={q}>
        {(o) => (
          <>
            {o.people.length === 0 ? <p className="muted">{t("practice.noAssignments")}</p> : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t("practice.person")}</th><th className="num">{t("practice.clients")}</th><th className="num">{t("practice.openPeriods")}</th><th className="num">{t("practice.dueSoon")}</th>
                      <th className="num">{t("practice.overdue")}</th><th className="num">{t("practice.exceptions")}</th><th className="num">{t("practice.blocking")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.people.map((p) => (
                      <tr key={p.userId}>
                        <td>{p.displayName}</td><td className="num">{p.entities}</td><td className="num">{p.openPeriods}</td><td className="num">{p.dueSoon}</td>
                        <td className="num">{p.overdue > 0 ? <span className="badge bad">{p.overdue}</span> : 0}</td>
                        <td className="num">{p.openExceptions}</td><td className="num">{p.blockingExceptions}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {o.unassigned.length > 0 && (
              <>
                <h3 style={{ marginBlockStart: 16 }}>{t("practice.unassigned")}</h3>
                <ul>{o.unassigned.map((u) => <li key={u.entityId}><Link to={`/entities/${u.entityId}`}>{u.legalName}</Link>{u.nextDue && <> <small className="muted">{t("portfolio.due")} <span className="ltr">{u.nextDue}</span></small></>}</li>)}</ul>
              </>
            )}
          </>
        )}
      </Query>
    </section>
  );
}

function RegistrationWatch() {
  const { t } = useTranslation();
  const key = ["registration-watch"];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<WatchPosition[]>("/registration-watch") });
  const [name, setName] = useState("");
  const add = useAction(() => api.post("/registration-watch", { name }), [key]);
  const [month, setMonth] = useState({ watchId: "", monthStart: "", taxableSuppliesAed: "", expectedNext30DaysAed: "" });
  const record = useAction(() => api.post(`/registration-watch/${month.watchId}/months`, { monthStart: month.monthStart, taxableSuppliesAed: month.taxableSuppliesAed, expectedNext30DaysAed: month.expectedNext30DaysAed || null }), [key]);
  return (
    <section className="card">
      <h2>{t("practice.watch")}</h2>
      <p className="muted">{t("practice.watchExplain")}</p>
      <ErrorAlert error={add.error ?? record.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("practice.client")}</th><th className="num">{t("practice.rolling")}</th><th className="num">{t("practice.pctOfLimit")}</th><th className="num">{t("practice.expected")}</th><th>{t("common.status")}</th></tr></thead>
              <tbody>
                {rows.map((w) => (
                  <tr key={w.watch.id}>
                    <td>{w.watch.name}</td><td className="num"><Money value={w.rollingAed} /></td><td className="num">{Number(w.pctOfMandatory).toFixed(0)}%</td><td className="num"><Money value={w.expectedNext30DaysAed} /></td>
                    <td><Badge value={w.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      <form className="row" style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => setName("") }); }}>
        <Field label={t("practice.addClient")}><input required value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <button type="submit" disabled={add.isPending}>{t("practice.add")}</button>
      </form>
      {q.data && q.data.length > 0 && (
        <form onSubmit={(e) => { e.preventDefault(); record.mutate(undefined, { onSuccess: () => setMonth({ ...month, monthStart: "", taxableSuppliesAed: "", expectedNext30DaysAed: "" }) }); }}>
          <h3>{t("practice.recordMonth")}</h3>
          <div className="form-grid">
            <Field label={t("practice.client")}>
              <select required value={month.watchId} onChange={(e) => setMonth({ ...month, watchId: e.target.value })}>
                <option value="">{t("common.choose")}</option>
                {q.data.map((w) => <option key={w.watch.id} value={w.watch.id}>{w.watch.name}</option>)}
              </select>
            </Field>
            <Field label={t("practice.monthStart")} hint={t("practice.monthStartHint")}><input type="date" required value={month.monthStart} onChange={(e) => setMonth({ ...month, monthStart: e.target.value })} /></Field>
            <Field label={t("practice.supplies")}><input type="number" step="0.01" min="0" required value={month.taxableSuppliesAed} onChange={(e) => setMonth({ ...month, taxableSuppliesAed: e.target.value })} /></Field>
            <Field label={t("practice.expectedInput")}><input type="number" step="0.01" min="0" value={month.expectedNext30DaysAed} onChange={(e) => setMonth({ ...month, expectedNext30DaysAed: e.target.value })} /></Field>
          </div>
          <button type="submit" disabled={record.isPending}>{t("common.save")}</button>
        </form>
      )}
    </section>
  );
}

/** What check R08 reads. A person records what they checked: nothing here comes from the FTA. */
function SupplierRegister() {
  const { t } = useTranslation();
  const key = ["counterparties"];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Counterparty[]>("/counterparties") });
  const blank = { trn: "", name: "", country: "AE", designatedZone: false, trnStatus: "ACTIVE", activeFrom: "", activeTo: "", checkedOn: "" };
  const [form, setForm] = useState(blank);
  const save = useAction(() => api.post("/counterparties", { ...form, activeFrom: form.activeFrom || null, activeTo: form.activeTo || null, checkedOn: form.checkedOn || null }), [key]);
  return (
    <section className="card">
      <h2>{t("practice.register")}</h2>
      <p className="muted">{t("practice.registerExplain")}</p>
      <ErrorAlert error={save.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>TRN</th><th>{t("entity.name")}</th><th>{t("common.status")}</th><th>{t("practice.registerChecked")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.id}>
                    <td className="ltr">{c.trn}</td><td>{c.name}</td><td>{t(`practice.trnStatus.${c.trnStatus}`)}</td><td className="ltr">{c.checkedOn ?? "–"}</td>
                    <td><button type="button" aria-label={t("practice.registerEditOne", { name: c.name })} onClick={() => setForm({ trn: c.trn, name: c.name, country: c.country, designatedZone: c.designatedZone, trnStatus: c.trnStatus, activeFrom: c.activeFrom ?? "", activeTo: c.activeTo ?? "", checkedOn: c.checkedOn ?? "" })}>{t("practice.registerEdit")}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); save.mutate(undefined, { onSuccess: () => setForm(blank) }); }}>
        <div className="form-grid">
          <Field label="TRN"><input required dir="ltr" value={form.trn} onChange={(e) => setForm({ ...form, trn: e.target.value })} /></Field>
          <Field label={t("entity.name")}><input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label={t("practice.registerStatus")}>
            <select value={form.trnStatus} onChange={(e) => setForm({ ...form, trnStatus: e.target.value })}>
              {["ACTIVE", "INACTIVE", "UNKNOWN"].map((s) => <option key={s} value={s}>{t(`practice.trnStatus.${s}`)}</option>)}
            </select>
          </Field>
          <Field label={t("practice.registerCountry")}><input dir="ltr" maxLength={2} value={form.country} onChange={(e) => setForm({ ...form, country: e.target.value.toUpperCase() })} /></Field>
          <Field label={t("practice.registerFrom")}><input type="date" value={form.activeFrom} onChange={(e) => setForm({ ...form, activeFrom: e.target.value })} /></Field>
          <Field label={t("practice.registerTo")}><input type="date" value={form.activeTo} onChange={(e) => setForm({ ...form, activeTo: e.target.value })} /></Field>
          <Field label={t("practice.registerChecked")}><input type="date" value={form.checkedOn} onChange={(e) => setForm({ ...form, checkedOn: e.target.value })} /></Field>
        </div>
        <label className="check-row"><input type="checkbox" checked={form.designatedZone} onChange={(e) => setForm({ ...form, designatedZone: e.target.checked })} /><span>{t("practice.registerZone")}</span></label>
        <button type="submit" disabled={save.isPending}>{t("common.save")}</button>
      </form>
    </section>
  );
}

function FxFeed() {
  const { t } = useTranslation();
  const [result, setResult] = useState<{ rows: number; added: number } | null>(null);
  const pull = useAction(() => api.post<{ rows: number; added: number }>("/fx/feed/pull"));
  return (
    <section className="card">
      <h2>{t("practice.fx")}</h2>
      <p className="muted">{t("practice.fxExplain")}</p>
      <ErrorAlert error={pull.error} />
      {result && <div className="alert ok" role="status">{t("practice.fxPulled", result)}</div>}
      <button type="button" disabled={pull.isPending} onClick={() => pull.mutate(undefined, { onSuccess: (r) => setResult(r as { rows: number; added: number }) })}>{t("practice.fxPull")}</button>
    </section>
  );
}

function Webhooks() {
  const { t } = useTranslation();
  const key = ["webhooks"];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<WebhookEndpoint[]>("/practice/webhooks") });
  const events = useQuery({ queryKey: ["webhook-events"], queryFn: () => api.get<string[]>("/practice/webhook-events") });
  const blank = { name: "", url: "", events: [] as string[] };
  const [form, setForm] = useState(blank);
  const [secret, setSecret] = useState<string | null>(null);
  const [shown, setShown] = useState<string | null>(null);
  const create = useAction(() => api.post<{ secret: string }>("/practice/webhooks", form), [key]);
  const setActive = useAction(({ id, active }: { id: string; active: boolean }) => api.post(`/practice/webhooks/${id}/active`, { active }), [key]);
  const toggle = (e: string) => setForm({ ...form, events: form.events.includes(e) ? form.events.filter((x) => x !== e) : [...form.events, e] });
  return (
    <section className="card">
      <h2>{t("practice.webhooks")}</h2>
      <p className="muted">{t("practice.webhooksExplain")}</p>
      <ErrorAlert error={create.error ?? setActive.error} />
      {secret && <div className="alert warn" role="status">{t("practice.secretOnce")} <code className="ltr">{secret}</code></div>}
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("entity.name")}</th><th>URL</th><th>{t("practice.events")}</th><th>{t("common.status")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((w) => (
                  <tr key={w.id}>
                    <td>{w.name}</td><td className="ltr">{w.url}</td><td className="ltr"><small>{w.events.join(", ")}</small></td>
                    <td><Badge value={w.active ? "ENABLED" : "PAUSED"} group="connectors.state" /></td>
                    <td className="row">
                      <button type="button" onClick={() => setActive.mutate({ id: w.id, active: !w.active })}>{w.active ? t("practice.pause") : t("practice.resume")}</button>
                      <button type="button" aria-expanded={shown === w.id} onClick={() => setShown(shown === w.id ? null : w.id)}>{t("practice.deliveries")}</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
      {shown && <Deliveries endpointId={shown} />}
      <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); create.mutate(undefined, { onSuccess: (r) => { setSecret((r as { secret: string }).secret); setForm(blank); } }); }}>
        <h3>{t("practice.addWebhook")}</h3>
        <div className="form-grid">
          <Field label={t("entity.name")}><input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label="URL"><input required type="url" dir="ltr" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} /></Field>
        </div>
        <fieldset className="card">
          <legend>{t("practice.events")}</legend>
          {events.data?.map((e) => (
            <label key={e} className="check-row"><input type="checkbox" checked={form.events.includes(e)} onChange={() => toggle(e)} /><span className="ltr">{e}</span></label>
          ))}
        </fieldset>
        <button type="submit" disabled={create.isPending || form.events.length === 0}>{t("practice.add")}</button>
      </form>
    </section>
  );
}

function Deliveries({ endpointId }: { endpointId: string }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ["webhook-deliveries", endpointId], queryFn: () => api.get<WebhookDelivery[]>(`/practice/webhooks/${endpointId}/deliveries`) });
  return (
    <Query query={q}>
      {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t("practice.event")}</th><th>{t("common.status")}</th><th className="num">{t("practice.attempts")}</th><th>{t("practice.lastResult")}</th></tr></thead>
            <tbody>{rows.map((d) => <tr key={d.id}><td className="ltr">{d.eventType}</td><td><Badge value={d.status} /></td><td className="num">{d.attempts}</td><td className="ltr">{d.lastError ?? d.lastStatus ?? "–"}</td></tr>)}</tbody>
          </table>
        </div>
      )}
    </Query>
  );
}

function Retention() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [client, setClient] = useState("");
  const q = useQuery({ queryKey: ["retention", client], queryFn: () => api.get<RetentionRow[]>(`/retention?limit=200${client ? `&client=${encodeURIComponent(client)}` : ""}`) });
  const mark = useAction(({ id, realEstate, reason }: { id: string; realEstate: boolean; reason: string }) => api.post(`/periods/${id}/real-estate-records`, { realEstate, reason }), [["retention"]]);
  return (
    <section className="card">
      <h2>{t("practice.retention")}</h2>
      <p className="muted">{t("practice.retentionExplain")}</p>
      <ErrorAlert error={mark.error} />
      <form className="row" onSubmit={(e) => { e.preventDefault(); setClient(search.trim()); }}>
        <Field label={t("practice.client")}><input value={search} onChange={(e) => setSearch(e.target.value)} /></Field>
        <button type="submit">{t("practice.find")}</button>
      </form>
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("portfolio.entity")}</th><th>{t("deadlines.periodEnd")}</th><th>{t("practice.retainUntil")}</th><th>{t("practice.holds")}</th><th>{t("common.status")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.periodId}>
                    <td>{r.legalName}</td><td className="ltr">{r.periodEnd}</td><td className="ltr">{r.retainUntil}{r.realEstateRecords && <> <small className="muted">{t("evidence.realEstate")}</small></>}</td>
                    <td className="num">{r.activeHolds}</td>
                    <td>{r.expired ? <span className="badge warn">{t("practice.pastRetention")}</span> : <span className="badge ok">{t("practice.kept")}</span>}</td>
                    <td><ReasonAction label={r.realEstateRecords ? t("practice.markStandard") : t("practice.markRealEstate")} pending={mark.isPending} onSubmit={(reason) => mark.mutate({ id: r.periodId, realEstate: !r.realEstateRecords, reason })} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
    </section>
  );
}
