import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { PortfolioRow } from "../api/types";
import { Query } from "../components/Query";
import { Badge, Money, Stat, useRoles } from "../components/ui";
import { formatDate } from "../i18n";

export function PortfolioPage() {
  const { t } = useTranslation();
  const roles = useRoles();
  const query = useQuery({ queryKey: ["portfolio"], queryFn: () => api.get<PortfolioRow[]>("/portfolio") });
  return (
    <>
      <div className="row">
        <h1>{t("portfolio.title")}</h1>
        <span className="spacer" />
        {roles.isAdmin && <Link className="button" to="/entities/new">{t("portfolio.newEntity")}</Link>}
      </div>
      <Query query={query}>
        {(rows) => {
          const overdue = rows.filter((r) => r.daysLeft !== null && r.daysLeft < 0).length;
          const blocked = rows.filter((r) => (r.blockingOpen ?? 0) > 0).length;
          const dueSoon = rows.filter((r) => r.daysLeft !== null && r.daysLeft >= 0 && r.daysLeft <= 10).length;
          return (
            <>
              <div className="stat-grid">
                <Stat label={t("portfolio.entities")} value={rows.length} />
                <Stat label={t("portfolio.dueSoon")} value={dueSoon} />
                <Stat label={t("portfolio.overdue")} value={overdue} />
                <Stat label={t("portfolio.withBlocking")} value={blocked} />
              </div>
              {rows.length === 0 ? (
                <p className="card muted">{t("portfolio.empty")}</p>
              ) : (
                <div className="card table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t("portfolio.entity")}</th>
                        <th>{t("portfolio.trn")}</th>
                        <th>{t("portfolio.period")}</th>
                        <th>{t("portfolio.due")}</th>
                        <th>{t("common.status")}</th>
                        <th className="num">{t("portfolio.blocking")}</th>
                        <th className="num">{t("portfolio.warnings")}</th>
                        <th className="num">{t("portfolio.netVat")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((r) => (
                        <tr key={r.entityId}>
                          <td><Link to={`/entities/${r.entityId}`}>{r.legalName}</Link></td>
                          <td className="ltr">{r.trn}</td>
                          <td>{r.periodId ? <Link to={`/periods/${r.periodId}`} className="ltr">{formatDate(r.periodStart)} → {formatDate(r.periodEnd)}</Link> : "–"}</td>
                          <td>
                            <span className="ltr">{formatDate(r.dueDate)}</span>
                            {r.daysLeft !== null && <small className="muted"> ({t("portfolio.daysLeft", { count: r.daysLeft })})</small>}
                          </td>
                          <td><Badge value={r.state} /></td>
                          <td className="num">{r.blockingOpen ?? "–"}</td>
                          <td className="num">{r.warningsOpen ?? "–"}</td>
                          <td className="num"><Money value={r.netVat} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          );
        }}
      </Query>
    </>
  );
}
