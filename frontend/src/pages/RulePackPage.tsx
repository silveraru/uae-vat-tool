import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { BundledPack, RulePack, RulePackChange, RulePackCheck, RulePackCopy, RulePackView } from "../api/types";
import { Query } from "../components/Query";
import { PackSections } from "../components/RuleBook";
import { Badge, ErrorAlert, Field, ReasonAction, useAction } from "../components/ui";
import { formatDate } from "../i18n";

/** One version of the firm's pack: what it says, where it came from, and who signed it off. */
export function RulePackPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["rule-pack", id], queryFn: () => api.get<RulePackView>(`/rule-packs/${id}`) });
  const signOff = useAction((note: string) => api.post(`/rule-packs/${id}/sign-off`, { note }), [["rule-pack", id], ["rule-packs"]]);
  const retire = useAction((note: string) => api.post(`/rule-packs/${id}/retire`, { note }), [["rule-pack", id], ["rule-packs"]]);
  return (
    <Query query={query}>
      {({ pack, definition, yaml }) => (
        <>
          <p><Link to="/rule-packs">{t("rulePacks.back")}</Link></p>
          <div className="card">
            <h1 className="ltr">{pack.version}</h1>
            <p className="row">
              <Badge value={pack.status} />
              {pack.inForceToday && <span className="badge ok">{t("rulePacks.inForce")}</span>}
              <span className="muted">{t("rulePacks.effective", { from: pack.effectiveFrom, to: pack.effectiveTo ?? t("rulePacks.noEnd") })}</span>
            </p>
            <PackFacts pack={pack} />
            <ErrorAlert error={signOff.error ?? retire.error} />
            <div className="row">
              {pack.status !== "RETIRED" && <Link className="button primary" to={`/rule-packs/${id}/edit`}>{t("rulePacks.editCopy")}</Link>}
              {pack.status === "DRAFT" && <ReasonAction label={t("rulePacks.signOff")} placeholder={t("rulePacks.signOffHint")} onSubmit={(note) => signOff.mutate(note)} pending={signOff.isPending} />}
              {pack.status !== "RETIRED" && <ReasonAction danger label={t("rulePacks.retire")} placeholder={t("rulePacks.retireHint")} onSubmit={(note) => retire.mutate(note)} pending={retire.isPending} />}
            </div>
          </div>
          <PackSections definition={definition} />
          <section className="card">
            <details>
              <summary>{t("rulePacks.source")}</summary>
              <p className="muted">{t("rulePacks.sourceExplain")}</p>
              <pre className="source ltr" dir="ltr">{yaml}</pre>
            </details>
          </section>
        </>
      )}
    </Query>
  );
}

function PackFacts({ pack }: { pack: RulePack }) {
  const { t } = useTranslation();
  const bundled = useQuery({ queryKey: ["rule-pack-bundled"], queryFn: () => api.get<BundledPack>("/rule-packs/bundled") });
  return (
    <dl className="facts">
      <dt>{t("rulePacks.loaded")}</dt>
      <dd>{pack.loadedByName ?? pack.loadedBy} · {formatDate(pack.loadedAt)}</dd>
      <dt>{t("rulePacks.basedOn")}</dt>
      <dd>{pack.basedOn ? <><Link className="ltr" to={`/rule-packs/${pack.basedOn}`}>{pack.basedOnVersion}</Link>{pack.editNote && <> · {pack.editNote}</>}</> : pack.version === bundled.data?.version ? t("rulePacks.shipped") : t("rulePacks.loadedWhole")}</dd>
      <dt>{t("rulePacks.signedOff")}</dt>
      <dd>{pack.signedOffAt ? <>{pack.signedOffByName ?? pack.signedOffBy} · {formatDate(pack.signedOffAt)}{pack.signOffNote && <> · {pack.signOffNote}</>}</> : t("rulePacks.notSignedOff")}</dd>
      {pack.retiredAt && (<><dt>{t("rulePacks.retired")}</dt><dd>{formatDate(pack.retiredAt)} · {pack.retireNote}</dd></>)}
    </dl>
  );
}

