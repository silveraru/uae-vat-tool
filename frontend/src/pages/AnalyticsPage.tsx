import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { Entity, TrendPoint } from "../api/types";
import { ColumnChart, LineChart, type Series } from "../components/Charts";
import { Query } from "../components/Query";
import { Badge, Money, Stat } from "../components/ui";

// Categorical slots 1 to 3, checked with the palette validator against this app's white surface. Aqua
// sits under 3:1 there, which the legend and the table below this page's charts make up for.
const BLUE = "#2a78d6";
const ORANGE = "#eb6834";
const AQUA = "#1baf7a";

export function AnalyticsPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const entity = useQuery({ queryKey: ["entity", id], queryFn: () => api.get<Entity>(`/entities/${id}`) });
  const q = useQuery({ queryKey: ["trends", id], queryFn: () => api.get<TrendPoint[]>(`/entities/${id}/analytics/trends`) });
  return (
    <>
      <h1>{t("analytics.title")}</h1>
      {entity.data && <p><Link to={`/entities/${id}`}>{entity.data.legalName}</Link></p>}
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="card muted">{t("analytics.none")}</p> : <Trends rows={rows} />}
      </Query>
    </>
  );
}

function Trends({ rows }: { rows: TrendPoint[] }) {
  const { t } = useTranslation();
  const labels = rows.map((r) => r.periodEnd.slice(0, 7));
  const series = (name: string, color: string, pick: (r: TrendPoint) => number): Series => ({ name, color, values: rows.map(pick) });
  const percent = (v: number) => `${v.toFixed(1)}%`;
  const latest = rows[rows.length - 1];
  const previous = rows.length > 1 ? rows[rows.length - 2] : null;
  const change = (now: string, before: string | undefined) => before === undefined ? null : Number(now) - Number(before);
  const netChange = change(latest.netVat, previous?.netVat);
  return (
    <>
      <div className="stat-grid">
        <Stat label={t("analytics.outputTax")} value={<Money value={latest.outputTax} />} />
        <Stat label={t("analytics.recoverableTax")} value={<Money value={latest.recoverableTax} />} />
        <Stat label={t("analytics.netVat")} value={<Money value={latest.netVat} />} />
        <Stat label={t("analytics.recoveryRate")} value={`${Number(latest.recoveryRatePct).toFixed(1)}%`} />
        <Stat label={t("analytics.effectiveRate")} value={`${Number(latest.effectiveRatePct).toFixed(1)}%`} />
      </div>
      <p className="muted">
        {t("analytics.latest", { period: labels[labels.length - 1] })}
        {netChange !== null && <> {t("analytics.netChange", { amount: netChange.toLocaleString("en-AE", { maximumFractionDigits: 2, signDisplay: "always" }) })}</>}
      </p>
      <div className="chart-grid">
        <section className="card">
          <h2>{t("analytics.taxTitle")}</h2>
          <LineChart title={t("analytics.taxTitle")} labels={labels} series={[
            series(t("analytics.outputTax"), BLUE, (r) => Number(r.outputTax)),
            series(t("analytics.recoverableTax"), ORANGE, (r) => Number(r.recoverableTax)),
          ]} />
        </section>
        <section className="card">
          <h2>{t("analytics.netTitle")}</h2>
          <ColumnChart title={t("analytics.netTitle")} labels={labels} series={[series(t("analytics.netVat"), BLUE, (r) => Number(r.netVat))]} />
        </section>
        <section className="card">
          <h2>{t("analytics.recoveryRate")}</h2>
          <LineChart title={t("analytics.recoveryRate")} labels={labels} format={percent} series={[series(t("analytics.recoveryRate"), BLUE, (r) => Number(r.recoveryRatePct))]} />
        </section>
        <section className="card">
          <h2>{t("analytics.effectiveRate")}</h2>
          <LineChart title={t("analytics.effectiveRate")} labels={labels} format={percent} series={[series(t("analytics.effectiveRate"), BLUE, (r) => Number(r.effectiveRatePct))]} />
        </section>
        <section className="card">
          <h2>{t("analytics.exceptions")}</h2>
          <ColumnChart title={t("analytics.exceptions")} labels={labels} format={(v) => String(v)} series={[
            series(t("analytics.resolved"), BLUE, (r) => r.resolvedExceptions),
            series(t("analytics.open"), ORANGE, (r) => r.openExceptions),
            series(t("analytics.waived"), AQUA, (r) => r.waivedExceptions),
          ]} />
        </section>
      </div>
      <section className="card table-wrap">
        <h2>{t("analytics.table")}</h2>
        <table>
          <thead>
            <tr>
              <th>{t("portfolio.period")}</th><th>{t("common.status")}</th><th className="num">{t("analytics.outputTax")}</th><th className="num">{t("analytics.recoverableTax")}</th><th className="num">{t("analytics.netVat")}</th>
              <th className="num">{t("analytics.recoveryRate")}</th><th className="num">{t("analytics.effectiveRate")}</th><th className="num">{t("analytics.open")}</th><th className="num">{t("analytics.resolved")}</th><th className="num">{t("analytics.waived")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.periodId}>
                <td><Link to={`/periods/${r.periodId}`} className="ltr">{r.periodEnd.slice(0, 7)}</Link></td>
                <td><Badge value={r.state} /></td>
                <td className="num"><Money value={r.outputTax} /></td><td className="num"><Money value={r.recoverableTax} /></td><td className="num"><Money value={r.netVat} /></td>
                <td className="num">{Number(r.recoveryRatePct).toFixed(1)}%</td><td className="num">{Number(r.effectiveRatePct).toFixed(1)}%</td>
                <td className="num">{r.openExceptions}</td><td className="num">{r.resolvedExceptions}</td><td className="num">{r.waivedExceptions}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}
