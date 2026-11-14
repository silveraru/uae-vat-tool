import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { ClientRules, DecisionRule, PackBox, PatternRule, RulePackDefinition, RuleThen, RuleWhen, SuggestionKeyword } from "../api/types";

/**
 * A rule pack read out section by section, for people rather than parsers. Every value comes from the
 * pack as stored, so what is shown is what the engine runs. Rules a client has switched off are marked.
 */
export function PackSections({ definition: d, switchedOff = [] }: { definition: RulePackDefinition; switchedOff?: string[] }) {
  const { t } = useTranslation();
  const off = new Set(switchedOff);
  return (
    <>
      <Section title={t("pack.filing")}>
        <KeyValues values={{ ...d.filing, standardPct: d.rates.standardPct }} />
      </Section>
      <Section title={t("pack.reconciliation")}>
        <KeyValues values={{
          perInvoiceToleranceAed: d.reconciliation.perInvoiceToleranceAed, glToleranceAed: d.reconciliation.glToleranceAed,
          boxSwingPct: d.reconciliation.boxSwingPct, fuzzyDateWindowDays: d.reconciliation.fuzzyDateWindowDays,
        }} />
        <h4>{t("pack.glAccountBoxes")}</h4>
        <KeyValues values={d.reconciliation.glAccountBoxes} raw />
      </Section>
      <Section title={t("pack.classification")} hint={t("pack.classificationHint")}>
        <DecisionRules rules={d.classification?.rules ?? []} off={off} />
      </Section>
      <Section title={t("pack.patternSection")} hint={t("pack.patternsHint")}>
        <h4>{t("pack.drcPatterns")}</h4>
        <Patterns rows={d.classification?.drcPatterns ?? []} off={off} />
        <h4>{t("pack.blockedPatterns")}</h4>
        <Patterns rows={d.classification?.blockedPatterns ?? []} off={off} />
      </Section>
      {d.penalties && d.penalties.length > 0 && (
        <Section title={t("pack.penalties")}>
          {d.penalties.map((p) => <KeyValues key={String(p.effectiveFrom)} values={p} />)}
        </Section>
      )}
      <Section title={t("pack.forms")}>
        {d.disclosure && <><h4>{t("pack.disclosure")}</h4><KeyValues values={d.disclosure} /></>}
        {d.refund && <><h4>{t("pack.refund")}</h4><KeyValues values={d.refund} /></>}
        {d.zeroRating && <><h4>{t("pack.zeroRating")}</h4><KeyValues values={d.zeroRating} /></>}
      </Section>
      <Section title={t("pack.schemes")}>
        {d.apportionment && (
          <>
            <h4>{t("pack.apportionment")}</h4>
            <KeyValues values={{
              methods: d.apportionment.methods.map((m) => `${t(`pack.method.${m.method}`, { defaultValue: m.method })} (${m.fromTaxYearStarting})`),
              roundToWholePct: d.apportionment.roundToWholePct, furtherAdjustmentThresholdAed: d.apportionment.furtherAdjustmentThresholdAed,
              adjustmentBox: d.apportionment.adjustmentBox,
            }} />
          </>
        )}
        {d.capitalAssets && <><h4>{t("pack.capitalAssets")}</h4><KeyValues values={d.capitalAssets} /></>}
        {d.badDebt && <><h4>{t("pack.badDebt")}</h4><KeyValues values={d.badDebt} /></>}
        {d.marginScheme && <><h4>{t("pack.marginScheme")}</h4><KeyValues values={d.marginScheme} /></>}
      </Section>
      {(d.registration || d.analytics) && (
        <Section title={t("pack.watch")}>
          {d.registration && <><h4>{t("pack.registration")}</h4><KeyValues values={d.registration} /></>}
          {d.analytics && <><h4>{t("pack.analytics")}</h4><KeyValues values={d.analytics} /></>}
        </Section>
      )}
      {d.suggestions && (
        <Section title={t("pack.suggestions")} hint={t("pack.suggestionsHint")}>
          <KeyValues values={{ engineVersion: d.suggestions.engineVersion, historyMinLines: d.suggestions.historyMinLines }} />
          <Keywords rows={d.suggestions.keywords ?? []} />
        </Section>
      )}
      {d.faf && (
        <Section title={t("pack.faf")}>
          <KeyValues values={{ version: d.faf.version, dateFormat: d.faf.dateFormat, outputAmendmentCode: d.faf.outputAmendmentCode, inputAmendmentCode: d.faf.inputAmendmentCode }} />
          <h4>{t("pack.taxCodes")}</h4>
          <KeyValues values={Object.fromEntries(Object.entries(d.faf.taxCodes).map(([k, v]) => [t(`treatment.${k}`, { defaultValue: k }), v]))} raw />
        </Section>
      )}
      <Section title={t("pack.form", { code: d.form.code, version: d.form.version })}>
        <Boxes boxes={d.form.boxes} />
      </Section>
    </>
  );
}

