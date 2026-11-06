import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { Query } from "../components/Query";

interface Me {
  displayName: string;
  tenantName: string;
  grants: { entityId: string | null; role: string }[];
}

export function HomePage() {
  const { t } = useTranslation();
  const me = useQuery({ queryKey: ["me"], queryFn: () => api.get<Me>("/me") });
  return (
    <Query query={me}>
      {(data) => (
        <div className="card">
          <h1>{t("home.welcome", { name: data.displayName })}</h1>
          <p><strong>{t("home.firm")}:</strong> {data.tenantName}</p>
          <p>
            <strong>{t("home.roles")}:</strong>{" "}
            {data.grants.map((g) => (
              <span key={`${g.entityId}-${g.role}`} className="badge">{t(`role.${g.role}`)}</span>
            ))}
          </p>
        </div>
      )}
    </Query>
  );
}
