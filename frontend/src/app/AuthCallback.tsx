import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { userManager } from "../auth/auth";

export function AuthCallback() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    userManager
      .signinCallback()
      .then((user) => {
        const state = user?.state as { returnTo?: string } | undefined;
        navigate(state?.returnTo ?? "/", { replace: true });
      })
      .catch(() => setFailed(true));
  }, [navigate]);

  return <main><p role="status">{failed ? t("common.error") : t("auth.signingIn")}</p></main>;
}
