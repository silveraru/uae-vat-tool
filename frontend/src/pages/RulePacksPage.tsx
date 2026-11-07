import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { BundledPack, RulePack } from "../api/types";
import { Query } from "../components/Query";
import { Badge, ErrorAlert, ReasonAction, useAction } from "../components/ui";

export function RulePacksPage() {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["rule-packs"], queryFn: () => api.get<RulePack[]>("/rule-packs") });
  const signOff = useAction(({ id, note }: { id: string; note: string }) => api.post(`/rule-packs/${id}/sign-off`, { note }), [["rule-packs"]]);
  const bundled = useQuery({ queryKey: ["rule-pack-bundled"], queryFn: () => api.get<BundledPack>("/rule-packs/bundled") });
  const install = useAction(() => api.post("/rule-packs/install-bundled"), [["rule-packs"], ["rule-pack-bundled"]]);
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
          <div className="card table-wrap">
            <table>
              <thead><tr><th>{t("rulePacks.version")}</th><th>{t("entity.effectiveFrom")}</th><th>{t("common.status")}</th><th>{t("rulePacks.signedBy")}</th><th>{t("common.actions")}</th></tr></thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.id}>
                    <td className="ltr">{p.version}</td>
                    <td className="ltr">{p.effectiveFrom}</td>
                    <td><Badge value={p.status} /></td>
                    <td>{p.signOffNote ?? "–"}</td>
                    <td>{p.status === "DRAFT" && <ReasonAction label={t("rulePacks.signOff")} onSubmit={(note) => signOff.mutate({ id: p.id, note })} pending={signOff.isPending} />}</td>
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
