import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, ApiError } from "../../api/client";
import type { Evidence, FilingPosition, Pack } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Field, Money, Stat, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

export function FilingTab() {
  const { t } = useTranslation();
  const { periodId, view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const state = view.period.state;
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(state);
  const pack = useQuery({
    queryKey: ["pack", periodId],
    queryFn: async () => {
      try {
        return await api.get<Pack>(`/periods/${periodId}/pack`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: locked,
  });
  const position = useQuery({ queryKey: ["filing", periodId], queryFn: () => api.get<FilingPosition>(`/periods/${periodId}/filing`), enabled: locked });
  const generate = useAction(() => api.post(`/periods/${periodId}/pack`), [["pack", periodId]]);
  const refresh = [["filing", periodId], ["return", periodId], ["portfolio"], ["deadlines"]];

  const upload = async (file: File, description: string) =>
    (await api.upload<Evidence>(`/entities/${entity.id}/evidence`, file, { periodId, description })).id;

  const ackRef = useRef<HTMLInputElement>(null);
  const [filing, setFiling] = useState({ submissionRef: "", submittedOn: "" });
  const file = useAction(async () => {
    const f = ackRef.current?.files?.[0];
    if (!f) throw new Error(t("filing.ackNeeded"));
    const ackEvidenceId = await upload(f, "EmaraTax acknowledgement");
    return api.post(`/periods/${periodId}/filing`, { ...filing, ackEvidenceId });
  }, refresh);

  const bankRef = useRef<HTMLInputElement>(null);
  const [pay, setPay] = useState({ amountPaidAed: "", gibanReference: "", paidOn: "", bankReference: "", bankAmountAed: "" });
  const record = useAction(async () => {
    const f = bankRef.current?.files?.[0];
    const confirmationEvidenceId = f ? await upload(f, "Bank confirmation") : null;
    return api.post(`/periods/${periodId}/payment`, {
      ...pay, bankReference: pay.bankReference || null, bankAmountAed: pay.bankAmountAed || null, confirmationEvidenceId,
    });
  }, refresh);

  if (!locked) return <p className="card muted">{t("filing.notLocked")}</p>;

  return (
    <>
      <ErrorAlert error={generate.error ?? file.error ?? record.error} />
      <section className="card">
        <h2>{t("filing.pack")}</h2>
        <p className="muted">{t("filing.packExplain")}</p>
        <Query query={pack}>
          {(p) => p ? (
            <>
              {p.draftRules && <div className="alert bad" role="status">{t("filing.draftPack")}</div>}
              <p className="muted">{t("filing.packMade", { when: p.createdAt.slice(0, 16).replace("T", " ") })}</p>
              <div className="row">
                <button type="button" onClick={() => void api.download(`/periods/${periodId}/pack/pdf`, "return-pack.pdf")}>PDF</button>
                <button type="button" onClick={() => void api.download(`/periods/${periodId}/pack/xlsx`, "return-pack.xlsx")}>Excel</button>
                <button type="button" onClick={() => void api.download(`/periods/${periodId}/pack/json`, "return-pack.json")}>JSON</button>
              </div>
            </>
          ) : <p className="muted">{t("filing.noPack")}</p>}
        </Query>
        {roles.canFile && <button type="button" className="primary" style={{ marginBlockStart: 12 }} disabled={generate.isPending} onClick={() => generate.mutate(undefined)}>{t("filing.generate")}</button>}
      </section>
      <Query query={position}>
        {(p) => (
          <>
            <div className="stat-grid">
              <Stat label={p.refundable ? t("filing.refundable") : t("filing.payable")} value={<Money value={p.payable} />} />
              <Stat label={t("filing.paid")} value={<Money value={p.paidAndMatched} />} />
              <Stat label={t("filing.outstanding")} value={<Money value={p.outstanding} />} />
            </div>
            <section className="card">
              <h2>{t("filing.submission")}</h2>
              {p.filing ? (
                <p>{t("filing.filed", { ref: p.filing.submissionRef, on: p.filing.submittedOn })}</p>
              ) : state === "APPROVED" && roles.canFile ? (
                <form onSubmit={(e) => { e.preventDefault(); file.mutate(undefined); }}>
                  <p className="muted">{t("filing.submitExplain")}</p>
                  <div className="form-grid">
                    <Field label={t("filing.ref")}><input required dir="ltr" value={filing.submissionRef} onChange={(e) => setFiling({ ...filing, submissionRef: e.target.value })} /></Field>
                    <Field label={t("filing.date")}><input type="date" required value={filing.submittedOn} onChange={(e) => setFiling({ ...filing, submittedOn: e.target.value })} /></Field>
                    <Field label={t("filing.ack")}><input ref={ackRef} type="file" required /></Field>
                  </div>
                  <button type="submit" className="primary" disabled={file.isPending}>{t("filing.record")}</button>
                </form>
              ) : <p className="muted">{t("filing.notFiled")}</p>}
            </section>
            <section className="card">
              <h2>{t("filing.payment")}</h2>
              {p.payments.length > 0 && (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>GIBAN</th><th>{t("filing.date")}</th><th className="num">{t("return.amount")}</th><th>{t("filing.bankMatch")}</th></tr></thead>
                    <tbody>
                      {p.payments.map((x) => (
                        <tr key={x.id}><td className="ltr">{x.gibanReference}</td><td className="ltr">{x.paidOn}</td><td className="num"><Money value={x.amountPaidAed} /></td><td><Badge value={x.matchStatus} group="match" />{x.matchDetail && <><br /><small className="muted">{x.matchDetail}</small></>}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {state === "FILED" && roles.canFile ? (
                <form onSubmit={(e) => { e.preventDefault(); record.mutate(undefined); }} style={{ marginBlockStart: 12 }}>
                  <div className="form-grid">
                    <Field label={t("filing.giban")}><input required dir="ltr" value={pay.gibanReference} onChange={(e) => setPay({ ...pay, gibanReference: e.target.value })} /></Field>
                    <Field label={t("filing.amountPaid")}><input type="number" step="0.01" required value={pay.amountPaidAed} onChange={(e) => setPay({ ...pay, amountPaidAed: e.target.value })} /></Field>
                    <Field label={t("filing.date")}><input type="date" required value={pay.paidOn} onChange={(e) => setPay({ ...pay, paidOn: e.target.value })} /></Field>
                    <Field label={t("filing.bankRef")}><input dir="ltr" value={pay.bankReference} onChange={(e) => setPay({ ...pay, bankReference: e.target.value })} /></Field>
                    <Field label={t("filing.bankAmount")}><input type="number" step="0.01" value={pay.bankAmountAed} onChange={(e) => setPay({ ...pay, bankAmountAed: e.target.value })} /></Field>
                    <Field label={t("filing.bankFile")}><input ref={bankRef} type="file" /></Field>
                  </div>
                  <button type="submit" className="primary" disabled={record.isPending}>{t("filing.recordPayment")}</button>
                </form>
              ) : p.payments.length === 0 && <p className="muted">{state === "PAID" ? t("filing.nothingDue") : t("filing.payAfterFiling")}</p>}
            </section>
          </>
        )}
      </Query>
    </>
  );
}
