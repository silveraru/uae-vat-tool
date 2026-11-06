import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AuditEvent, AuditResult } from "../api/types";
import { Query } from "../components/Query";

export function AuditPage() {
  const { t } = useTranslation();
  const verify = useQuery({ queryKey: ["audit-verify"], queryFn: () => api.get<AuditResult>("/audit/verify") });
  const events = useQuery({ queryKey: ["audit-events"], queryFn: () => api.get<AuditEvent[]>("/audit/events?limit=200") });
  return (
    <>
      <h1>{t("audit.title")}</h1>
      <Query query={verify}>
        {(r) => (
          <div className={`alert ${r.intact ? "ok" : "bad"}`} role="status">
            {r.intact ? t("audit.intact", { count: r.eventsChecked }) : t("audit.broken", { seq: r.firstBrokenSeq, problem: r.problem })}
          </div>
        )}
      </Query>
      <Query query={events}>
        {(rows) => (
          <div className="card table-wrap">
            <table>
              <thead><tr><th>#</th><th>{t("audit.when")}</th><th>{t("audit.actor")}</th><th>{t("audit.action")}</th><th>{t("audit.object")}</th><th>{t("common.reason")}</th></tr></thead>
              <tbody>
                {rows.map((e) => (
                  <tr key={e.seq}>
                    <td className="num">{e.seq}</td>
                    <td className="ltr">{e.occurred_at}</td>
                    <td className="ltr">{e.actor_type === "SYSTEM" ? e.actor_id : e.actor_id.slice(0, 8)}</td>
                    <td className="ltr">{e.action}</td>
                    <td className="ltr">{e.object_type}</td>
                    <td>{e.reason ?? "–"}</td>
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
