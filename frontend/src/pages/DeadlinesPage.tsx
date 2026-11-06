import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { DeadlineRow, Notification } from "../api/types";
import { Query } from "../components/Query";
import { Badge, Money } from "../components/ui";
import { formatDate } from "../i18n";

export function DeadlinesPage() {
  const { t } = useTranslation();
  const deadlines = useQuery({ queryKey: ["deadlines"], queryFn: () => api.get<DeadlineRow[]>("/deadlines?days=120") });
  const notifications = useQuery({ queryKey: ["notifications"], queryFn: () => api.get<Notification[]>("/notifications") });
  return (
    <>
      <h1>{t("deadlines.title")}</h1>
      <Query query={deadlines}>
        {(rows) =>
          rows.length === 0 ? <p className="card muted">{t("deadlines.none")}</p> : (
            <div className="card table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t("portfolio.entity")}</th>
                    <th>{t("deadlines.periodEnd")}</th>
                    <th>{t("portfolio.due")}</th>
                    <th>{t("deadlines.urgency")}</th>
                    <th>{t("common.status")}</th>
                    <th className="num">{t("portfolio.blocking")}</th>
                    <th className="num">{t("portfolio.netVat")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.periodId}>
                      <td><Link to={`/periods/${r.periodId}`}>{r.legalName}</Link></td>
                      <td className="ltr">{formatDate(r.periodEnd)}</td>
                      <td><span className="ltr">{formatDate(r.dueDate)}</span> <small className="muted">({t("portfolio.daysLeft", { count: r.daysLeft })})</small></td>
                      <td><Badge value={r.urgency} group="urgency" /></td>
                      <td><Badge value={r.state} /></td>
                      <td className="num">{r.blockingOpen}</td>
                      <td className="num"><Money value={r.netVat} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        }
      </Query>
      <h2>{t("deadlines.alerts")}</h2>
      <Query query={notifications}>
        {(rows) => rows.length === 0 ? <p className="card muted">{t("deadlines.noAlerts")}</p> : (
          <ul className="card">
            {rows.map((n) => <li key={n.id}><strong>{n.title}</strong> <span className="muted">{n.body}</span></li>)}
          </ul>
        )}
      </Query>
    </>
  );
}
