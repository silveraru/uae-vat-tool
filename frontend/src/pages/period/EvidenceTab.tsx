import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Evidence } from "../../api/types";
import { Query } from "../../components/Query";
import { ErrorAlert, Field, ReasonAction, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

export function EvidenceTab() {
  const { t } = useTranslation();
  const { periodId, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const key = ["evidence", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Evidence[]>(`/entities/${entity.id}/evidence?periodId=${periodId}`) });
  const fileRef = useRef<HTMLInputElement>(null);
  const [form, setForm] = useState({ description: "", retentionClass: "STANDARD" });
  const upload = useAction(async () => {
    const f = fileRef.current?.files?.[0];
    if (!f) throw new Error(t("evidence.fileNeeded"));
    return api.upload(`/entities/${entity.id}/evidence`, f, { periodId, description: form.description, retentionClass: form.retentionClass });
  }, [key]);
  const hold = useAction(({ id, on, reason }: { id: string; on: boolean; reason: string }) => api.post(`/evidence/${id}/legal-hold`, { on, reason }), [key]);
  return (
    <>
      <ErrorAlert error={upload.error ?? hold.error} />
      <p className="muted">{t("evidence.explain")}</p>
      {roles.canUploadEvidence && (
        <form className="card" onSubmit={(e) => { e.preventDefault(); upload.mutate(undefined, { onSuccess: () => { setForm({ ...form, description: "" }); if (fileRef.current) fileRef.current.value = ""; } }); }}>
          <div className="form-grid">
            <Field label={t("evidence.file")}><input ref={fileRef} type="file" required /></Field>
            <Field label={t("exceptions.description")}><input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
            <Field label={t("evidence.retention")}>
              <select value={form.retentionClass} onChange={(e) => setForm({ ...form, retentionClass: e.target.value })}>
                <option value="STANDARD">{t("evidence.standard")}</option>
                <option value="REAL_ESTATE">{t("evidence.realEstate")}</option>
              </select>
            </Field>
          </div>
          <button type="submit" className="primary" disabled={upload.isPending}>{t("evidence.add")}</button>
        </form>
      )}
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="card muted">{t("common.none")}</p> : (
          <div className="card table-wrap">
            <table>
              <thead><tr><th>{t("evidence.file")}</th><th>{t("exceptions.description")}</th><th>{t("evidence.retainUntil")}</th><th>{t("evidence.fingerprint")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((e) => (
                  <tr key={e.id}>
                    <td><button type="button" className="linklike" onClick={() => void api.download(`/evidence/${e.id}/content`, e.fileName)}>{e.fileName}</button></td>
                    <td>{e.description ?? "–"}</td>
                    <td className="ltr">{e.retainUntil}{e.legalHold && <> <span className="badge warn">{t("evidence.onHold")}</span></>}</td>
                    <td className="ltr" title={e.sha256}>{e.sha256.slice(0, 12)}…</td>
                    <td>{roles.isAdmin && <ReasonAction label={e.legalHold ? t("evidence.releaseHold") : t("evidence.placeHold")} onSubmit={(reason) => hold.mutate({ id: e.id, on: !e.legalHold, reason })} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Query>
    </>
  );
}
