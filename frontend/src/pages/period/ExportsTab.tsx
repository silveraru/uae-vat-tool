import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { ExportRow, MarginRow } from "../../api/types";
import { uploadEvidence } from "../../components/DocumentList";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

/** What a zero rate and the margin scheme rest on: export evidence and the margin schedule. */
export function ExportsTab() {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const exports = useQuery({ queryKey: ["exports", periodId], queryFn: () => api.get<ExportRow[]>(`/periods/${periodId}/exports`) });
  const margin = useQuery({ queryKey: ["margin", periodId], queryFn: () => api.get<MarginRow[]>(`/periods/${periodId}/margin-schedule`) });
  const [open, setOpen] = useState<string | null>(null);
  return (
    <>
      <section className="card">
        <h2>{t("exports.title")}</h2>
        <p className="muted">{t("exports.explain")}</p>
        <Query query={exports}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("exports.none")}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t("suggestions.document")}</th><th>{t("exports.customer")}</th><th className="num">{t("return.amount")}</th><th>{t("exports.exportedOn")}</th><th>{t("exports.limit")}</th><th>{t("common.status")}</th><th>{t("common.actions")}</th></tr></thead>
                <tbody>
                  {rows.map((x) => (
                    <tr key={x.documentId}>
                      <td className="ltr">{x.docNumber}</td>
                      <td>{x.counterpartyName ?? "–"} <span className="muted ltr">{x.counterpartyCountry}</span></td>
                      <td className="num"><Money value={x.netAed} /></td>
                      <td className="ltr">{x.exportedOn ?? "–"}</td>
                      <td className="ltr">{x.limitOn}</td>
                      <td><Badge value={x.status} /></td>
                      <td>{roles.isPreparer && <button type="button" aria-expanded={open === x.documentId} onClick={() => setOpen(open === x.documentId ? null : x.documentId)}>{t("exports.record")}</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Query>
        {open && <EvidenceForm documentId={open} onDone={() => setOpen(null)} />}
      </section>
      <section className="card">
        <h2>{t("exports.marginTitle")}</h2>
        <p className="muted">{t("exports.marginExplain")}</p>
        <Query query={margin}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("exports.noMargin")}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t("suggestions.document")}</th><th>{t("exports.category")}</th><th className="num">{t("exports.purchase")}</th><th className="num">{t("exports.selling")}</th><th className="num">{t("exports.margin")}</th><th className="num">{t("return.vat")}</th></tr></thead>
                <tbody>
                  {rows.map((m) => (
                    <tr key={m.docNumber}>
                      <td className="ltr">{m.docNumber}</td><td>{t(`exports.categoryValue.${m.category}`, { defaultValue: m.category })}</td>
                      <td className="num"><Money value={Number(m.purchasePriceAed) + Number(m.acquisitionCostsAed)} /></td><td className="num"><Money value={m.sellingPriceAed} /></td>
                      <td className="num"><Money value={m.marginAed} /></td><td className="num"><Money value={m.vatAed} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Query>
      </section>
    </>
  );
}

function EvidenceForm({ documentId, onDone }: { documentId: string; onDone: () => void }) {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const [form, setForm] = useState({ exportedOn: "", exitReference: "", extendedUntil: "" });
  const official = useRef<HTMLInputElement>(null);
  const commercial = useRef<HTMLInputElement>(null);
  const extension = useRef<HTMLInputElement>(null);
  const save = useAction(async () => {
    const put = async (ref: React.RefObject<HTMLInputElement | null>, description: string) => {
      const f = ref.current?.files?.[0];
      return f ? uploadEvidence(entity.id, f, periodId, description) : null;
    };
    return api.post(`/documents/${documentId}/export-evidence`, {
      exportedOn: form.exportedOn || null, exitReference: form.exitReference || null, extendedUntil: form.extendedUntil || null,
      officialEvidenceId: await put(official, "Official export evidence"), commercialEvidenceId: await put(commercial, "Commercial export evidence"),
      extensionEvidenceId: await put(extension, "FTA extension of the export period"),
    });
  }, [["exports", periodId], ["exceptions", periodId]]);
  return (
    <form style={{ marginBlockStart: 12 }} onSubmit={(e) => { e.preventDefault(); save.mutate(undefined, { onSuccess: onDone }); }}>
      <ErrorAlert error={save.error} />
      <div className="form-grid">
        <Field label={t("exports.exportedOn")}><input type="date" value={form.exportedOn} onChange={(e) => setForm({ ...form, exportedOn: e.target.value })} /></Field>
        <Field label={t("exports.exitReference")}><input dir="ltr" value={form.exitReference} onChange={(e) => setForm({ ...form, exitReference: e.target.value })} /></Field>
        <Field label={t("exports.official")}><input ref={official} type="file" /></Field>
        <Field label={t("exports.commercial")}><input ref={commercial} type="file" /></Field>
        <Field label={t("exports.extendedUntil")} hint={t("exports.extensionHint")}><input type="date" value={form.extendedUntil} onChange={(e) => setForm({ ...form, extendedUntil: e.target.value })} /></Field>
        <Field label={t("exports.extensionLetter")}><input ref={extension} type="file" /></Field>
      </div>
      <button type="submit" className="primary" disabled={save.isPending}>{t("common.save")}</button>
    </form>
  );
}
