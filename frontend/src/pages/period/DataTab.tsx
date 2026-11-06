import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Batch, LoadResult, RowView } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, useAction } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

const KINDS = ["AR", "AP", "VAT_SUBLEDGER", "GL", "CUSTOMS", "FX", "OPENING_AP"];

export function DataTab() {
  const { t } = useTranslation();
  const { entity, periodId } = usePeriod();
  const [kind, setKind] = useState("AR");
  const [result, setResult] = useState<LoadResult | null>(null);
  const [rowsFor, setRowsFor] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const batches = useQuery({ queryKey: ["batches", entity.id], queryFn: () => api.get<Batch[]>(`/entities/${entity.id}/ingest/batches`), refetchInterval: 5000 });
  const upload = useAction(
    (file: File) => api.upload<LoadResult>(`/entities/${entity.id}/ingest/${kind}`, file),
    [["batches", entity.id], ["return", periodId], ["exceptions", periodId]],
  );
  return (
    <>
      <form
        className="card"
        onSubmit={(e) => {
          e.preventDefault();
          const file = fileRef.current?.files?.[0];
          if (file) upload.mutate(file, { onSuccess: (r) => { setResult(r as LoadResult); if (fileRef.current) fileRef.current.value = ""; } });
        }}
      >
        <h2>{t("data.upload")}</h2>
        <ErrorAlert error={upload.error} />
        {result && (
          <div className={`alert ${result.batch.rowsRejected + result.batch.rowsHeld > 0 ? "warn" : "ok"}`} role="status">
            {result.alreadyProcessed ? t("data.alreadyLoaded") : t("data.loaded", {
              file: result.batch.fileName, loaded: result.batch.rowsLoaded, unchanged: result.batch.rowsUnchanged,
              rejected: result.batch.rowsRejected, held: result.batch.rowsHeld,
            })}
          </div>
        )}
        <div className="form-grid">
          <Field label={t("data.kind")}>
            <select value={kind} onChange={(e) => setKind(e.target.value)}>{KINDS.map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select>
          </Field>
          <Field label={t("data.file")} hint={t("data.fileHint")}><input ref={fileRef} type="file" accept=".csv,.xlsx" required /></Field>
        </div>
        <div className="row">
          <button type="submit" className="primary" disabled={upload.isPending}>{upload.isPending ? t("data.loading") : t("data.load")}</button>
          <button type="button" onClick={() => void api.download(`/ingest/templates/${kind}`, `${kind.toLowerCase()}-template.csv`)}>{t("data.template")}</button>
        </div>
      </form>
      <section className="card">
        <h2>{t("data.loads")}</h2>
        <Query query={batches}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("data.noLoads")}</p> : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t("data.fileName")}</th><th>{t("data.kind")}</th><th>{t("data.channel")}</th><th className="num">{t("data.loadedCol")}</th>
                    <th className="num">{t("data.unchanged")}</th><th className="num">{t("data.rejected")}</th><th className="num">{t("data.held")}</th><th>{t("common.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((b) => (
                    <tr key={b.id}>
                      <td className="ltr">{b.fileName}</td>
                      <td>{t(`kind.${b.kind}`)}</td>
                      <td>{b.channel}</td>
                      <td className="num">{b.rowsLoaded}</td>
                      <td className="num">{b.rowsUnchanged}</td>
                      <td className="num">
                        {b.rowsRejected + b.rowsHeld > 0
                          ? <button type="button" className="linklike" onClick={() => setRowsFor(rowsFor === b.id ? null : b.id)}>{b.rowsRejected}</button>
                          : b.rowsRejected}
                      </td>
                      <td className="num">{b.rowsHeld}</td>
                      <td><Badge value={b.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Query>
        {rowsFor && <ProblemRows batchId={rowsFor} />}
      </section>
    </>
  );
}

function ProblemRows({ batchId }: { batchId: string }) {
  const { t } = useTranslation();
  const { entity } = usePeriod();
  const q = useQuery({
    queryKey: ["batch-rows", batchId],
    queryFn: async () => {
      const [rej, held] = await Promise.all([
        api.get<RowView[]>(`/entities/${entity.id}/ingest/batches/${batchId}/rows?status=REJECTED`),
        api.get<RowView[]>(`/entities/${entity.id}/ingest/batches/${batchId}/rows?status=HELD`),
      ]);
      return [...rej, ...held];
    },
  });
  return (
    <Query query={q}>
      {(rows) => (
        <div className="table-wrap" style={{ marginBlockStart: 12 }}>
          <table>
            <thead><tr><th>{t("data.row")}</th><th>{t("common.status")}</th><th>{t("data.problem")}</th><th>{t("data.content")}</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}><td className="num">{r.rowNo}</td><td>{r.status}</td><td className="ltr">{r.errors.join(", ")}</td><td className="ltr"><code>{r.raw.slice(0, 140)}</code></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Query>
  );
}
