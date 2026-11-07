import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { GeneratedDoc } from "../api/types";

/** Generated files with a download for each, and a warning when the rules behind them are unsigned. */
export function DocumentList({ documents }: { documents: GeneratedDoc[] }) {
  const { t } = useTranslation();
  if (documents.length === 0) return <p className="muted">{t("documents.none")}</p>;
  const draft = documents.some((d) => d.draftRules);
  return (
    <>
      {draft && <div className="alert bad" role="status">{t("filing.draftPack")}</div>}
      <ul className="doc-list">
        {documents.map((d) => (
          <li key={d.id}>
            <button type="button" className="linklike" onClick={() => void api.download(`/documents/${d.id}/content`, d.fileName)}>{d.fileName}</button>{" "}
            <small className="muted ltr">{d.createdAt.slice(0, 16).replace("T", " ")}</small>
          </li>
        ))}
      </ul>
    </>
  );
}

export async function uploadEvidence(entityId: string, file: File, periodId: string | null, description: string): Promise<string> {
  const fields: Record<string, string> = { description };
  if (periodId) fields.periodId = periodId;
  return (await api.upload<{ id: string }>(`/entities/${entityId}/evidence`, file, fields)).id;
}
