import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { api, ApiError } from "../api/client";
import type { Me } from "../api/types";
import { formatAed } from "../i18n";

const STATE_TONE: Record<string, string> = {
  OPEN: "", IN_REVIEW: "warn", APPROVED: "ok", FILED: "ok", PAID: "ok", AMENDED: "warn", ASSIGNED: "warn", RESOLVED: "ok", WAIVED: "warn",
  BLOCKING: "bad", WARNING: "warn", LOCKED: "ok", DRAFT: "", CORRECTED: "warn", MATCHED: "ok", MISMATCHED: "bad", UNCONFIRMED: "warn",
  PROPOSED: "warn", REJECTED: "bad", SIGNED_OFF: "ok", PROCESSED: "ok", OVERDUE: "bad", "T-2": "bad", "T-5": "warn", "T-10": "warn",
};

/** A status badge. The text carries the meaning, so colour is never the only signal. */
export function Badge({ value, group = "state" }: { value: string | null | undefined; group?: string }) {
  const { t } = useTranslation();
  if (!value) return <span className="muted">–</span>;
  const tone = STATE_TONE[value] ?? "";
  return <span className={`badge ${tone}`}>{t(`${group}.${value}`, { defaultValue: value })}</span>;
}

export function Money({ value }: { value: string | number | null | undefined }) {
  return <span className="num">{formatAed(value)}</span>;
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <div className="field">
      <label>
        {label}
        {children}
      </label>
      {hint && <small className="muted">{hint}</small>}
    </div>
  );
}

export function ErrorAlert({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError || error instanceof Error ? error.message : String(error);
  return <div className="alert bad" role="alert">{message}</div>;
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className={`value ${tone ?? ""}`}>{value}</div>
    </div>
  );
}

/** A write that shows its own failure and refreshes the data that depends on it. */
export function useAction<T>(fn: (arg: T) => Promise<unknown>, invalidate: QueryKey[] = []) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => Promise.all(invalidate.map((key) => qc.invalidateQueries({ queryKey: key }))),
  });
}

/** Asks for a reason before doing something that needs one. */
export function ReasonAction({ label, placeholder, onSubmit, danger, pending }: {
  label: string; placeholder?: string; onSubmit: (reason: string) => void; danger?: boolean; pending?: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  if (!open) {
    return <button type="button" className={danger ? "danger" : ""} onClick={() => setOpen(true)}>{label}</button>;
  }
  return (
    <form
      className="row"
      onSubmit={(e) => {
        e.preventDefault();
        if (reason.trim()) {
          onSubmit(reason);
          setOpen(false);
          setReason("");
        }
      }}
    >
      <Field label={t("common.reason")}>
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={placeholder} required autoFocus />
      </Field>
      <button type="submit" className={danger ? "danger" : "primary"} disabled={pending}>{label}</button>
      <button type="button" onClick={() => setOpen(false)}>{t("common.cancel")}</button>
    </form>
  );
}

export function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: () => api.get<Me>("/me") });
}

/** For showing the right buttons. The server enforces every one of these again. */
export function useRoles(entityId?: string) {
  const me = useMe();
  const grants = me.data?.grants ?? [];
  const roles = new Set(grants.filter((g) => g.entityId === null || g.entityId === entityId).map((g) => g.role));
  return {
    isPreparer: roles.has("PREPARER"),
    isReviewer: roles.has("TAX_REVIEWER"),
    isPartner: roles.has("ENGAGEMENT_PARTNER"),
    isApprover: roles.has("APPROVER"),
    isAdmin: roles.has("FIRM_ADMIN"),
    canFile: roles.has("TAX_REVIEWER") || roles.has("APPROVER"),
    canUploadEvidence: roles.has("PREPARER") || roles.has("TAX_REVIEWER") || roles.has("APPROVER"),
    canAdjust: roles.has("PREPARER") || roles.has("TAX_REVIEWER"),
    canDecide: roles.has("TAX_REVIEWER") || roles.has("APPROVER"),
    canWaive: roles.has("TAX_REVIEWER") || roles.has("APPROVER") || roles.has("ENGAGEMENT_PARTNER"),
  };
}

export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const { t } = useTranslation();
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="row" style={{ marginBlockStart: 8 }}>
      <button type="button" disabled={page <= 0} onClick={() => onPage(page - 1)}>{t("common.previous")}</button>
      <span className="muted">{t("common.pageOf", { page: page + 1, pages, total })}</span>
      <button type="button" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>{t("common.next")}</button>
    </div>
  );
}
