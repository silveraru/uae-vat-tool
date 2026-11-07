import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ErrorText } from "./ui";

/** Shows loading and error states the same way everywhere. */
export function Query<T>({ query, children }: { query: UseQueryResult<T>; children: (data: T) => ReactNode }) {
  const { t } = useTranslation();
  if (query.isPending) return <p role="status">{t("common.loading")}</p>;
  if (query.isError) {
    return (
      <div className="alert bad" role="alert">
        {query.error.message ? <ErrorText error={query.error} /> : t("common.error")}{" "}
        <button type="button" onClick={() => void query.refetch()}>{t("common.retry")}</button>
      </div>
    );
  }
  return <>{children(query.data)}</>;
}
