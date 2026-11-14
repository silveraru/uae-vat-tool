import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { ClientRules, ClientRulesView, DecisionRule, PatternRule, RulePackDefinition, RulePackView, SuggestionKeyword } from "../api/types";
import { Query } from "../components/Query";
import { ClientRulesTables, PackSections } from "../components/RuleBook";
import { Badge, ErrorAlert, Field, ReasonAction, useAction } from "../components/ui";
import { formatDate, isoDate } from "../i18n";

const TREATMENTS = ["STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "REVERSE_CHARGE", "IMPORT", "DRC", "TOURIST_REFUND"];
const DRC_CATEGORIES = ["GOLD", "DIAMONDS", "PRECIOUS_METALS_STONES", "HYDROCARBONS", "ELECTRONIC_DEVICES", "SCRAP_METAL"];
const BLOCKED_CATEGORIES = ["ENTERTAINMENT", "MOTOR_VEHICLE_PRIVATE", "EMPLOYEE_BENEFIT"];
const EMPTY: ClientRules = { classificationRules: [], drcPatterns: [], blockedPatterns: [], switchedOff: [], reconciliation: null, analytics: null, suggestionKeywords: [] };

/**
 * The rules that apply to this client: the firm's pack, and anything the client has on top of it.
 * Everyone who works on the client can read them. Only a firm admin can change them, and each change
 * is a new version that waits for sign-off.
 */
export function ClientRulesSection({ entityId, admin }: { entityId: string; admin: boolean }) {
  const { t } = useTranslation();
  const view = useQuery({ queryKey: ["client-rules", entityId], queryFn: () => api.get<ClientRulesView>(`/entities/${entityId}/client-rules`) });
  const firm = useQuery({ queryKey: ["entity-rule-pack", entityId], queryFn: () => api.get<RulePackView>(`/entities/${entityId}/rule-pack`) });
  const signOff = useAction(({ versionId, note }: { versionId: string; note: string }) =>
    api.post(`/entities/${entityId}/client-rules/${versionId}/sign-off`, { note }), [["client-rules", entityId], ["client-rules"]]);
  const [editing, setEditing] = useState(false);
  return (
    <section className="card" id="client-rules">
      <h2>{t("clientRules.title")}</h2>
      <p className="muted">{t("clientRules.explainShort")}</p>
      <ErrorAlert error={signOff.error} />
      <Query query={view}>
        {(v) => {
          const latest = v.versions[0];
          const today = isoDate(new Date());
          const upcoming = latest && latest.effectiveFrom > today ? latest : null;
          return (
            <>
              <p className="row">
                <span>{t("clientRules.firmPackLine")}</span>
                {admin ? <Link className="ltr" to={`/rule-packs/${v.firmPack.id}`}>{v.firmPack.version}</Link> : <span className="ltr">{v.firmPack.version}</span>}
                <Badge value={v.firmPack.status} />
              </p>
              {v.inForce ? (
                <>
                  <p className="row">
                    <strong>{t("clientRules.inForce", { no: v.inForce.versionNo, from: v.inForce.effectiveFrom })}</strong>
                    <Badge value={v.inForce.status} />
                    <span className="muted">{v.inForce.note}</span>
                  </p>
                  {v.inForce.status === "DRAFT" && <p className="alert warn">{t("clientRules.draftWarning")}</p>}
                  <ClientRulesTables rules={v.inForce.content} firm={firm.data?.definition} />
                </>
              ) : (
                <p className="muted">{t("clientRules.noneInForce")}</p>
              )}
              {upcoming && <p className="alert warn">{t("clientRules.upcoming", { no: upcoming.versionNo, from: upcoming.effectiveFrom })}</p>}
              {v.versions.length > 0 && (
                <details>
                  <summary>{t("clientRules.history", { count: v.versions.length })}</summary>
                  <div className="table-wrap">
                    <table>
                      <thead><tr><th>{t("rulePacks.version")}</th><th>{t("entity.effectiveFrom")}</th><th>{t("common.status")}</th><th>{t("common.note")}</th><th>{t("clientRules.by")}</th><th>{t("rulePacks.signedOff")}</th><th>{t("common.actions")}</th></tr></thead>
                      <tbody>
                        {v.versions.map((x) => (
                          <tr key={x.id}>
                            <td className="num">{x.versionNo}</td>
                            <td className="ltr">{x.effectiveFrom}</td>
                            <td><Badge value={x.status} /></td>
                            <td>{x.note}{x.content && countRules(x.content) === 0 && <div className="muted">{t("clientRules.ended")}</div>}</td>
                            <td>{x.createdByName ?? x.createdBy} · {formatDate(x.createdAt)}</td>
                            <td>{x.signedOffAt ? <>{x.signedOffByName ?? x.signedOffBy} · {formatDate(x.signedOffAt)}{x.signOffNote && <div className="muted">{x.signOffNote}</div>}</> : "–"}</td>
                            <td>{admin && x.status === "DRAFT" && <ReasonAction label={t("rulePacks.signOff")} onSubmit={(note) => signOff.mutate({ versionId: x.id, note })} pending={signOff.isPending} />}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </details>
              )}
              {firm.data && (
                <details>
                  <summary>{t("clientRules.showFirmRules", { version: firm.data.pack.version })}</summary>
                  <PackSections definition={firm.data.definition} switchedOff={v.inForce?.content.switchedOff.map((s) => s.id)} />
                </details>
              )}
              {admin && firm.data && (editing ? (
                <ClientRulesEditor entityId={entityId} start={latest?.content ?? EMPTY} firm={firm.data.definition} onDone={() => setEditing(false)} />
              ) : (
                <p><button type="button" className="primary" onClick={() => setEditing(true)}>{t("clientRules.change")}</button></p>
              ))}
            </>
          );
        }}
      </Query>
    </section>
  );
}

function countRules(r: ClientRules) {
  return r.classificationRules.length + r.drcPatterns.length + r.blockedPatterns.length + r.switchedOff.length + r.suggestionKeywords.length
    + (r.reconciliation ? 1 : 0) + (r.analytics ? 1 : 0);
}

type Recon = NonNullable<ClientRules["reconciliation"]>;
type Analytics = NonNullable<ClientRules["analytics"]>;

/** The whole set is edited and published together, so a version always says everything that applied from its date. */
function ClientRulesEditor({ entityId, start, firm, onDone }: { entityId: string; start: ClientRules; firm: RulePackDefinition; onDone: () => void }) {
  const { t } = useTranslation();
  const [rules, setRules] = useState<ClientRules>(() => structuredClone(start));
  const [effectiveFrom, setEffectiveFrom] = useState(() => isoDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
  const [note, setNote] = useState("");
  const publish = useAction(async () => {
    await api.post(`/entities/${entityId}/client-rules`, { effectiveFrom, note, content: rules });
    onDone();
  }, [["client-rules", entityId], ["client-rules"]]);
  const set = <K extends keyof ClientRules>(key: K, value: ClientRules[K]) => setRules((r) => ({ ...r, [key]: value }));
  const firmIds = [
    ...(firm.classification?.rules ?? []).map((r) => ({ id: r.id, label: r.description ?? r.id })),
    ...(firm.classification?.drcPatterns ?? []).map((p) => ({ id: p.id, label: t(`pack.categories.${p.category}`, { defaultValue: p.category }) })),
    ...(firm.classification?.blockedPatterns ?? []).map((p) => ({ id: p.id, label: t(`pack.categories.${p.category}`, { defaultValue: p.category }) })),
  ];
  const recon = rules.reconciliation ?? { perInvoiceToleranceAed: null, glToleranceAed: null, boxSwingPct: null, fuzzyDateWindowDays: null };
  const analytics = rules.analytics ?? { newSupplierNetAed: null, zeroRatedGrowthMultiple: null, zeroRatedMinAed: null, trailingPeriods: null };
  return (
    <form className="editor" onSubmit={(e) => { e.preventDefault(); publish.mutate(undefined); }}>
      <h3>{t("clientRules.editTitle")}</h3>
      <p className="muted">{t("clientRules.editExplain")}</p>

      <Rows title={t("pack.decisionRules")} hint={t("clientRules.decisionHint")} rows={rules.classificationRules}
        onChange={(rows) => set("classificationRules", rows)}
        blank={(): DecisionRule => ({ id: `CL-${rules.classificationRules.length + 1}`, description: "", when: { direction: "IN" }, then: { treatment: "STANDARD" } })}
        render={(r, update) => (
          <div className="form-grid">
            <Field label={t("pack.id")}><input dir="ltr" required value={r.id} onChange={(e) => update({ ...r, id: e.target.value })} /></Field>
            <Field label={t("pack.description")}><input value={r.description ?? ""} onChange={(e) => update({ ...r, description: e.target.value })} /></Field>
            <Field label={t("pack.field.direction")}>
              <Choice value={r.when.direction} options={["IN", "OUT"]} label={(x) => t(`direction.${x}`)} onChange={(x) => update({ ...r, when: { ...r.when, direction: x } })} />
            </Field>
            <Field label={t("pack.field.goodsOrServices")}>
              <Choice value={r.when.goodsOrServices} options={["GOODS", "SERVICES"]} label={(x) => t(`pack.gos.${x}`)} onChange={(x) => update({ ...r, when: { ...r.when, goodsOrServices: x } })} />
            </Field>
            <Field label={t("pack.field.counterpartyInUae")}>
              <Choice value={r.when.counterpartyInUae == null ? null : String(r.when.counterpartyInUae)} options={["true", "false"]}
                label={(x) => (x === "true" ? t("common.yes") : t("common.no"))}
                onChange={(x) => update({ ...r, when: { ...r.when, counterpartyInUae: x == null ? null : x === "true" } })} />
            </Field>
            <Field label={t("pack.field.descriptionMatches")} hint={t("clientRules.patternHint")}>
              <input dir="ltr" value={r.when.descriptionMatches ?? ""} onChange={(e) => update({ ...r, when: { ...r.when, descriptionMatches: e.target.value || null } })} />
            </Field>
            <Field label={t("lines.treatment")}>
              <Choice value={r.then.treatment} options={TREATMENTS} label={(x) => t(`treatment.${x}`)} onChange={(x) => update({ ...r, then: { ...r.then, treatment: x } })} />
            </Field>
            <Field label={t("pack.field.flag")} hint={t("clientRules.flagHint")}>
              <input dir="ltr" value={r.then.flag ?? ""} onChange={(e) => update({ ...r, then: { ...r.then, flag: e.target.value || null } })} />
            </Field>
          </div>
        )} />

      <Rows title={t("pack.drcPatterns")} hint={t("clientRules.drcHint")} rows={rules.drcPatterns} onChange={(rows) => set("drcPatterns", rows)}
        blank={(): PatternRule => ({ id: `CL-DRC-${rules.drcPatterns.length + 1}`, category: "GOLD", patterns: [] })}
        render={(p, update) => <PatternFields p={p} update={update} categories={DRC_CATEGORIES} />} />

      <Rows title={t("pack.blockedPatterns")} hint={t("clientRules.blockedHint")} rows={rules.blockedPatterns} onChange={(rows) => set("blockedPatterns", rows)}
        blank={(): PatternRule => ({ id: `CL-BLOCK-${rules.blockedPatterns.length + 1}`, category: "ENTERTAINMENT", patterns: [], direction: "IN" })}
        render={(p, update) => <PatternFields p={p} update={update} categories={BLOCKED_CATEGORIES} />} />

      <Rows title={t("clientRules.switchedOff")} hint={t("clientRules.switchHint")} rows={rules.switchedOff} onChange={(rows) => set("switchedOff", rows)}
        blank={() => ({ id: firmIds[0]?.id ?? "", reason: "" })}
        render={(s, update) => (
          <div className="form-grid">
            <Field label={t("clientRules.firmRule")}>
              <select value={s.id} onChange={(e) => update({ ...s, id: e.target.value })}>
                {firmIds.map((f) => <option key={f.id} value={f.id}>{f.id} · {f.label}</option>)}
              </select>
            </Field>
            <Field label={t("common.reason")}><input required value={s.reason} onChange={(e) => update({ ...s, reason: e.target.value })} /></Field>
          </div>
        )} />

      <fieldset>
        <legend>{t("pack.reconciliation")}</legend>
        <p className="muted">{t("clientRules.figureHint")}</p>
        <div className="form-grid">
          {(Object.keys(recon) as (keyof Recon)[]).map((k) => (
            <Field key={k} label={t(`pack.field.${k}`)}>
              <NumberInput value={recon[k]} placeholder={String(firm.reconciliation[k] ?? "")}
                onChange={(x) => set("reconciliation", { ...recon, [k]: x })} />
            </Field>
          ))}
        </div>
      </fieldset>

      {firm.analytics && (
        <fieldset>
          <legend>{t("pack.analytics")}</legend>
          <div className="form-grid">
            {(Object.keys(analytics) as (keyof Analytics)[]).map((k) => (
              <Field key={k} label={t(`pack.field.${k}`)}>
                <NumberInput value={analytics[k]} placeholder={String(firm.analytics?.[k] ?? "")}
                  onChange={(x) => set("analytics", { ...analytics, [k]: x })} />
              </Field>
            ))}
          </div>
        </fieldset>
      )}

      <Rows title={t("pack.keywords")} hint={t("clientRules.keywordHint")} rows={rules.suggestionKeywords} onChange={(rows) => set("suggestionKeywords", rows)}
        blank={(): SuggestionKeyword => ({ pattern: "", treatment: "EXEMPT", confidence: 0.5, reason: "" })}
        render={(k, update) => (
          <div className="form-grid">
            <Field label={t("pack.pattern")}><input dir="ltr" required value={k.pattern} onChange={(e) => update({ ...k, pattern: e.target.value })} /></Field>
            <Field label={t("lines.treatment")}>
              <select value={k.treatment} onChange={(e) => update({ ...k, treatment: e.target.value })}>
                {TREATMENTS.map((x) => <option key={x} value={x}>{t(`treatment.${x}`)}</option>)}
              </select>
            </Field>
            <Field label={t("pack.confidence")}><input type="number" min={0.05} max={1} step={0.05} value={k.confidence} onChange={(e) => update({ ...k, confidence: Number(e.target.value) })} /></Field>
            <Field label={t("common.reason")}><input required value={k.reason} onChange={(e) => update({ ...k, reason: e.target.value })} /></Field>
          </div>
        )} />

      <div className="form-grid">
        <Field label={t("entity.effectiveFrom")} hint={t("clientRules.effectiveHint")}>
          <input type="date" required value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} />
        </Field>
        <Field label={t("clientRules.why")}><input required value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      </div>
      <ErrorAlert error={publish.error} />
      <div className="row">
        <button type="submit" className="primary" disabled={publish.isPending || !note.trim()}>{t("clientRules.publish")}</button>
        <button type="button" onClick={onDone}>{t("common.cancel")}</button>
      </div>
    </form>
  );
}

function Rows<T>({ title, hint, rows, onChange, blank, render }: {
  title: string; hint: string; rows: T[]; onChange: (rows: T[]) => void; blank: () => T; render: (row: T, update: (row: T) => void) => ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <fieldset>
      <legend>{title}</legend>
      <p className="muted">{hint}</p>
      {rows.map((row, i) => (
        <div className="editor-row" key={i}>
          {render(row, (next) => onChange(rows.map((x, j) => (j === i ? next : x))))}
          <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))}>{t("clientRules.remove")}</button>
        </div>
      ))}
      <button type="button" onClick={() => onChange([...rows, blank()])}>{t("clientRules.add")}</button>
    </fieldset>
  );
}

function PatternFields({ p, update, categories }: { p: PatternRule; update: (p: PatternRule) => void; categories: string[] }) {
  const { t } = useTranslation();
  return (
    <div className="form-grid">
      <Field label={t("pack.id")}><input dir="ltr" required value={p.id} onChange={(e) => update({ ...p, id: e.target.value })} /></Field>
      <Field label={t("pack.categoryHeader")}>
        <select value={p.category} onChange={(e) => update({ ...p, category: e.target.value })}>
          {categories.map((c) => <option key={c} value={c}>{t(`pack.categories.${c}`)}</option>)}
        </select>
      </Field>
      <Field label={t("pack.patterns")} hint={t("clientRules.onePerLine")}>
        <textarea dir="ltr" rows={2} value={p.patterns.join("\n")}
          onChange={(e) => update({ ...p, patterns: e.target.value.split("\n").map((x) => x.trim()).filter(Boolean) })} />
      </Field>
    </div>
  );
}

/** Blank means "any" for a condition, or "not set". */
function Choice({ value, options, label, onChange }: { value: string | null | undefined; options: string[]; label: (x: string) => string; onChange: (x: string | null) => void }) {
  const { t } = useTranslation();
  return (
    <select value={value ?? ""} onChange={(e) => onChange(e.target.value || null)}>
      <option value="">{t("clientRules.any")}</option>
      {options.map((x) => <option key={x} value={x}>{label(x)}</option>)}
    </select>
  );
}

function NumberInput({ value, placeholder, onChange }: { value: number | null; placeholder: string; onChange: (x: number | null) => void }) {
  return <input type="number" step="any" min={0} dir="ltr" value={value ?? ""} placeholder={placeholder} onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))} />;
}
