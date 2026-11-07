import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { BankImport, BankStatement, BankTxn, Batch, ConnectorProfileView, Einvoice, LoadResult, RowView } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

const KINDS = ["AR", "AP", "VAT_SUBLEDGER", "GL", "GL_JOURNAL", "CUSTOMS", "FX", "MARGIN", "OPENING_AP", "OPENING_ASSETS", "OPENING_APPORTIONMENT"];

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
      <OtherSources onLoaded={setResult} />
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

/** Data that does not come as a template file: e-invoices from another ASP, bank statements and ERP exports. */
function OtherSources({ onLoaded }: { onLoaded: (r: LoadResult) => void }) {
  const { t } = useTranslation();
  const { entity, periodId } = usePeriod();
  const roles = useRoles(entity.id);
  const refresh = [["batches", entity.id], ["return", periodId], ["exceptions", periodId]];
  const ublFile = useRef<HTMLInputElement>(null);
  const [linesFor, setLinesFor] = useState<string | null>(null);
  const ubl = useAction((file: File) => api.upload<LoadResult>(`/entities/${entity.id}/einvoices/ubl`, file), [...refresh, ["einvoices", entity.id]]);
  const bankFile = useRef<HTMLInputElement>(null);
  const [bankResult, setBankResult] = useState<BankImport | null>(null);
  const statements = useQuery({ queryKey: ["bank-statements", entity.id], queryFn: () => api.get<BankStatement[]>(`/entities/${entity.id}/bank-statements`) });
  const bank = useAction((file: File) => api.upload<BankImport>(`/entities/${entity.id}/bank-statements`, file), [["bank-statements", entity.id], ["return", periodId], ["filing", periodId]]);
  const profiles = useQuery({ queryKey: ["connector-profiles"], queryFn: () => api.get<ConnectorProfileView[]>("/connector-profiles") });
  const [system, setSystem] = useState("");
  const [exportKind, setExportKind] = useState("AR");
  const exportFile = useRef<HTMLInputElement>(null);
  const exported = useAction((file: File) => api.upload<LoadResult>(`/entities/${entity.id}/ingest-export/${system}/${exportKind}`, file), refresh);
  const profile = profiles.data?.find((p) => p.system === system);
  if (!roles.isPreparer) return null;
  return (
    <section className="card">
      <h2>{t("data.otherSources")}</h2>
      <ErrorAlert error={ubl.error ?? bank.error ?? exported.error} />
      <form onSubmit={(e) => { e.preventDefault(); const f = ublFile.current?.files?.[0]; if (f) ubl.mutate(f, { onSuccess: (r) => { onLoaded(r as LoadResult); if (ublFile.current) ublFile.current.value = ""; } }); }}>
        <h3>{t("data.ubl")}</h3>
        <p className="muted">{t("data.ublExplain")}</p>
        <div className="row">
          <Field label={t("data.ublFile")}><input ref={ublFile} type="file" accept=".xml,.zip" required /></Field>
          <button type="submit" disabled={ubl.isPending}>{t("data.loadUbl")}</button>
        </div>
      </form>
      <OtherProviderStatuses />
      <form onSubmit={(e) => { e.preventDefault(); const f = bankFile.current?.files?.[0]; if (f) bank.mutate(f, { onSuccess: (r) => { setBankResult(r as BankImport); if (bankFile.current) bankFile.current.value = ""; } }); }}>
        <h3>{t("data.bank")}</h3>
        <p className="muted">{t("data.bankExplain")}</p>
        {bankResult && (
          <div className="alert ok" role="status">
            {bankResult.result.alreadyProcessed ? t("data.alreadyLoaded") : t("data.bankLoaded", { invoices: bankResult.result.supplierInvoicesMatched, returns: bankResult.returnsSettled })}
          </div>
        )}
        <div className="row">
          <Field label={t("data.bankFile")}><input ref={bankFile} type="file" required /></Field>
          <button type="submit" disabled={bank.isPending}>{t("data.loadBank")}</button>
        </div>
        {statements.data && statements.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t("data.fileName")}</th><th>{t("data.bankAccount")}</th><th className="num">{t("data.bankLines")}</th><th className="num">{t("data.bankNew")}</th><th>{t("data.bankCheck")}</th></tr></thead>
              <tbody>
                {statements.data.map((b) => (
                  <tr key={b.id}>
                    <td className="ltr">{b.fileName}</td><td className="ltr">{b.account}</td>
                    <td className="num"><button type="button" className="linklike" aria-expanded={linesFor === b.id} aria-label={t("data.showBankLines", { file: b.fileName })} onClick={() => setLinesFor(linesFor === b.id ? null : b.id)}>{b.txnCount}</button></td>
                    <td className="num">{b.txnNew}</td>
                    <td>{b.balanceProblem ? <span className="badge bad" title={b.balanceProblem}>{t("data.bankDoesNotAddUp")}</span> : b.balanceChecked ? <span className="badge ok">{t("data.bankAddsUp")}</span> : "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {linesFor && <BankLines statementId={linesFor} />}
      </form>
      <form onSubmit={(e) => { e.preventDefault(); const f = exportFile.current?.files?.[0]; if (f) exported.mutate(f, { onSuccess: (r) => { onLoaded(r as LoadResult); if (exportFile.current) exportFile.current.value = ""; } }); }}>
        <h3>{t("data.erpExport")}</h3>
        <p className="muted">{t("data.erpExportExplain")}</p>
        {profile && <div className="alert warn" role="status"><strong>{t("connectors.unverified")}</strong> <span className="ltr">{profile.note}</span></div>}
        <div className="form-grid">
          <Field label={t("connectors.system")}>
            <select required value={system} onChange={(e) => setSystem(e.target.value)}>
              <option value="">{t("common.choose")}</option>
              {profiles.data?.map((p) => <option key={p.system} value={p.system}>{p.label}</option>)}
            </select>
          </Field>
          <Field label={t("data.exportKind")}>
            <select value={exportKind} onChange={(e) => setExportKind(e.target.value)}>{(profile?.kinds ?? ["AR", "AP"]).map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select>
          </Field>
          <Field label={t("data.exportFile")}><input ref={exportFile} type="file" accept=".csv,.xlsx" required /></Field>
        </div>
        <button type="submit" disabled={exported.isPending || !system}>{t("data.loadExport")}</button>
      </form>
    </section>
  );
}

/**
 * A UBL file does not carry its Corner 5 status, so it is entered here from what the other provider's
 * portal shows. Until then check R01 counts these invoices as not reported to the FTA.
 */
function OtherProviderStatuses() {
  const { t } = useTranslation();
  const { entity, periodId, view } = usePeriod();
  const { periodStart, periodEnd } = view.period;
  const q = useQuery({
    queryKey: ["einvoices", entity.id, periodStart],
    queryFn: () => api.get<Einvoice[]>(`/entities/${entity.id}/einvoices?from=${periodStart}&to=${periodEnd}`),
  });
  const set = useAction(
    (items: { invoiceUuid: string; status: string }[]) => api.post(`/entities/${entity.id}/einvoices/corner5-status`, items),
    [["einvoices", entity.id], ["return", periodId], ["exceptions", periodId]],
  );
  const rows = (q.data ?? []).filter((e) => e.source === "UBL_IMPORT");
  if (rows.length === 0) return null;
  const pending = rows.filter((e) => e.corner5Status !== "REPORTED");
  return (
    <div style={{ marginBlockEnd: 16 }}>
      <h4>{t("data.corner5")}</h4>
      <p className="muted">{t("data.corner5Explain")}</p>
      <ErrorAlert error={set.error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t("schemes.invoice")}</th><th>{t("data.invoiceDate")}</th><th className="num">{t("return.vat")}</th><th>{t("common.status")}</th><th>{t("common.actions")}</th></tr></thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.invoiceUuid}>
                <td className="ltr">{e.number}</td><td className="ltr">{e.issueDate}</td><td className="num"><Money value={e.vatAed} /></td>
                <td><Badge value={e.corner5Status} group="corner5" /></td>
                <td className="row">
                  {e.corner5Status !== "REPORTED" && <button type="button" disabled={set.isPending} aria-label={t("data.markReportedOne", { number: e.number })} onClick={() => set.mutate([{ invoiceUuid: e.invoiceUuid, status: "REPORTED" }])}>{t("data.markReported")}</button>}
                  {e.corner5Status !== "FAILED" && <button type="button" disabled={set.isPending} aria-label={t("data.markFailedOne", { number: e.number })} onClick={() => set.mutate([{ invoiceUuid: e.invoiceUuid, status: "FAILED" }])}>{t("data.markFailed")}</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pending.length > 1 && (
        <button type="button" disabled={set.isPending} onClick={() => set.mutate(pending.map((e) => ({ invoiceUuid: e.invoiceUuid, status: "REPORTED" })))}>{t("data.markAllReported", { count: pending.length })}</button>
      )}
    </div>
  );
}

function BankLines({ statementId }: { statementId: string }) {
  const { t } = useTranslation();
  const { entity } = usePeriod();
  const q = useQuery({ queryKey: ["bank-lines", statementId], queryFn: () => api.get<BankTxn[]>(`/entities/${entity.id}/bank-statements/${statementId}/transactions`) });
  return (
    <Query query={q}>
      {(rows) => rows.length === 0 ? <p className="muted">{t("common.none")}</p> : (
        <div className="table-wrap" style={{ marginBlockStart: 12 }}>
          <table>
            <thead><tr><th>{t("data.bankDate")}</th><th>{t("data.bankDirection")}</th><th className="num">{t("return.amount")}</th><th>{t("data.bankReference")}</th><th>{t("data.bankNarrative")}</th></tr></thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.id}>
                  <td className="ltr">{x.bookedOn}</td><td>{t(`data.bankDirectionValue.${x.direction}`)}</td><td className="num"><Money value={x.amount} /> <small className="muted ltr">{x.currency}</small></td>
                  <td className="ltr">{x.reference ?? "–"}</td><td className="ltr"><small>{[x.counterparty, x.narrative].filter(Boolean).join(" · ") || "–"}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Query>
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
