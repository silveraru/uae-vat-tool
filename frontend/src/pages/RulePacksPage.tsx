import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { BundledPack, ClientRuleSummary, RulePack } from "../api/types";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, ReasonAction, useAction } from "../components/ui";
import { formatDate } from "../i18n";

export function RulePacksPage() {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["rule-packs"], queryFn: () => api.get<RulePack[]>("/rule-packs") });
  const signOff = useAction(({ id, note }: { id: string; note: string }) => api.post(`/rule-packs/${id}/sign-off`, { note }), [["rule-packs"]]);
  const bundled = useQuery({ queryKey: ["rule-pack-bundled"], queryFn: () => api.get<BundledPack>("/rule-packs/bundled") });
  const install = useAction(() => api.post("/rule-packs/install-bundled"), [["rule-packs"], ["rule-pack-bundled"]]);
  const clients = useQuery({ queryKey: ["client-rules"], queryFn: () => api.get<ClientRuleSummary[]>("/client-rules") });
  return (
    <>
      <h1>{t("rulePacks.title")}</h1>
      <p className="muted">{t("rulePacks.explain")}</p>
      <ErrorAlert error={signOff.error ?? install.error} />
      {bundled.data && !bundled.data.installed && (
        <div className="alert warn" role="status">
          <p>{t("rulePacks.newer", { version: bundled.data.version })}</p>
          <button type="button" className="primary" disabled={install.isPending} onClick={() => install.mutate(undefined)}>{t("rulePacks.install")}</button>
        </div>
      )}
      <Query query={query}>
        {(rows) => rows.length === 0 ? <p className="card muted">{t("rulePacks.none")}</p> : (
          <section className="card">
            <h2>{t("rulePacks.firmPack")}</h2>
            <p className="muted">{t("rulePacks.firmPackExplain")}</p>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t("rulePacks.version")}</th><th>{t("entity.effectiveFrom")}</th><th>{t("common.status")}</th>
                    <th>{t("rulePacks.basedOn")}</th><th>{t("rulePacks.signedOff")}</th><th>{t("common.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <Link className="ltr" to={`/rule-packs/${p.id}`}>{p.version}</Link>
                        {p.inForceToday && <> <span className="badge ok">{t("rulePacks.inForce")}</span></>}
                      </td>
                      <td className="ltr">{p.effectiveFrom}</td>
                      <td><Badge value={p.status} /></td>
                      <td>{p.basedOnVersion ? <><span className="ltr">{p.basedOnVersion}</span>{p.editNote && <div className="muted">{p.editNote}</div>}</> : p.version === bundled.data?.version ? t("rulePacks.shipped") : t("rulePacks.loadedWhole")}</td>
                      <td>
                        {p.signedOffAt ? <>{p.signedOffByName ?? p.signedOffBy} · {formatDate(p.signedOffAt)}{p.signOffNote && <div className="muted">{p.signOffNote}</div>}</> : "–"}
                        {p.retireNote && <div className="muted">{t("rulePacks.retiredBecause", { reason: p.retireNote })}</div>}
                      </td>
                      <td>
                        <div className="row">
                          <Link to={`/rule-packs/${p.id}`}>{t("rulePacks.open")}</Link>
                          {p.status === "DRAFT" && <ReasonAction label={t("rulePacks.signOff")} onSubmit={(note) => signOff.mutate({ id: p.id, note })} pending={signOff.isPending} />}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </Query>
      <section className="card">
        <h2>{t("clientRules.withOwnRules")}</h2>
        <p className="muted">{t("clientRules.explain")}</p>
        <Query query={clients}>
          {(rows) => rows.length === 0 ? <p className="muted">{t("clientRules.noneYet")}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t("clientRules.client")}</th><th>{t("rulePacks.version")}</th><th>{t("entity.effectiveFrom")}</th><th>{t("common.status")}</th><th>{t("clientRules.count")}</th></tr></thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.entityId}>
                      <td><Link to={`/entities/${c.entityId}#client-rules`}>{c.legalName}</Link></td>
                      <td className="num">{c.versionNo}</td>
                      <td className="ltr">{c.effectiveFrom}</td>
                      <td><Badge value={c.status} /></td>
                      <td className="num">{c.rules === 0 ? t("clientRules.ended") : c.rules}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Query>
      </section>
    </>
  );
}