/**
 * Editing is done on a copy, in the pack's own source, so the notes and open questions in it survive.
 * The check shows what the copy changes before anything is saved. Saving makes a new draft version.
 */
export function RulePackEditPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const base = useQuery({ queryKey: ["rule-pack", id], queryFn: () => api.get<RulePackView>(`/rule-packs/${id}`) });
  const copy = useQuery({ queryKey: ["rule-pack-copy", id], queryFn: () => api.get<RulePackCopy>(`/rule-packs/${id}/copy`), staleTime: Infinity });
  const [yaml, setYaml] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [result, setResult] = useState<{ text: string; check: RulePackCheck } | null>(null);
  const text = yaml ?? copy.data?.yaml ?? "";
  const check = useAction(async (body: string) => {
    const r = await api.post<RulePackCheck>("/rule-packs/check", { yaml: body, basedOn: id });
    setResult({ text: body, check: r });
    return r;
  });
  const save = useAction(async () => {
    const saved = await api.post<RulePack>("/rule-packs/edits", { yaml: text, basedOn: id, note });
    navigate(`/rule-packs/${saved.id}`);
  }, [["rule-packs"]]);
  const checked = result && result.text === text ? result.check : null;
  return (
    <Query query={base}>
      {({ pack }) => (
        <>
          <p><Link to={`/rule-packs/${id}`}>{t("rulePacks.backTo", { version: pack.version })}</Link></p>
          <h1>{t("rulePacks.editTitle", { version: pack.version })}</h1>
          <div className="alert warn" role="note">
            <p>{t("rulePacks.editExplain")}</p>
            <ul>
              <li>{t("rulePacks.editRule1")}</li>
              <li>{t("rulePacks.editRule2")}</li>
              <li>{t("rulePacks.editRule3")}</li>
            </ul>
          </div>
          <section className="card">
            <Field label={t("rulePacks.sourceLabel", { version: copy.data?.suggestedVersion ?? "" })} hint={t("rulePacks.sourceHint")}>
              <textarea className="code ltr" dir="ltr" spellCheck={false} rows={28} value={text} onChange={(e) => setYaml(e.target.value)} />
            </Field>
            <div className="row">
              <button type="button" disabled={check.isPending || !text} onClick={() => check.mutate(text)}>{t("rulePacks.check")}</button>
              {result && result.text !== text && <span className="muted">{t("rulePacks.changedSinceCheck")}</span>}
            </div>
            <ErrorAlert error={check.error} />
          </section>
          {checked && (
            <section className="card">
              <h2>{t("rulePacks.checkResult")}</h2>
              {checked.problems.length > 0 ? (
                <div className="alert bad" role="alert"><ul>{checked.problems.map((p) => <li key={p}>{p}</li>)}</ul></div>
              ) : (
                <div className="alert ok" role="status">{t("rulePacks.checkOk", { version: checked.version, from: checked.effectiveFrom })}</div>
              )}
              <ChangeList changes={checked.changes} />
              {checked.valid && (
                <form className="row" onSubmit={(e) => { e.preventDefault(); save.mutate(undefined); }}>
                  <Field label={t("rulePacks.whyEdit")} hint={t("rulePacks.whyEditHint")}>
                    <input value={note} onChange={(e) => setNote(e.target.value)} required />
                  </Field>
                  <button type="submit" className="primary" disabled={save.isPending || !note.trim()}>{t("rulePacks.saveDraft")}</button>
                </form>
              )}
              <ErrorAlert error={save.error} />
            </section>
          )}
        </>
      )}
    </Query>
  );
}

export function ChangeList({ changes }: { changes: RulePackChange[] }) {
  const { t } = useTranslation();
  if (changes.length === 0) return null;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t("rulePacks.what")}</th><th>{t("rulePacks.before")}</th><th>{t("rulePacks.after")}</th></tr></thead>
        <tbody>
          {changes.map((c) => (
            <tr key={c.path}>
              <td><code className="ltr">{c.path}</code></td>
              <td className="ltr">{c.before ?? <span className="muted">{t("rulePacks.added")}</span>}</td>
              <td className="ltr">{c.after ?? <span className="muted">{t("rulePacks.removed")}</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
