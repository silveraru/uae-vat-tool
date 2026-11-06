import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { BoxDefinition, BoxValues, Comment, DrillPage, Lineage, ReturnVersion, TaxLine } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, Pager, useAction } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

const SECTIONS = ["OUTPUT", "INPUT", "NET"];

export function ReturnTab() {
  const { t, i18n } = useTranslation();
  const { periodId, view } = usePeriod();
  const [selected, setSelected] = useState<string | null>(null);
  const recompute = useAction(() => api.post(`/periods/${periodId}/recompute`), [["return", periodId], ["versions", periodId]]);
  const locale = i18n.language === "ar" ? "ar" : "en";
  const values: BoxValues = view.version ? JSON.parse(view.version.boxValues) : {};
  const selectedBox = view.form.find((b) => b.code === selected);

  return (
    <>
      <ErrorAlert error={recompute.error} />
      <div className="row" style={{ marginBlockEnd: 12 }}>
        {view.version ? (
          <span className="muted">
            {t("return.version", { no: view.version.versionNo })} · <Badge value={view.version.status} /> · {t("return.lines", { count: view.version.lineCount })}
          </span>
        ) : <span className="muted">{t("return.none")}</span>}
        <span className="spacer" />
        {!["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state) && (
          <button type="button" onClick={() => recompute.mutate(undefined)} disabled={recompute.isPending}>{t("return.recompute")}</button>
        )}
      </div>
      {SECTIONS.map((section) => (
        <section className="card table-wrap" key={section}>
          <h2>{t(`return.section.${section}`)}</h2>
          <table>
            <thead>
              <tr>
                <th>{t("return.box")}</th><th>{t("return.description")}</th>
                <th className="num">{t("return.amount")}</th><th className="num">{t("return.vat")}</th><th className="num">{t("return.adjustment")}</th>
              </tr>
            </thead>
            <tbody>
              {view.form.filter((b) => b.section === section).map((b) => (
                <BoxRow key={b.code} box={b} locale={locale} value={values[b.code]} selected={selected === b.code} onSelect={() => setSelected(selected === b.code ? null : b.code)} />
              ))}
            </tbody>
          </table>
        </section>
      ))}
      {selectedBox && <BoxDetail box={selectedBox} locale={locale} />}
      <Versions />
      <MemberView values={view.version?.memberValues} />
    </>
  );
}

function BoxRow({ box, locale, value, selected, onSelect }: { box: BoxDefinition; locale: string; value?: BoxValues[string]; selected: boolean; onSelect: () => void }) {
  const cell = (col: string, v?: string) => <td className="num">{box.columns.includes(col) ? <Money value={v ?? "0"} /> : ""}</td>;
  return (
    <tr className={box.computed ? "total" : ""} aria-selected={selected}>
      <td>
        {/* A real button, so keyboard and screen reader users can open the drill-down too. */}
        <button type="button" className="linklike" onClick={onSelect} aria-expanded={selected}>{box.code}</button>
      </td>
      <td>{box.label[locale] ?? box.label.en}</td>
      {cell("amount", value?.amount)}
      {cell("vat", value?.vat)}
      {cell("adjustment", value?.adjustment)}
    </tr>
  );
}

function BoxDetail({ box, locale }: { box: BoxDefinition; locale: string }) {
  const { t } = useTranslation();
  const { periodId } = usePeriod();
  const [page, setPage] = useState(0);
  const [lineage, setLineage] = useState<string | null>(null);
  const lines = useQuery({
    queryKey: ["box-lines", periodId, box.code, page],
    queryFn: () => api.get<DrillPage>(`/periods/${periodId}/boxes/${box.code}/lines?page=${page}&size=25`),
    enabled: !box.computed,
  });
  return (
    <section className="card" aria-label={t("return.boxDetail", { code: box.code })}>
      <h2>{t("return.box")} {box.code}: {box.label[locale] ?? box.label.en}</h2>
      <p className="muted">{box.entryGuide[locale] ?? box.entryGuide.en}</p>
      {box.computed ? <p className="muted">{t("return.computedBox")}</p> : (
        <Query query={lines}>
          {(d) => d.total === 0 ? <p className="muted">{t("return.noLines")}</p> : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t("return.document")}</th><th>{t("lines.taxPoint")}</th><th>{t("lines.counterparty")}</th>
                      <th className="num">{t("return.amount")}</th><th className="num">{t("return.vat")}</th>
                      <th>{t("return.einvoice")}</th><th>{t("return.source")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.rows.map((r) => (
                      <tr key={r.line.id}>
                        <td className="ltr">{r.docNumber ?? r.line.description ?? r.line.lineKind}</td>
                        <td className="ltr">{r.line.taxPoint}</td>
                        <td>{r.counterpartyName ?? r.line.counterpartyTrn ?? "–"}</td>
                        <td className="num"><Money value={r.line.netAed} /></td>
                        <td className="num"><Money value={r.line.lineKind === "ADJUSTMENT" ? r.line.vatAed : (box.section === "INPUT" ? r.line.recoverableVatAed : r.line.vatAed)} /></td>
                        <td>{r.einvoiceUuid ? <><span className="ltr" title={r.einvoiceUuid}>{r.einvoiceUuid.slice(0, 8)}</span> <Badge value={r.corner5Status} group="corner5" /></> : "–"}</td>
                        <td><button type="button" className="linklike" onClick={() => setLineage(lineage === r.line.id ? null : r.line.id)}>{r.sourceFile ? `${r.sourceFile} #${r.sourceRowNo}` : t("return.showSource")}</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={d.page} size={d.size} total={d.total} onPage={setPage} />
            </>
          )}
        </Query>
      )}
      {lineage && <LineagePanel lineId={lineage} />}
      <Comments objectType="BOX" objectId={box.code} />
    </section>
  );
}

function LineagePanel({ lineId }: { lineId: string }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ["lineage", lineId], queryFn: () => api.get<Lineage>(`/lines/${lineId}/lineage`) });
  return (
    <div className="card" style={{ background: "var(--surface-2)" }}>
      <h3>{t("return.lineage")}</h3>
      <Query query={q}>
        {(l: Lineage) => (
          <>
            <LineFacts line={l.line} />
            {l.batch.file_name ? (
              <p>{t("return.fromFile", { file: String(l.batch.file_name), row: String(l.sourceRow.row_no) })}<br />
                <small className="muted ltr">SHA-256 {String(l.batch.content_hash)}</small></p>
            ) : <p className="muted">{t("return.noFile")}</p>}
            {l.sourceRow.raw ? <pre className="ltr raw">{JSON.stringify(JSON.parse(String(l.sourceRow.raw)), null, 1)}</pre> : null}
          </>
        )}
      </Query>
    </div>
  );
}

