import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, Outlet, useOutletContext, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Entity, ReturnView } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge } from "../../components/ui";
import { formatDate } from "../../i18n";

export interface PeriodContext {
  periodId: string;
  view: ReturnView;
  entity: Entity;
}

export function usePeriod() {
  return useOutletContext<PeriodContext>();
}

const TABS = ["", "data", "exceptions", "lines", "adjustments", "review", "filing", "evidence", "corrections"];

export function PeriodLayout() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const view = useQuery({
    queryKey: ["return", id],
    queryFn: () => api.get<ReturnView>(`/periods/${id}/return`),
    // Keeps the page in step while the worker recomputes after a data load.
    refetchInterval: 5000,
  });
  const entityId = view.data?.period.entityId;
  const entity = useQuery({ queryKey: ["entity", entityId], queryFn: () => api.get<Entity>(`/entities/${entityId}`), enabled: !!entityId });
  return (
    <Query query={view}>
      {(v) => (
        <>
          <div className="row" style={{ marginBlockEnd: 8 }}>
            <div>
              <h1 style={{ marginBlockEnd: 2 }}>
                {entity.data ? <Link to={`/entities/${entityId}`}>{entity.data.legalName}</Link> : "…"}
              </h1>
              <span className="muted ltr">{v.period.periodStart} → {v.period.periodEnd}</span>
              <span className="muted"> · {t("portfolio.due")} <span className="ltr">{formatDate(v.period.dueDate)}</span></span>
            </div>
            <span className="spacer" />
            <Badge value={v.period.state} />
          </div>
          {v.ruleDraft && <div className="alert warn" role="status">{t("period.draftRules")}</div>}
          <nav className="tabs" aria-label={t("period.sections")}>
            {TABS.map((tab) => (
              <NavLink key={tab} to={tab === "" ? `/periods/${id}` : `/periods/${id}/${tab}`} end>
                {t(`period.tab.${tab === "" ? "return" : tab}`)}
              </NavLink>
            ))}
          </nav>
          {entity.data && <Outlet context={{ periodId: id, view: v, entity: entity.data } satisfies PeriodContext} />}
        </>
      )}
    </Query>
  );
}