/** What one client has on top of the firm's pack. Firm figures are given beside the ones the client changes. */
export function ClientRulesTables({ rules, firm }: { rules: ClientRules; firm?: RulePackDefinition }) {
  const { t } = useTranslation();
  const none = new Set<string>();
  return (
    <>
      {rules.classificationRules.length > 0 && (<><h4>{t("pack.decisionRules")}</h4><DecisionRules rules={rules.classificationRules} off={none} /></>)}
      {rules.drcPatterns.length > 0 && (<><h4>{t("pack.drcPatterns")}</h4><Patterns rows={rules.drcPatterns} off={none} /></>)}
      {rules.blockedPatterns.length > 0 && (<><h4>{t("pack.blockedPatterns")}</h4><Patterns rows={rules.blockedPatterns} off={none} /></>)}
      {rules.switchedOff.length > 0 && (
        <>
          <h4>{t("clientRules.switchedOff")}</h4>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("pack.id")}</th><th>{t("common.reason")}</th></tr></thead>
              <tbody>{rules.switchedOff.map((s) => <tr key={s.id}><td className="ltr">{s.id}</td><td>{s.reason}</td></tr>)}</tbody>
            </table>
          </div>
        </>
      )}
      {rules.reconciliation && (
        <>
          <h4>{t("pack.reconciliation")}</h4>
          <Overrides values={rules.reconciliation} firm={firm?.reconciliation as unknown as Record<string, unknown> | undefined} />
        </>
      )}
      {rules.analytics && (
        <>
          <h4>{t("pack.analytics")}</h4>
          <Overrides values={rules.analytics} firm={firm?.analytics as unknown as Record<string, unknown> | undefined} />
        </>
      )}
      {rules.suggestionKeywords.length > 0 && (<><h4>{t("pack.keywords")}</h4><Keywords rows={rules.suggestionKeywords} /></>)}
    </>
  );
}

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section className="card">
      <h2>{title}</h2>
      {hint && <p className="muted">{hint}</p>}
      {children}
    </section>
  );
}