function LineFacts({ line }: { line: TaxLine }) {
  const { t } = useTranslation();
  return (
    <p>
      <Badge value={line.treatment} group="treatment" /> · {t("return.classifiedBy", { by: t(`classifiedBy.${line.classifiedBy}`) })}
      {line.blockedCategory && <> · {t("lines.blocked")}: {line.blockedCategory}</>}
      {line.ruleIds.length > 0 && <> · <span className="ltr">{line.ruleIds.join(", ")}</span></>}
      {" · "}{t("return.revision", { no: line.revision })} · <span className="ltr">{line.rulePackVersion}</span>
    </p>
  );
}

export function Comments({ objectType, objectId }: { objectType: string; objectId: string }) {
  const { t } = useTranslation();
  const { periodId } = usePeriod();
  const [body, setBody] = useState("");
  const key = ["comments", periodId, objectType, objectId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Comment[]>(`/periods/${periodId}/comments?objectType=${objectType}&objectId=${encodeURIComponent(objectId)}`) });
  const add = useAction((text: string) => api.post(`/periods/${periodId}/comments`, { objectType, objectId, body: text }), [key]);
  function submit(e: FormEvent) {
    e.preventDefault();
    add.mutate(body, { onSuccess: () => setBody("") });
  }
  return (
    <div style={{ marginBlockStart: 12 }}>
      <h3>{t("comments.title")}</h3>
      <ErrorAlert error={add.error} />
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("comments.none")}</p> : (
          <ul>{rows.map((c) => <li key={c.id}><strong>{c.authorName}</strong> <small className="muted ltr">{c.createdAt.slice(0, 16).replace("T", " ")}</small><br />{c.body}</li>)}</ul>
        )}
      </Query>
      <form className="row" onSubmit={submit}>
        <Field label={t("comments.add")}><input value={body} onChange={(e) => setBody(e.target.value)} required /></Field>
        <button type="submit" disabled={add.isPending}>{t("comments.post")}</button>
      </form>
    </div>
  );
}

function Versions() {
  const { t } = useTranslation();
  const { periodId } = usePeriod();
  const q = useQuery({ queryKey: ["versions", periodId], queryFn: () => api.get<ReturnVersion[]>(`/periods/${periodId}/versions`) });
  return (
    <section className="card">
      <h2>{t("return.versions")}</h2>
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>#</th><th>{t("common.status")}</th><th>{t("return.when")}</th><th>{t("return.changes")}</th></tr></thead>
              <tbody>
                {rows.map((v) => {
                  const diff: { box: string; column: string; from: string; to: string }[] = v.diff ? JSON.parse(v.diff) : [];
                  return (
                    <tr key={v.id}>
                      <td className="num">{v.versionNo}</td>
                      <td><Badge value={v.status} /></td>
                      <td className="ltr">{v.createdAt.slice(0, 16).replace("T", " ")}</td>
                      <td>
                        {diff.length === 0 ? <span className="muted">{t("return.firstVersion")}</span> : (
                          <span className="ltr">{diff.filter((d) => !["8", "11", "12", "13"].includes(d.box)).slice(0, 6).map((d) => `${d.box} ${d.column}: ${d.from} → ${d.to}`).join("; ")}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Query>
    </section>
  );
}

function MemberView({ values }: { values?: string }) {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const members = useQuery({
    queryKey: ["group-members", periodId],
    queryFn: () => api.get<{ entityId: string; legalName: string }[]>(`/periods/${periodId}/group-members`),
    enabled: entity.entityKind === "GROUP_REPRESENTATIVE",
  });
  if (entity.entityKind !== "GROUP_REPRESENTATIVE" || !values) return null;
  const perMember: Record<string, BoxValues> = JSON.parse(values);
  return (
    <section className="card">
      <h2>{t("return.memberTitle")}</h2>
      <Query query={members}>
        {(rows) => (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("return.member")}</th><th className="num">{t("return.outputVat")}</th><th className="num">{t("return.inputVat")}</th><th className="num">{t("return.net")}</th></tr></thead>
              <tbody>
                {rows.map((m) => {
                  const b = perMember[m.entityId] ?? {};
                  return (
                    <tr key={m.entityId}>
                      <td>{m.legalName}</td>
                      <td className="num"><Money value={b["12"]?.vat ?? "0"} /></td>
                      <td className="num"><Money value={b["13"]?.vat ?? "0"} /></td>
                      <td className="num"><Money value={b["14"]?.vat ?? "0"} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Query>
    </section>
  );
}
