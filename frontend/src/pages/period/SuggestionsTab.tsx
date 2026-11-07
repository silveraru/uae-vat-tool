import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { Suggestion, SuggestionRun } from "../../api/types";
import { Query } from "../../components/Query";
import { Badge, ErrorAlert, Money, useAction, useRoles } from "../../components/ui";
import { usePeriod } from "./PeriodLayout";

export function SuggestionsTab() {
  const { t } = useTranslation();
  const { periodId, view, entity } = usePeriod();
  const roles = useRoles(entity.id);
  const key = ["suggestions", periodId];
  const q = useQuery({ queryKey: key, queryFn: () => api.get<Suggestion[]>(`/periods/${periodId}/suggestions?status=PROPOSED`) });
  const refresh = [key, ["return", periodId], ["exceptions", periodId], ["lines", periodId]];
  const [run, setRun] = useState<SuggestionRun | null>(null);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const generate = useAction(() => api.post<SuggestionRun>(`/periods/${periodId}/suggestions`), refresh);
  const accept = useAction((ids: string[]) => api.post("/suggestions/accept", { suggestionIds: ids }), refresh);
  const reject = useAction((id: string) => api.post(`/suggestions/${id}/reject`), refresh);
  const locked = ["APPROVED", "FILED", "PAID", "AMENDED"].includes(view.period.state);
  const canAct = roles.isPreparer && !locked;
  const toggle = (id: string) => setChosen((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  return (
    <>
      <ErrorAlert error={generate.error ?? accept.error ?? reject.error} />
      <section className="card">
        <h2>{t("suggestions.title")}</h2>
        <p className="muted">{t("suggestions.explain")}</p>
        {run && <div className="alert ok" role="status">{t("suggestions.generated", { examined: run.linesExamined, added: run.suggestionsAdded, none: run.noSuggestion })}</div>}
        {canAct && <button type="button" className="primary" disabled={generate.isPending} onClick={() => generate.mutate(undefined, { onSuccess: (r) => setRun(r as SuggestionRun) })}>{t("suggestions.generate")}</button>}
      </section>
      <Query query={q}>
        {(rows) => rows.length === 0 ? <p className="card muted">{t("suggestions.none")}</p> : (
          <section className="card table-wrap">
            {canAct && <button type="button" disabled={chosen.size === 0 || accept.isPending} onClick={() => accept.mutate([...chosen], { onSuccess: () => setChosen(new Set()) })}>{t("suggestions.acceptChosen", { count: chosen.size })}</button>}
            <table>
              <thead>
                <tr>
                  {canAct && <th><span className="sr-only">{t("lines.select")}</span></th>}
                  <th>{t("suggestions.document")}</th><th>{t("lines.description")}</th><th>{t("suggestions.now")}</th><th>{t("suggestions.suggested")}</th>
                  <th className="num">{t("suggestions.confidence")}</th><th>{t("suggestions.why")}</th><th className="num">{t("return.amount")}</th><th>{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((s) => (
                  <tr key={s.id}>
                    {canAct && <td><input type="checkbox" style={{ width: "auto" }} aria-label={t("lines.select")} checked={chosen.has(s.id)} onChange={() => toggle(s.id)} /></td>}
                    <td className="ltr">{s.docNumber ?? "–"}</td>
                    <td>{s.description ?? "–"}</td>
                    <td><Badge value={s.currentTreatment} group="treatment" /></td>
                    <td><Badge value={s.treatment} group="treatment" /></td>
                    <td className="num">{Math.round(Number(s.confidence) * 100)}%</td>
                    <td><small>{t(`suggestions.method.${s.method}`)}</small><br /><small className="muted ltr">{s.reason}</small></td>
                    <td className="num"><Money value={s.netAed} /></td>
                    <td>{canAct && <button type="button" onClick={() => reject.mutate(s.id)}>{t("suggestions.reject")}</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </Query>
      <p className="muted"><small>{t("suggestions.logged")}</small></p>
    </>
  );
}