/** Field names come from the pack, so each has a label; a field added later still shows, by its name. */
export function KeyValues({ values, raw }: { values: Record<string, unknown>; raw?: boolean }) {
  const { t } = useTranslation();
  const rows = Object.entries(values).filter(([, v]) => v !== null && v !== undefined);
  return (
    <div className="table-wrap">
      <table className="kv">
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <th scope="row">{raw ? <span className="ltr">{k}</span> : t(`pack.field.${k}`, { defaultValue: k })}</th>
              <td><Value value={v} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Value({ value }: { value: unknown }) {
  const { t } = useTranslation();
  if (typeof value === "boolean") return <>{value ? t("common.yes") : t("common.no")}</>;
  if (Array.isArray(value)) return <span className="ltr">{value.map(String).join(", ")}</span>;
  if (value && typeof value === "object") return <KeyValues values={value as Record<string, unknown>} raw />;
  return <span className="ltr">{String(value)}</span>;
}

function Overrides({ values, firm }: { values: Record<string, unknown>; firm?: Record<string, unknown> }) {
  const { t } = useTranslation();
  const rows = Object.entries(values).filter(([, v]) => v !== null && v !== undefined);
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("pack.setting")}</th><th>{t("clientRules.forClient")}</th><th>{t("clientRules.firmFigure")}</th></tr></thead>
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <td>{t(`pack.field.${k}`, { defaultValue: k })}</td>
              <td className="num">{String(v)}</td>
              <td className="num muted">{firm && firm[k] !== undefined ? String(firm[k]) : "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function WhenText({ when }: { when: RuleWhen }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (when.direction) parts.push(t(`direction.${when.direction}`, { defaultValue: when.direction }));
  if (when.goodsOrServices) parts.push(t(`pack.gos.${when.goodsOrServices}`, { defaultValue: when.goodsOrServices }));
  const yesNo = (key: string, v: boolean | null | undefined) => {
    if (v !== null && v !== undefined) parts.push(`${t(`pack.field.${key}`)}: ${v ? t("common.yes") : t("common.no")}`);
  };
  yesNo("counterpartyInUae", when.counterpartyInUae);
  yesNo("counterpartyHasTrn", when.counterpartyHasTrn);
  yesNo("counterpartyDesignatedZone", when.counterpartyDesignatedZone);
  yesNo("establishmentDesignatedZone", when.establishmentDesignatedZone);
  if (when.mappedTreatmentIn?.length) parts.push(`${t("pack.field.mappedTreatmentIn")}: ${when.mappedTreatmentIn.map((x) => t(`treatment.${x}`, { defaultValue: x })).join(", ")}`);
  if (when.source) parts.push(`${t("pack.field.source")}: ${when.source}`);
  return (
    <>
      {parts.join(" · ")}
      {when.descriptionMatches && <>{parts.length > 0 && " · "}{t("pack.field.descriptionMatches")}: <code className="ltr">{when.descriptionMatches}</code></>}
    </>
  );
}

export function ThenText({ then }: { then: RuleThen }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (then.treatment) parts.push(t(`treatment.${then.treatment}`, { defaultValue: then.treatment }));
  if (then.placeOfSupply) parts.push(`${t("pack.field.placeOfSupply")}: ${then.placeOfSupply}`);
  if (then.flag) parts.push(`${t("pack.field.flag")}: ${then.flag}`);
  return <>{parts.join(" · ")}</>;
}

function DecisionRules({ rules, off }: { rules: DecisionRule[]; off: Set<string> }) {
  const { t } = useTranslation();
  if (rules.length === 0) return <p className="muted">{t("common.none")}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("pack.id")}</th><th>{t("pack.description")}</th><th>{t("pack.when")}</th><th>{t("pack.then")}</th></tr></thead>
        <tbody>
          {rules.map((r) => (
            <tr key={r.id} className={off.has(r.id) ? "struck" : undefined}>
              <td className="ltr">{r.id}{off.has(r.id) && <> <span className="badge warn">{t("clientRules.offForClient")}</span></>}</td>
              <td>{r.description ?? "–"}</td>
              <td><WhenText when={r.when} /></td>
              <td><ThenText then={r.then} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Patterns({ rows, off }: { rows: PatternRule[]; off: Set<string> }) {
  const { t } = useTranslation();
  if (rows.length === 0) return <p className="muted">{t("common.none")}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("pack.id")}</th><th>{t("pack.categoryHeader")}</th><th>{t("pack.patterns")}</th></tr></thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.id} className={off.has(p.id) ? "struck" : undefined}>
              <td className="ltr">{p.id}{off.has(p.id) && <> <span className="badge warn">{t("clientRules.offForClient")}</span></>}</td>
              <td>{t(`pack.categories.${p.category}`, { defaultValue: p.category })}</td>
              <td><code className="ltr">{p.patterns.join("  ")}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Keywords({ rows }: { rows: SuggestionKeyword[] }) {
  const { t } = useTranslation();
  if (rows.length === 0) return <p className="muted">{t("common.none")}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("pack.pattern")}</th><th>{t("lines.treatment")}</th><th>{t("pack.confidence")}</th><th>{t("common.reason")}</th></tr></thead>
        <tbody>
          {rows.map((k) => (
            <tr key={k.pattern}>
              <td><code className="ltr">{k.pattern}</code></td>
              <td>{t(`treatment.${k.treatment}`, { defaultValue: k.treatment })}</td>
              <td className="num">{k.confidence}</td>
              <td>{k.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Boxes({ boxes }: { boxes: PackBox[] }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.language === "ar" ? "ar" : "en";
  const rule = (b: PackBox) => {
    if (b.filter) {
      const parts = [
        b.filter.direction ? t(`direction.${b.filter.direction}`, { defaultValue: b.filter.direction }) : null,
        b.filter.treatments?.map((x) => t(`treatment.${x}`, { defaultValue: x })).join(", "),
        b.filter.emirate ? t(`emirate.${b.filter.emirate}`, { defaultValue: b.filter.emirate }) : null,
        b.filter.blocked === false ? t("pack.notBlocked") : null,
      ];
      return parts.filter(Boolean).join(" · ");
    }
    const parts = [];
    if (b.sumOf) parts.push(t("pack.sumOf", { boxes: b.sumOf.join(", ") }));
    if (b.addOf) parts.push(t("pack.addOf", { boxes: b.addOf.join(", ") }));
    if (b.subtractOf) parts.push(t("pack.subtractOf", { boxes: b.subtractOf.join(", ") }));
    return parts.join(" ");
  };
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("pack.box")}</th><th>{t("pack.label")}</th><th>{t("pack.columns")}</th><th>{t("pack.boxRule")}</th></tr></thead>
        <tbody>
          {boxes.map((b) => (
            <tr key={b.code}>
              <td className="ltr">{b.code}</td>
              <td>{b.label[lang] ?? b.label.en}</td>
              <td>{b.columns.map((c) => t(`pack.column.${c}`, { defaultValue: c })).join(", ")}</td>
              <td>{rule(b)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
