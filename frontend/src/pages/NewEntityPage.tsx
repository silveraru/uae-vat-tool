import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { Entity } from "../api/types";
import { ErrorAlert, Field, useAction } from "../components/ui";

export function NewEntityPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [kind, setKind] = useState("STANDALONE");
  const [form, setForm] = useState({
    legalName: "", legalNameAr: "", trn: "", filingFrequency: "MONTHLY", firstPeriodStart: "2027-01-01",
    einvoiceGoLive: "2027-01-01", partnerThresholdAed: "", groupRepId: "", requireReviewer: true,
  });
  const entities = useQuery({ queryKey: ["entities"], queryFn: () => api.get<Entity[]>("/entities") });
  const create = useAction((body: unknown) => api.post<Entity>("/entities", body), [["entities"], ["portfolio"]]);
  const set = (k: string, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const member = kind === "GROUP_MEMBER";

  function submit(e: FormEvent) {
    e.preventDefault();
    const body: Record<string, unknown> = {
      legalName: form.legalName, legalNameAr: form.legalNameAr || null, entityKind: kind, trn: form.trn || null,
      requireReviewer: form.requireReviewer, partnerThresholdAed: form.partnerThresholdAed || null,
    };
    if (member) {
      body.groupRepId = form.groupRepId;
    } else {
      body.filingFrequency = form.filingFrequency;
      body.firstPeriodStart = form.firstPeriodStart;
      body.einvoiceGoLive = form.einvoiceGoLive || null;
    }
    create.mutate(body, { onSuccess: (created) => navigate(`/entities/${(created as Entity).id}`) });
  }

  const reps = (entities.data ?? []).filter((x) => x.entityKind === "GROUP_REPRESENTATIVE");
  return (
    <form className="card" onSubmit={submit}>
      <h1>{t("entity.newTitle")}</h1>
      <ErrorAlert error={create.error} />
      <div className="form-grid">
        <Field label={t("entity.legalName")}><input required value={form.legalName} onChange={(e) => set("legalName", e.target.value)} /></Field>
        <Field label={t("entity.legalNameAr")}><input dir="rtl" value={form.legalNameAr} onChange={(e) => set("legalNameAr", e.target.value)} /></Field>
        <Field label={t("entity.kind")}>
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            {["STANDALONE", "GROUP_REPRESENTATIVE", "GROUP_MEMBER"].map((k) => <option key={k} value={k}>{t(`entityKind.${k}`)}</option>)}
          </select>
        </Field>
        {member ? (
          <Field label={t("entity.groupRep")}>
            <select required value={form.groupRepId} onChange={(e) => set("groupRepId", e.target.value)}>
              <option value="">{t("common.choose")}</option>
              {reps.map((r) => <option key={r.id} value={r.id}>{r.legalName}</option>)}
            </select>
          </Field>
        ) : (
          <>
            <Field label={t("entity.trn")} hint={t("entity.trnHint")}>
              <input required pattern="[0-9]{15}" inputMode="numeric" dir="ltr" value={form.trn} onChange={(e) => set("trn", e.target.value)} />
            </Field>
            <Field label={t("entity.frequency")}>
              <select value={form.filingFrequency} onChange={(e) => set("filingFrequency", e.target.value)}>
                <option value="MONTHLY">{t("entityFrequency.MONTHLY")}</option>
                <option value="QUARTERLY">{t("entityFrequency.QUARTERLY")}</option>
              </select>
            </Field>
            <Field label={t("entity.firstPeriod")} hint={t("entity.firstPeriodHint")}>
              <input type="date" required value={form.firstPeriodStart} onChange={(e) => set("firstPeriodStart", e.target.value)} />
            </Field>
            <Field label={t("entity.goLive")} hint={t("entity.goLiveHint")}>
              <input type="date" value={form.einvoiceGoLive} onChange={(e) => set("einvoiceGoLive", e.target.value)} />
            </Field>
            <Field label={t("entity.partnerThreshold")} hint={t("entity.partnerThresholdHint")}>
              <input type="number" min="0" step="0.01" value={form.partnerThresholdAed} onChange={(e) => set("partnerThresholdAed", e.target.value)} />
            </Field>
          </>
        )}
      </div>
      <label className="row" style={{ gap: 8, marginBlock: 12 }}>
        <input type="checkbox" style={{ width: "auto" }} checked={form.requireReviewer} onChange={(e) => set("requireReviewer", e.target.checked)} />
        {t("entity.requireReviewer")}
      </label>
      <button type="submit" className="primary" disabled={create.isPending}>{t("entity.create")}</button>
    </form>
  );
}
